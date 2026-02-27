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
import java.util.zip.Inflater

class ScanManga : HttpSource(), ConfigurableSource {
    override val name = "Scan-Manga"

    override val baseUrl = "https://m.scan-manga.com"
    private val wwwBaseUrl = "https://www.scan-manga.com"
    private val baseImageUrl = "https://static.scan-manga.com/img/manga"

    override val lang = "fr"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<android.app.Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val TAG = "ScanManga"
    }

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

    override fun headersBuilder(): Headers.Builder {
        val builder =
            super.headersBuilder().add("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
        when (getUserAgentMode()) {
            "custom" -> {
                val custom = getCustomUserAgent()
                if (custom.isNotBlank()) builder.set("User-Agent", custom)
            }

            "rotating" -> builder.set("User-Agent", getRotatingUserAgent())
        }
        return builder
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val userAgentModePref = ListPreference(screen.context).apply {
            key = "pref_useragent_mode"
            title = "Mode User-Agent"
            summary = "Choisir comment gérer le User-Agent"
            entries = arrayOf("Par défaut (Mihon)", "Personnalisé", "Rotatif automatique")
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
    }

    // ─────────────────────────────────────────────────────────────
    // COVERS VIA API BQJ
    //
    // Au lieu de charger chaque page manga pour récupérer la cover
    // (lent, lourd), on appelle l'API JSON légère de bqj.
    //
    // URL : https://bqj.scan-manga.com/popmanga_ID.json
    // Réponse : ["Titre", "Auteur", ..., "cover_1_123.jpg", ...]
    //                                        ↑ index 5 = nom du fichier cover
    //
    // L'ID du manga est le nombre au début de l'URL :
    // "/16224/Reincarnated-as-the-Grand-Duke.html" → ID = 16224
    // ─────────────────────────────────────────────────────────────

    private fun fetchCoverViaBqj(mangaUrl: String): String? {
        return try {
            // Extrait l'ID depuis l'URL ex : "/16224/Mon-Manga.html" → "16224"
            val mangaId = mangaUrl.trimStart('/').substringBefore('/').toLongOrNull() ?: return null
            val jsonUrl = "https://bqj.scan-manga.com/popmanga_$mangaId.json"
            val raw = client.newCall(GET(jsonUrl, headers)).execute().use { it.body.string() }
            val array = org.json.JSONArray(raw)
            val filename = array.optString(5)
            if (filename.isNotBlank()) "$baseImageUrl/$filename" else null
        } catch (e: Exception) {
            Log.w(TAG, "fetchCoverViaBqj failed pour $mangaUrl", e)
            null
        }
    }

    private fun loadCovers(mangas: List<SManga>) {
        // 3 threads max pour éviter le 429 (trop de requêtes) sur bqj
        val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
        mangas.forEachIndexed { index, manga ->
            executor.submit {
                try {
                    // Petit délai pour étaler les requêtes
                    if (index > 0) Thread.sleep((index % 3) * 150L)
                    manga.thumbnail_url = fetchCoverViaBqj(manga.url)
                } catch (e: Exception) {
                    Log.w(TAG, "loadCovers failed pour ${manga.url}", e)
                }
            }
        }
        executor.shutdown()
        executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
    }

    // ─────────────────────────────────────────────────────────────
    // POPULAR (TOP Manga) — CODE ORIGINAL INCHANGÉ
    // ─────────────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/TOP-Manga-Webtoon-36.html", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val mangas = response.asJsoup().select("#carouselTOPContainer > div.top").map { element ->
            val titleElement = element.selectFirst("a.atop")!!
            SManga.create().apply {
                title = titleElement.text()
                setUrlWithoutDomain(titleElement.attr("href"))
                thumbnail_url = null
            }
        }
        // Remplace le cache complexe par l'API bqj simple
        loadCovers(mangas)
        return MangasPage(mangas, false)
    }

    // ─────────────────────────────────────────────────────────────
    // LATEST UPDATES — CODE ORIGINAL INCHANGÉ
    // ─────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val mangas = response.asJsoup().select("#content_news .publi").map { element ->
            val mangaElement = element.selectFirst("a.l_manga")!!
            SManga.create().apply {
                title = mangaElement.text()
                setUrlWithoutDomain(mangaElement.attr("href"))
                thumbnail_url = null
            }
        }
        // Remplace le cache complexe par l'API bqj simple
        loadCovers(mangas)
        return MangasPage(mangas, false)
    }

    // ─────────────────────────────────────────────────────────────
    // SEARCH — CODE ORIGINAL INCHANGÉ
    // ─────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────
    // MANGA DETAILS — CODE ORIGINAL INCHANGÉ
    // ─────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────
    // CHAPTER LIST — CODE ORIGINAL INCHANGÉ
    // ─────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────
    // PAGE LIST — CODE ORIGINAL INCHANGÉ
    // ─────────────────────────────────────────────────────────────

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
                append((number - interval).toChar())
            }
        }
    }

    private fun dataAPI(data: String, idc: Int): UrlPayload {
        try {
            val compressedBytes = Base64.decode(data, Base64.DEFAULT)
            var inflated: String? = null
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
            } catch (e: Exception) {
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
                } catch (e2: Exception) {
                    Log.e(TAG, "Both inflate failed", e2)
                    throw e2
                }
            }
            if (inflated == null) throw Exception("Decompression failed")
            val hex = idc.toString(16)
            val clean = inflated.removeSuffix(hex)
            val rev = clean.reversed()
            val padding = (4 - rev.length % 4) % 4
            val revPadded = rev + "=".repeat(padding)
            val json = String(Base64.decode(revPadded, Base64.DEFAULT), Charsets.UTF_8)
            Log.d(TAG, "✓ Final JSON: $json")
            return json.parseAs<UrlPayload>()
        } catch (e: Exception) {
            Log.e(TAG, "dataAPI FAILED", e)
            throw e
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val packedScript = document.selectFirst("script:containsData(h,u,n,t,e,r)")!!.data()
        val unpackedScript = decodeHunter(packedScript)

        val parametersRegex = Regex("""sml = '([^']+)';\n?.*var sme = '([^']+)'""")
        val (sml, sme) = parametersRegex.find(unpackedScript)?.destructured
            ?: error("Failed to extract parameters from script.")

        val chapterInfoRegex = Regex("""const idc = (\d+)""")
        val (chapterId) = chapterInfoRegex.find(packedScript)?.destructured
            ?: error("Failed to extract chapter ID.")

        val chapterPath = document.baseUri().toHttpUrl().encodedPath
        val chapterSourceUrl = "$wwwBaseUrl$chapterPath"

        Log.d(TAG, "Chapter ID: $chapterId | sml: $sml | sme: $sme")

        val mediaType = "application/json; charset=UTF-8".toMediaType()
        val requestBody = """{"a":"$sme","b":"$sml"}"""
        val apiUrl = "https://bqj.scan-manga.com/lel/$chapterId.json"

        Log.d(TAG, "POST $apiUrl | source: $chapterSourceUrl | body: $requestBody")

        val pageListRequest = POST(
            apiUrl,
            headers.newBuilder().set("Accept", "*/*").set("Origin", wwwBaseUrl)
                .set("Referer", "$wwwBaseUrl/").add("source", chapterSourceUrl).add("Token", "yf")
                .add("Sec-Fetch-Dest", "empty").add("Sec-Fetch-Mode", "cors")
                .add("Sec-Fetch-Site", "same-site").build(),
            requestBody.toRequestBody(mediaType),
        )

        val lelResponse =
            client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build().newCall(pageListRequest)
                .execute().use { resp ->
                    val responseBody = resp.body.string()
                    Log.d(TAG, "Réponse (HTTP ${resp.code}): ${responseBody.take(200)}")

                    if (responseBody.contains("\"error\"")) {
                        val errorCode =
                            Regex(""""error"\s*:\s*"?(\w+)"?""").find(responseBody)?.groupValues?.get(
                                1,
                            ) ?: "inconnu"
                        error("Erreur serveur $errorCode")
                    }

                    if (!resp.isSuccessful) error("HTTP ${resp.code}")

                    dataAPI(responseBody, chapterId.toInt())
                }

        return lelResponse.generateImageUrls().map { Page(it.first, imageUrl = it.second) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val imgHeaders = headers.newBuilder().add("Origin", wwwBaseUrl).build()
        return GET(page.imageUrl!!, imgHeaders)
    }
}
