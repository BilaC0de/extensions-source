package eu.kanade.tachiyomi.extension.fr.scanmanga

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater

class ScanManga : HttpSource(), ConfigurableSource {
    override val name = "Scan-Manga"

    override val baseUrl = "https://m.scan-manga.com"
    private val baseImageUrl = "https://static.scan-manga.com/img/manga"

    override val lang = "fr"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<android.app.Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // User-Agents rotatifs pour éviter la détection
    private val rotatingUserAgents = listOf(
        "Mozilla/5.0 (Linux; Android 11; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.45 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 10; SM-A505F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.105 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 11; OnePlus 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/93.0.4577.62 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 12; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/98.0.4758.101 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Mobile Safari/537.36",
    )

    private fun getUserAgentMode(): String =
        preferences.getString("pref_useragent_mode", "default") ?: "default"

    private fun getCustomUserAgent(): String =
        preferences.getString("pref_custom_useragent", "") ?: ""

    private fun getRotatingUserAgent(): String {
        val hourOfDay = (System.currentTimeMillis() / (1000 * 60 * 60)) % rotatingUserAgents.size
        return rotatingUserAgents[hourOfDay.toInt()]
    }

    private fun getCurrentUserAgent(): String {
        return when (getUserAgentMode()) {
            "custom" -> {
                val custom = getCustomUserAgent()
                if (custom.isNotBlank()) {
                    custom
                } else {
                    super.headersBuilder().build()["User-Agent"] ?: ""
                }
            }
            "rotating" -> getRotatingUserAgent()
            else -> super.headersBuilder().build()["User-Agent"] ?: ""
        }
    }

    override fun headersBuilder(): Headers.Builder {
        val builder =
            super.headersBuilder().add("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")

        when (getUserAgentMode()) {
            "custom" -> {
                val custom = getCustomUserAgent()
                if (custom.isNotBlank()) {
                    builder.set("User-Agent", custom)
                }
            }
            "rotating" -> {
                builder.set("User-Agent", getRotatingUserAgent())
            }
        }

        return builder
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val userAgentModePref = ListPreference(screen.context).apply {
            key = "pref_useragent_mode"
            title = "Mode User-Agent"
            summary = "Choisir comment gérer le User-Agent"
            entries = arrayOf(
                "Par défaut (Mihon)",
                "Personnalisé",
                "Rotatif automatique",
            )
            entryValues = arrayOf("default", "custom", "rotating")
            setDefaultValue("default")
        }
        screen.addPreference(userAgentModePref)

        val customUserAgentPref = EditTextPreference(screen.context).apply {
            key = "pref_custom_useragent"
            title = "User-Agent personnalisé"
            summary = "Utilisé seulement si mode 'Personnalisé' est sélectionné"
            dialogTitle = "User-Agent personnalisé"
            dialogMessage =
                "Exemple : Mozilla/5.0 (Linux; Android 11; SM-G991B) AppleWebKit/537.36..."
            setDefaultValue("")
        }
        screen.addPreference(customUserAgentPref)

        val cacheModePref = ListPreference(screen.context).apply {
            key = "pref_cache_mode"
            title = "Mode de cache des covers"
            summary = "Cache hybride recommandé pour de meilleures performances"
            entries =
                arrayOf("Cache hybride (recommandé)", "Mémoire uniquement", "Persistant uniquement")
            entryValues = arrayOf("hybrid", "memory", "persistent")
            setDefaultValue("hybrid")
        }
        screen.addPreference(cacheModePref)

        val loadingModePref = ListPreference(screen.context).apply {
            key = "pref_loading_mode"
            title = "Mode de chargement des covers"
            summary = "Rapide = affichage immédiat, Complet = attendre toutes les covers"
            entries = arrayOf("Rapide (affichage immédiat)", "Complet (attendre toutes les covers)")
            entryValues = arrayOf("fast", "complete")
            setDefaultValue("fast")
        }
        screen.addPreference(loadingModePref)

        val batchSizePref = ListPreference(screen.context).apply {
            key = "pref_batch_size"
            title = "Taille des lots de chargement"
            summary = "Plus grand = plus rapide mais plus de charge serveur"
            entries = arrayOf("5 covers", "10 covers", "15 covers", "20 covers")
            entryValues = arrayOf("5", "10", "15", "20")
            setDefaultValue("15")
        }
        screen.addPreference(batchSizePref)
    }

