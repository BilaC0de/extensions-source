package eu.kanade.tachiyomi.extension.fr.scanmanga

import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient

@Serializable
class RemoteMarkersBasic(
    val validVersion: List<Int>,
)

@Serializable
class RemoteMarkers(
    val validVersion: List<Int>,
    val selectors: Selectors,
    val regexes: Regexes,
    val apiConfig: ApiConfig,
) {
    @Serializable
    class Selectors(val packedScript: String)

    @Serializable
    class Regexes(
        val hunterObfuscation: String,
        val smlParam: String,
        val smeParam: String,
        val chapterInfo: String,
    )

    @Serializable
    class ApiConfig(
        val pageListUrl: String,
        val requestBody: String,
        val headers: Map<String, String>? = null,
    )
}

@Serializable
class WhatsUp(
    val systemNotice: String? = null,
)

class MarkerManager(
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
) {
    private var cachedMarkers: RemoteMarkers? = null
    private var fallbackEnabled = false

    companion object {
        private const val MARKER_URL = "https://github.com/Starmania/scan-manga/releases/latest/download/marker.json"
        private const val NOTICE_URL = "https://github.com/Starmania/scan-manga/releases/latest/download/message.json"

        const val PREF_MARKERS_JSON = "external_markers_json"

        private const val PARSER_VERSION = 1
    }

    // Fallback markers based on direct HTML analysis of scan-manga.
    // Used when remote markers are broken (wrong selector, bad regex, etc.)
    private val hardcodedMarkers = RemoteMarkers(
        validVersion = listOf(PARSER_VERSION),
        selectors = RemoteMarkers.Selectors(
            packedScript = "script:containsData(const idc)",
        ),
        regexes = RemoteMarkers.Regexes(
            hunterObfuscation = """eval\s*\(\s*function\s*\(\s*\w\s*,\s*\w\s*,\s*\w\s*,\s*\w\s*,\s*\w\s*,\s*\w\s*(?:,\s*[^)]+)?\)\s*\{\s*.*?\s*\}\s*\(\s*"([^"]+)"\s*,\s*\d+\s*,\s*"([^"]+)"\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*\d+\s*\)\s*\)""",
            smlParam = """sml\s*=\s*'([^']+)'""",
            smeParam = """sme\s*=\s*'([^']+)'""",
            chapterInfo = """const idc = (\d+)""",
        ),
        apiConfig = RemoteMarkers.ApiConfig(
            pageListUrl = "https://bqj.{topDomain}/lel/{chapterId}.json",
            requestBody = """{"a":"{sme}","b":"{sml}","c":"{fingerprint}"}""",
            headers = mapOf(
                "Token" to "yf",
                "source" to "{chapterUrl}",
            ),
        ),
    )

    /**
     * Switches all subsequent [getMarkers] calls to the hardcoded fallback.
     * Called by [ScanManga.runSafe] after an initial failure to avoid reusing
     * potentially broken remote markers.
     */
    fun enableFallback() {
        fallbackEnabled = true
    }

    fun getMarkers(): RemoteMarkers {
        if (fallbackEnabled) return hardcodedMarkers

        cachedMarkers?.let {
            if (PARSER_VERSION in it.validVersion) return it
        }

        val cachedJson = preferences.getString(PREF_MARKERS_JSON, null)
        if (cachedJson != null) {
            runCatching {
                val basic = cachedJson.parseAs<RemoteMarkersBasic>()
                if (PARSER_VERSION in basic.validVersion) {
                    return cachedJson.parseAs<RemoteMarkers>().also { cachedMarkers = it }
                }
            }
        }

        return fetchWithRetry()
    }

    private fun fetchWithRetry(): RemoteMarkers {
        return (1..3).firstNotNullOfOrNull {
            runCatching {
                client.newCall(GET(MARKER_URL)).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null

                    val bodyString = response.body.string()
                    val basic = bodyString.parseAs<RemoteMarkersBasic>()

                    if (PARSER_VERSION !in basic.validVersion) return@runCatching null

                    bodyString.parseAs<RemoteMarkers>().also { markers ->
                        cachedMarkers = markers
                        preferences.edit().putString(PREF_MARKERS_JSON, bodyString).apply()
                    }
                }
            }.getOrNull()
        } ?: error("Update the extension !")
    }

    fun handleFatalFailure(originalError: Throwable): Nothing {
        val messageObject = fetchMessage()

        if (messageObject?.systemNotice != null) {
            error(messageObject.systemNotice.replace("{message}", originalError.message ?: ""))
        }

        throw originalError
    }

    private fun fetchMessage(): WhatsUp? = runCatching {
        client.newCall(GET(NOTICE_URL)).execute().use { response ->
            if (!response.isSuccessful) null else response.parseAs<WhatsUp>()
        }
    }.getOrNull()
}
