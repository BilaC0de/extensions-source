package eu.kanade.tachiyomi.extension.fr.crunchyscan

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy

class Crunchyscan : HttpSource() {

    override val name = "CrunchyScan"
    override val baseUrl = "https://crunchyscan.fr"
    override val lang = "fr"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    // Variable pour stocker le token CSRF
    private var csrfToken: String = ""

    // Fonction pour récupérer le token CSRF
    private fun fetchCsrfToken(): String {
        if (csrfToken.isNotEmpty()) {
            return csrfToken
        }

        val response = client.newCall(GET(baseUrl, headers)).execute()
        val html = response.body.string()
        response.close()

        // Extraire le token depuis la balise meta
        val document = Jsoup.parse(html)
        val metaTag = document.selectFirst("meta[name=csrf-token]")
        csrfToken = metaTag?.attr("content") ?: ""

        return csrfToken
    }

    // Créer les headers avec le token CSRF
    private fun headersWithCsrf(): Headers {
        val token = fetchCsrfToken()
        return headers.newBuilder().add("X-CSRF-TOKEN", token)
            .add("X-Requested-With", "XMLHttpRequest").build()
    }

    // ============================================
    // POPULAR MANGA
    // ============================================

    override fun popularMangaRequest(page: Int): Request {
        val formBody = FormBody.Builder().add("affichage", "grid").add("team", "").add("artist", "")
            .add("author", "").add("page", page.toString()).add("chapters[]", "0")
            .add("chapters[]", "200").add("searchTerm", "").add("orderWith", "Vues")
            .add("orderBy", "desc").build()

        return POST("$baseUrl/api/manga/search/advance", headersWithCsrf(), formBody)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val responseBody = response.body.string()
        val apiResponse = json.decodeFromString<ApiResponse>(responseBody)

        val mangaList = apiResponse.data.map { item ->
            SManga.create().apply {
                title = item.name
                url = "/lecture-en-ligne/${item.slug}"
                thumbnail_url = item.cover_url
                initialized = false
            }
        }

        val hasNextPage = apiResponse.meta.current_page < apiResponse.meta.last_page
        return MangasPage(mangaList, hasNextPage)
    }

    // ============================================
    // LATEST UPDATES
    // ============================================

    override fun latestUpdatesRequest(page: Int): Request {
        val formBody = FormBody.Builder().add("affichage", "grid").add("team", "").add("artist", "")
            .add("author", "").add("page", page.toString()).add("chapters[]", "0")
            .add("chapters[]", "200").add("searchTerm", "").add("orderWith", "Récent")
            .add("orderBy", "desc").build()

        return POST("$baseUrl/api/manga/search/advance", headersWithCsrf(), formBody)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // ============================================
    // SEARCH
    // ============================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val formBody = FormBody.Builder().add("affichage", "grid").add("team", "").add("artist", "")
            .add("author", "").add("page", page.toString()).add("chapters[]", "0")
            .add("chapters[]", "200").add("searchTerm", query).add("orderWith", "Vues")
            .add("orderBy", "desc").build()

        return POST("$baseUrl/api/manga/search/advance", headersWithCsrf(), formBody)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // ============================================
    // MANGA DETAILS
    // ============================================

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body?.string() ?: "")