    private fun getCacheMode(): String =
        preferences.getString("pref_cache_mode", "hybrid") ?: "hybrid"

    private fun getBatchSize(): Int =
        preferences.getString("pref_batch_size", "15")?.toIntOrNull() ?: 15

    private fun getLoadingMode(): String =
        preferences.getString("pref_loading_mode", "fast") ?: "fast"

    companion object {
        private val memoryCache = ConcurrentHashMap<String, Pair<String, Long>>()
        private const val MEMORY_CACHE_DURATION = 3 * 24 * 60 * 60 * 1000L
        private const val TAG = "ScanManga"
    }

    private val persistentCache: ConcurrentHashMap<String, Pair<String, Long>> by lazy {
        ConcurrentHashMap(loadPersistentCache())
    }

    private val persistentCacheDuration = 7 * 24 * 60 * 60 * 1000L

    private fun loadPersistentCache(): Map<String, Pair<String, Long>> {
        return try {
            val cache = mutableMapOf<String, Pair<String, Long>>()
            val now = System.currentTimeMillis()

            val allPrefs = preferences.all
            allPrefs.keys.filter { it.startsWith("cover_") }.forEach { key ->
                try {
                    val value = preferences.getString(key, null)
                    if (value != null) {
                        val parts = value.split("|")
                        if (parts.size == 2) {
                            val url = parts[0]
                            val timestamp = parts[1].toLong()

                            if (now - timestamp < persistentCacheDuration) {
                                val mangaUrl = key.removePrefix("cover_")
                                cache[mangaUrl] = url to timestamp
                            } else {
                                preferences.edit().remove(key).apply()
                            }
                        }
                    }
                } catch (e: Exception) {
                    preferences.edit().remove(key).apply()
                }
            }
            cache
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun savePersistentCache() {
        try {
            val editor = preferences.edit()
            val now = System.currentTimeMillis()

            persistentCache.entries.forEach { (key, value) ->
                if (now - value.second < persistentCacheDuration) {
                    val prefKey = "cover_$key"
                    val prefValue = "${value.first}|${value.second}"
                    editor.putString(prefKey, prefValue)
                }
            }

            editor.apply()
        } catch (e: Exception) {
            // Ignorer les erreurs
        }
    }

    private fun getCoverUrl(mangaUrl: String): String? {
        val now = System.currentTimeMillis()

        return when (getCacheMode()) {
            "hybrid" -> getCoverHybridCache(mangaUrl, now)
            "memory" -> getCoverFromMemoryCache(mangaUrl, now)
            "persistent" -> getCoverFromPersistentCache(mangaUrl, now)
            else -> fetchCoverFromServer(mangaUrl)
        }
    }

    private fun getCoverHybridCache(mangaUrl: String, now: Long): String? {
        memoryCache[mangaUrl]?.let { cached ->
            if (now - cached.second < MEMORY_CACHE_DURATION) {
                return cached.first
            } else {
                memoryCache.remove(mangaUrl)
            }
        }

        persistentCache[mangaUrl]?.let { cached ->
            if (now - cached.second < persistentCacheDuration) {
                memoryCache[mangaUrl] = cached
                return cached.first
            } else {
                persistentCache.remove(mangaUrl)
            }
        }

        return fetchCoverFromServer(mangaUrl)?.also { coverUrl ->
            val entry = coverUrl to now
            memoryCache[mangaUrl] = entry
            persistentCache[mangaUrl] = entry
            Thread {
                savePersistentCache()
            }.start()
        }
    }

    private fun getCoverFromMemoryCache(mangaUrl: String, now: Long): String? {
        memoryCache[mangaUrl]?.let { cached ->
            if (now - cached.second < MEMORY_CACHE_DURATION) {
                return cached.first
            } else {
                memoryCache.remove(mangaUrl)
            }
        }

        return fetchCoverFromServer(mangaUrl)?.also { coverUrl ->
            memoryCache[mangaUrl] = coverUrl to now
        }
    }

    private fun getCoverFromPersistentCache(mangaUrl: String, now: Long): String? {
        persistentCache[mangaUrl]?.let { cached ->
            if (now - cached.second < persistentCacheDuration) {
                return cached.first
            } else {
                persistentCache.remove(mangaUrl)
            }
        }

        return fetchCoverFromServer(mangaUrl)?.also { coverUrl ->
            persistentCache[mangaUrl] = coverUrl to now
            Thread {
                savePersistentCache()
            }.start()
        }
    }

    private fun fetchCoverFromServer(mangaUrl: String): String? {
        return try {
            val doc = client.newCall(GET(baseUrl + mangaUrl, headers)).execute().use {
                it.asJsoup()
            }
            val coverUrl = doc.select("div.full_img_serie img[itemprop=image]").attr("src")
            if (coverUrl.isNotBlank()) coverUrl else null
        } catch (e: Exception) {
            null
        }
    }

    private fun loadCoversInBulk(mangas: List<SManga>) {
        val now = System.currentTimeMillis()
        val mangasNeedingCovers = mutableListOf<SManga>()

        mangas.forEach { manga ->
            val coverUrl = when (getCacheMode()) {
                "hybrid" -> {
                    memoryCache[manga.url]?.let { cached ->
                        if (now - cached.second < MEMORY_CACHE_DURATION) {
                            cached.first
                        } else {
                            memoryCache.remove(manga.url)
                            null
                        }
                    }
                        ?: persistentCache[manga.url]?.let { cached ->
                            if (now - cached.second < persistentCacheDuration) {
                                memoryCache[manga.url] = cached
                                cached.first
                            } else {
                                persistentCache.remove(manga.url)
                                null
                            }
                        }
                }

                "memory" -> {
                    memoryCache[manga.url]?.let { cached ->
                        if (now - cached.second < MEMORY_CACHE_DURATION) {
                            cached.first
                        } else {
                            memoryCache.remove(manga.url)
                            null
                        }
                    }
                }

                "persistent" -> {
                    persistentCache[manga.url]?.let { cached ->
                        if (now - cached.second < persistentCacheDuration) {
                            cached.first
                        } else {
                            persistentCache.remove(manga.url)
                            null
                        }
                    }
                }

                else -> null
            }

            if (coverUrl != null) {
                manga.thumbnail_url = coverUrl
            } else {
                mangasNeedingCovers.add(manga)
            }
        }

        if (mangasNeedingCovers.isNotEmpty()) {
            when (getLoadingMode()) {
                "fast" -> {
                    Thread {
                        loadMissingCoversInBackground(mangasNeedingCovers)
                    }.start()
                }

                "complete" -> {
                    loadMissingCoversInBackground(mangasNeedingCovers)
                }
            }
        }
    }

    private fun loadMissingCoversInBackground(mangas: List<SManga>) {
        val batchSize = getBatchSize()
        mangas.chunked(batchSize).forEachIndexed { batchIndex, batch ->
            val threads = batch.map { manga ->
                Thread {
                    fetchCoverFromServer(manga.url)?.let { coverUrl ->
                        val now = System.currentTimeMillis()
                        val entry = coverUrl to now
                        manga.thumbnail_url = coverUrl
                        memoryCache[manga.url] = entry
                        persistentCache[manga.url] = entry
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach {
                try {
                    it.join()
                } catch (e: InterruptedException) {
                    return
                }
            }

            if (batchIndex < mangas.chunked(batchSize).size - 1) {
                Thread.sleep(200L)
            }
        }

        Thread { savePersistentCache() }.start()
    }

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/TOP-Manga-Webtoon-36.html", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val mangas = response.asJsoup().select("#carouselTOPContainer > div.top").map { element ->
            val titleElement = element.selectFirst("a.atop")!!
            val mangaUrl = titleElement.attr("href")

            SManga.create().apply {
                title = titleElement.text()
                setUrlWithoutDomain(mangaUrl)
                thumbnail_url = null
            }
        }

        loadCoversInBulk(mangas)

        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val mangas = response.asJsoup().select("#content_news .publi").map { element ->
            val mangaElement = element.selectFirst("a.l_manga")!!
            val mangaUrl = mangaElement.attr("href")

            SManga.create().apply {
                title = mangaElement.text()
                setUrlWithoutDomain(mangaUrl)
                thumbnail_url = null
            }
        }

        loadCoversInBulk(mangas)

        return MangasPage(mangas, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/search/quick.json".toHttpUrl().newBuilder()
            .addQueryParameter("term", query).build().toString()

        val newHeaders =
            headers.newBuilder().add("Content-type", "application/json; charset=UTF-8").build()

        return GET(url, newHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val json = response.body.string()
        if (json == "[]") return MangasPage(emptyList(), false)

        return MangasPage(
            json.parseAs<MangaSearchDto>().title?.map {
                SManga.create().apply {
                    title = it.nom_match
                    setUrlWithoutDomain(it.url)
                    thumbnail_url = "$baseImageUrl/${it.image}"
                }
            } ?: emptyList(),
            false,
        )
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {
            title = document.select("h1.main_title[itemprop=name]").text()
            author = document.select("div[itemprop=author]").text()
            description = document.selectFirst("div.titres_desc[itemprop=description]")?.text()
            genre = document.selectFirst("div.titres_souspart span[itemprop=genre]")?.text()

            val statutText = document.selectFirst("div.titres_souspart")?.ownText()
            status = when {
                statutText?.contains("En cours", ignoreCase = true) == true -> SManga.ONGOING
                statutText?.contains("Terminé", ignoreCase = true) == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            thumbnail_url = document.select("div.full_img_serie img[itemprop=image]").attr("src")
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("div.chapt_m").map { element ->
            val linkEl = element.selectFirst("td.publimg span.i a")!!
            val titleEl = element.selectFirst("td.publititle")

            val chapterName = linkEl.text()
            val extraTitle = titleEl?.text()

            SChapter.create().apply {
                name =
                    if (!extraTitle.isNullOrEmpty()) "$chapterName - $extraTitle" else chapterName
                setUrlWithoutDomain(linkEl.absUrl("href"))
            }
        }
    }

    private fun decodeHunter(obfuscatedJs: String): String {
        val regex =
            Regex("""eval\(function\(h,u,n,t,e,r\)\{.*?\}\("([^"]+)",\d+,"([^"]+)",(\d+),(\d+),\d+\)\)""")
        val (encoded, mask, intervalStr, optionStr) = regex.find(obfuscatedJs)?.destructured
            ?: error("Failed to match obfuscation pattern")

        val interval = intervalStr.toInt()
        val option = optionStr.toInt()
        val delimiter = mask[option]
        val tokens = encoded.split(delimiter).filter { it.isNotEmpty() }
        val reversedMap = mask.withIndex().associate { it.value to it.index }

        return buildString {
            for (token in tokens) {
                val digitString = token.map { c ->
                    reversedMap[c]?.toString() ?: error("Invalid masked character: $c")
                }.joinToString("")

                val number = digitString.toIntOrNull(option)
                    ?: error("Failed to parse token: $digitString as base $option")

                val originalCharCode = number - interval

                append(originalCharCode.toChar())
            }
        }
    }

    private fun dataAPI(data: String, idc: Int): UrlPayload {
        try {
            Log.d(TAG, "=== dataAPI START ===")
            Log.d(TAG, "Input: ${data.length} chars, IDC: $idc (0x${idc.toString(16)})")

            // Étape 1: Base64 decode direct
            val compressedBytes = Base64.decode(data, Base64.DEFAULT)
            Log.d(TAG, "Decoded: ${compressedBytes.size} bytes")
            Log.d(TAG, "First 10 bytes: ${compressedBytes.take(10).joinToString { (it.toInt() and 0xFF).toString() }}")

            // Étape 2: Inflate (essayer les deux modes)
            var inflated: String? = null

            // Essai avec header zlib
            try {
                val inf = Inflater(false)
                inf.setInput(compressedBytes)
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(1024)
                while (!inf.finished()) {
                    val c = inf.inflate(buf)
                    if (c == 0 && inf.needsInput()) break
                    out.write(buf, 0, c)
                }
                inf.end()
                inflated = out.toString("UTF-8")
                Log.d(TAG, "✓ Inflated with zlib: ${inflated.length} chars")
            } catch (e: Exception) {
                Log.d(TAG, "✗ Zlib failed: ${e.message}")
                // Essai sans header
                try {
                    val inf = Inflater(true)
                    inf.setInput(compressedBytes)
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(1024)
                    while (!inf.finished()) {
                        val c = inf.inflate(buf)
                        if (c == 0 && inf.needsInput()) break
                        out.write(buf, 0, c)
                    }
                    inf.end()
                    inflated = out.toString("UTF-8")
                    Log.d(TAG, "✓ Inflated raw: ${inflated.length} chars")
                } catch (e2: Exception) {
                    Log.e(TAG, "✗ Both inflate failed", e2)
                    throw e2
                }
            }

            if (inflated == null) {
                throw Exception("Decompression failed with both methods")
            }

            Log.d(TAG, "Content start: ${inflated.take(100)}")

            // Étape 3: Remove suffix
            val hex = idc.toString(16)
            val clean = inflated.removeSuffix(hex)

            // Étape 4: Reverse
            val rev = clean.reversed()

            // Étape 5: Final decode
            val json = String(Base64.decode(rev, Base64.DEFAULT), Charsets.UTF_8)
            Log.d(TAG, "✓ Final JSON: $json")

            return json.parseAs<UrlPayload>()
        } catch (e: Exception) {
            Log.e(TAG, "=== FAILED ===", e)
            throw e
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val packedScript = document.selectFirst("script:containsData(h,u,n,t,e,r)")!!.data()

        Log.d(TAG, "pageListParse - Found packed script")

        val unpackedScript = decodeHunter(packedScript)
        Log.d(TAG, "pageListParse - Unpacked script (first 200 chars): ${unpackedScript.take(200)}")

        val parametersRegex = Regex("""sml = '([^']+)';\n?.*var sme = '([^']+)'""")

        val (sml, sme) = parametersRegex.find(unpackedScript)?.destructured
            ?: error("Failed to extract parameters from script.")

        Log.d(TAG, "pageListParse - sml: $sml")
        Log.d(TAG, "pageListParse - sme: $sme")

        val chapterInfoRegex = Regex("""const idc = (\d+)""")
        val (chapterId) = chapterInfoRegex.find(packedScript)?.destructured
            ?: error("Failed to extract chapter ID.")

        Log.d(TAG, "pageListParse - Chapter ID: $chapterId")

        // Chercher le sous-domaine API dans le script
        val apiDomainRegex = Regex("""(?:var\s+)?(?:api_url|apiUrl|API_URL)\s*=\s*['"]https?://([^/'"]+)""")
        val apiDomain = apiDomainRegex.find(unpackedScript)?.groupValues?.get(1)
            ?: apiDomainRegex.find(packedScript)?.groupValues?.get(1)
            ?: run {
                // Essayer d'autres patterns
                val altRegex = Regex("""['"]https?://([a-z0-9-]+\.scan-manga\.com)/(?:api/)?lel/""")
                altRegex.find(unpackedScript)?.groupValues?.get(1)
                    ?: altRegex.find(packedScript)?.groupValues?.get(1)
                    ?: "bqj.scan-manga.com" // fallback
            }

        Log.d(TAG, "pageListParse - API Domain: $apiDomain")

        val mediaType = "application/json; charset=UTF-8".toMediaType()
        val requestBody = """{"a":"$sme","b":"$sml"}"""

        val documentUrl = document.baseUri().toHttpUrl()

        val apiUrl = "https://$apiDomain/lel/$chapterId.json"
        Log.d(TAG, "pageListParse - Full API URL: $apiUrl")
        Log.d(TAG, "pageListParse - Request body: $requestBody")

        val pageListRequest = POST(
            apiUrl,
            headers.newBuilder()
                .add("Origin", "${documentUrl.scheme}://${documentUrl.host}")
                .add("Referer", documentUrl.toString())
                .add("Token", "yf")
                .build(),
            requestBody.toRequestBody(mediaType),
        )

        val lelResponse = client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
            .newCall(pageListRequest)
            .execute()
            .use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = resp.body.string()
                    Log.e(TAG, "pageListParse - Error response: ${resp.code} - $errorBody")
                    error("Unexpected error while fetching lel: ${resp.code} - $errorBody")
                }
                val responseBody = resp.body.string()
                Log.d(TAG, "pageListParse - Response body (first 200 chars): ${responseBody.take(200)}")
                dataAPI(responseBody, chapterId.toInt())
            }

        return lelResponse.generateImageUrls().map { Page(it.first, imageUrl = it.second) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headers.newBuilder().add("Origin", baseUrl).build()

        return GET(page.imageUrl!!, imgHeaders)
    }
}
