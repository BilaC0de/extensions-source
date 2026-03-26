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
        // 2024-2025 — Chrome récent sur appareils modernes
        "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.102 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; CPH2609) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.100 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; 2312DRA50G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.6613.127 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; Pixel 7a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.86 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.260 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; SM-A556B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.69 Mobile Safari/537.36",
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
    // ─────────────────────────────────────────────────────────────

    private fun fetchCoverViaBqj(mangaUrl: String): String? {
        return try {
            val segment = mangaUrl.trimStart('/').substringBefore('/')
            // "10834-28941" → on coupe au tiret → on prend "10834"
            val mangaId = segment.substringBefore('-').toLongOrNull() ?: run {
                Log.w(TAG, "Impossible d'extraire l'ID depuis : $segment")
                return null
            }

            val jsonUrl = "https://bqj.scan-manga.com/popmanga_$mangaId.json"
            val raw = client.newCall(GET(jsonUrl, headers)).execute().use { it.body.string() }
            val array = org.json.JSONArray(raw)

            val filename = array.optString(5, "")
            if (filename.isNotBlank()) {
                "$baseImageUrl/$filename"
            } else {
                Log.w(TAG, "Nom d'image vide pour ID $mangaId")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchCoverViaBqj failed pour $mangaUrl", e)
            null
        }
    }

    private fun loadCovers(mangas: List<SManga>) {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
        mangas.forEachIndexed { index, manga ->
            executor.submit {
                try {
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
    // POPULAR
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
        loadCovers(mangas)
        return MangasPage(mangas, false)
    }

    // ─────────────────────────────────────────────────────────────
    // LATEST UPDATES
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
        loadCovers(mangas)
        return MangasPage(mangas, false)
    }

    // ─────────────────────────────────────────────────────────────
    // SEARCH
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
    // MANGA DETAILS
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
    // CHAPTER LIST
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
    // PAGE LIST — NOUVEAU SYSTÈME DE DÉCHIFFREMENT (m,w,b,u,v,h)
    // Le site a remplacé l'ancien système (h,u,n,t,e,r) par un
    // nouveau format. Cette fonction gère le nouveau format.
    // ─────────────────────────────────────────────────────────────

    private fun decodeNewStyle(obfuscatedJs: String): String {
        // Accepte n'importe quel nom de variable : (m,w,b,u,v,h) ou (l,y,d,m,e,r,z) etc.
        // Le site change les noms régulièrement mais la logique reste identique :
        // eval(function(X,X,X,X,X,X,...){ ... }("données",nb,"alphabet",soustraction,base,...))
        val regex = Regex(
            """eval\(function\((?:[a-z],){5}[a-z](?:,[a-z])?\)\{[\s\S]*?\}\("([^"]+)",\d+,"([^"]+)",(\d+),(\d+),\d+(?:,\d+)?\)\)""",
        )
        val match = regex.find(obfuscatedJs) ?: error("Aucun script obfusqué trouvé")

        val encoded = match.groupValues[1] // les données chiffrées
        val alphabetStr = match.groupValues[2] // l'alphabet/masque
        val subtractVal = match.groupValues[3].toLong() // valeur à soustraire
        val base = match.groupValues[4].toInt() // base ET index du séparateur

        // Le séparateur est le caractère à la position [base] dans l'alphabet
        val delimiter = alphabetStr[base]

        return buildString {
            for (token in encoded.split(delimiter)) {
                if (token.isEmpty()) continue
                var s = token
                for (j in alphabetStr.indices) {
                    s = s.replace(alphabetStr[j].toString(), j.toString())
                }
                val number = s.toLongOrNull(base) ?: continue
                append((number - subtractVal).toChar())
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

        // idc est maintenant visible en clair dans la page
        // ex : "const idc = 521690;"
        val chapterId = document.select("script").mapNotNull { script ->
            Regex("""const idc\s*=\s*(\d+)""").find(script.data())?.groupValues?.get(1)
        }.firstOrNull() ?: error("Impossible de trouver l'ID du chapitre (idc)")

        // sml et sme sont cachés dans les scripts chiffrés (m,w,b,u,v,h)
        val parametersRegex = Regex("""sml\s*=\s*'([^']+)'[\s\S]*?var sme\s*=\s*'([^']+)'""")
        var sml: String? = null
        var sme: String? = null

        // Cherche dans tous les scripts qui contiennent un eval obfusqué
        // On cherche "eval(function(" au lieu des noms de variables qui changent à chaque fois
        for (script in document.select("script").toList()
            .filter { "eval(function(" in it.data() }) {
            try {
                val decoded = decodeNewStyle(script.data())
                val match = parametersRegex.find(decoded) ?: continue
                sml = match.groupValues[1]
                sme = match.groupValues[2]
                Log.d(TAG, "✓ sml et sme trouvés dans le script déchiffré")
                break
            } catch (e: Exception) {
                Log.w(TAG, "Échec décodage d'un script, essai du suivant...", e)
            }
        }

        if (sml == null || sme == null) {
            error("Impossible d'extraire sml/sme — le site a peut-être encore changé son format")
        }

        val chapterPath = response.request.url.encodedPath
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