        return SManga.create().apply {
            title = document.selectFirst("h2.text-3xl, h1.text-2xl")?.text() ?: ""

            description = document.selectFirst("div.mt-12 > p")?.text()
                ?: document.selectFirst("p.whitespace-pre-line")?.text()

            thumbnail_url = document.selectFirst("img.manga_cover")?.attr("abs:src")

            author = document.select("a[href*='/catalog/author/']").joinToString(", ") { it.text() }

            genre = document.select("a[href*='/catalog/genre/']").joinToString(", ") { it.text() }

            val statusElement = document.select("h3:contains(Status)").first()?.nextElementSibling()
            val statusText = statusElement?.text()?.lowercase()

            status = when {
                statusText?.contains("en cours") == true -> SManga.ONGOING
                statusText?.contains("terminé") == true -> SManga.COMPLETED
                statusText?.contains("pause") == true -> SManga.ON_HIATUS
                statusText?.contains("abandonné") == true -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }

            initialized = true
        }
    }

    // ============================================
    // CHAPTER LIST
    // ============================================

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())

        return document.select("div#ChapterWrap > div.chapterBox").map { element ->
            SChapter.create().apply {
                val link = element.selectFirst("a.chapter-link[href*='/read/']")
                url = link?.attr("href") ?: ""
                name = link?.text()?.trim() ?: "Chapitre"

                // Chercher le div qui contient l'icône timer, puis prendre le <p> suivant
                val dateElement =
                    element.select("i.fa-timer").first()?.parent()?.nextElementSibling()
                val dateText = dateElement?.text()
                date_upload = parseChapterDate(dateText)
            }
        }
    }

    private fun parseChapterDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L

        return try {
            val now = System.currentTimeMillis()
            val normalized = dateStr.lowercase().trim()

            when {
                normalized.contains("min") -> {
                    val minutes = normalized.filter { it.isDigit() }.toLongOrNull() ?: 0
                    now - (minutes * 60 * 1000)
                }

                normalized.contains("heure") || normalized.contains("h") -> {
                    val hours = normalized.filter { it.isDigit() }.toLongOrNull() ?: 0
                    now - (hours * 60 * 60 * 1000)
                }

                normalized.contains("jour") -> {
                    val days = normalized.filter { it.isDigit() }.toLongOrNull() ?: 0
                    now - (days * DAY_IN_MILLIS)
                }

                normalized.contains("mois") -> {
                    val months = normalized.filter { it.isDigit() }.toLongOrNull() ?: 0
                    now - (months * MONTH_IN_MILLIS)
                }

                normalized.contains("année") || normalized.contains("an") -> {
                    val years = normalized.filter { it.isDigit() }.toLongOrNull() ?: 0
                    now - (years * YEAR_IN_MILLIS)
                }

                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    // ============================================
    // PAGE LIST
    // ============================================

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body?.string() ?: return emptyList()
        val document = Jsoup.parse(html)

        // Chercher la variable JavaScript 'allImg' qui contient toutes les URLs
        document.select("script:not([src])").forEach { script ->
            val scriptContent = script.html() ?: return@forEach

            // Pattern pour : var allImg = [...] ou allImg = [...]
            val allimgPattern = Regex("""(?:var|let|const)?\s*allImg\s*=\s*(\[[\s\S]*?\]);""")
            val match = allimgPattern.find(scriptContent)

            if (match != null && match.groupValues.size > 1) {
                val jsonArrayString = match.groupValues[1]
                val imageUrls = parseImageArray(jsonArrayString)

                if (imageUrls.isNotEmpty()) {
                    return imageUrls.mapIndexed { index, url ->
                        val fullUrl = if (url.startsWith("http")) {
                            url
                        } else {
                            baseUrl + url
                        }
                        Page(index, imageUrl = fullUrl)
                    }
                }
            }
        }

        // Si aucune image trouvée, retourner une liste vide
        return emptyList()
    }

    // Fonction pour parser le tableau JSON des URLs d'images
    private fun parseImageArray(jsonArrayString: String): List<String> {
        val urls = mutableListOf<String>()

        try {
            // Parser avec kotlinx.serialization
            val jsonArray = json.parseToJsonElement(jsonArrayString)
            if (jsonArray is JsonArray) {
                jsonArray.forEach { element ->
                    val url = element.toString().trim('"')
                    if (url.isNotEmpty() && !url.equals("null", ignoreCase = true)) {
                        urls.add(url)
                    }
                }
                return urls
            }
        } catch (e: Exception) {
            // Parsing manuel en cas d'échec
            val urlPattern = Regex("""["']([^"']+)["']""")
            urlPattern.findAll(jsonArrayString).forEach { match ->
                val url = match.groupValues[1]
                if (url.startsWith("http") || url.startsWith("/upload")) {
                    urls.add(url)
                }
            }
        }

        return urls
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used")
    }

    // Override imageRequest pour gérer les URLs signées et ajouter les headers
    // Override imageRequest pour gérer les URLs signées et ajouter les headers
    override fun imageRequest(page: Page): Request {
        var imageUrl = page.imageUrl ?: "" // Correction: gérer le cas null

        // Corriger &amp; en & dans les URLs
        if (imageUrl.contains("&amp;")) {
            imageUrl = imageUrl.replace("&amp;", "&")
        }

        // Headers appropriés pour les requêtes d'images
        val requestHeaders = headers.newBuilder()
            .add("Referer", "$baseUrl/")
            .add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()

        return GET(imageUrl, requestHeaders)
    }

    // ============================================
    // DATA CLASSES
    // ============================================

    @Serializable
    data class ApiResponse(
        val data: List<MangaItem>,
        val meta: Meta,
        val links: Links,
    )

    @Serializable
    data class MangaItem(
        val name: String,
        val slug: String,
        val cover_url: String,
    )

    @Serializable
    data class Meta(
        val current_page: Int,
        val last_page: Int,
    )

    @Serializable
    data class Links(
        val next: String? = null,
    )

    companion object {
        private const val DAY_IN_MILLIS = 86400000L
        private const val MONTH_IN_MILLIS = 30 * DAY_IN_MILLIS
        private const val YEAR_IN_MILLIS = 365 * DAY_IN_MILLIS
    }
}
