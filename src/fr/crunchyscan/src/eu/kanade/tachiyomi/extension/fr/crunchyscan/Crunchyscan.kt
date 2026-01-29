package eu.kanade.tachiyomi.extension.fr.crunchyscan

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
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
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Crunchyscan : HttpSource() {

    override val name = "CrunchyScan"
    override val baseUrl = "https://crunchyscan.fr"
    override val lang = "fr"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()
    private var csrfToken: String = ""

    private val application: Application = Injekt.get()
    private val cookieManager = CookieManager.getInstance()

    // ============================================
    // CSRF TOKEN & COOKIES
    // ============================================

    private fun fetchCsrfToken(): String {
        if (csrfToken.isNotEmpty()) {
            return csrfToken
        }

        val response = client.newCall(GET(baseUrl, headers)).execute()
        val html = response.body.string()

        syncCookiesToWebView(baseUrl)

        response.close()

        val document = Jsoup.parse(html)
        val metaTag = document.selectFirst("meta[name=csrf-token]")
        csrfToken = metaTag?.attr("content") ?: ""

        return csrfToken
    }

    private fun headersWithCsrf(): Headers {
        val token = fetchCsrfToken()
        return headers.newBuilder()
            .add("X-CSRF-TOKEN", token)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    private fun syncCookiesToWebView(url: String) {
        cookieManager.setAcceptCookie(true)

        val httpUrl = url.toHttpUrlOrNull() ?: return
        val cookies = client.cookieJar.loadForRequest(httpUrl)

        cookies.forEach { cookie ->
            val cookieString = "${cookie.name}=${cookie.value}; domain=${cookie.domain}; path=${cookie.path}"
            cookieManager.setCookie(url, cookieString)
        }

        cookieManager.flush()
    }

    // ============================================
    // POPULAR MANGA
    // ============================================

    override fun popularMangaRequest(page: Int): Request {
        val formBody = FormBody.Builder()
            .add("affichage", "grid")
            .add("team", "")
            .add("artist", "")
            .add("author", "")
            .add("page", page.toString())
            .add("chapters[]", "0")
            .add("chapters[]", "200")
            .add("searchTerm", "")
            .add("orderWith", "Vues")
            .add("orderBy", "desc")
            .build()

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
        val formBody = FormBody.Builder()
            .add("affichage", "grid")
            .add("team", "")
            .add("artist", "")
            .add("author", "")
            .add("page", page.toString())
            .add("chapters[]", "0")
            .add("chapters[]", "200")
            .add("searchTerm", "")
            .add("orderWith", "Récent")
            .add("orderBy", "desc")
            .build()

        return POST("$baseUrl/api/manga/search/advance", headersWithCsrf(), formBody)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // ============================================
    // SEARCH
    // ============================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val formBody = FormBody.Builder()
            .add("affichage", "grid")
            .add("team", "")
            .add("artist", "")
            .add("author", "")
            .add("page", page.toString())
            .add("chapters[]", "0")
            .add("chapters[]", "200")
            .add("searchTerm", query)
            .add("orderWith", "Vues")
            .add("orderBy", "desc")
            .build()

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
        val document = Jsoup.parse(response.body.string())

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

                val dateElement = element.select("i.fa-timer").first()?.parent()?.nextElementSibling()
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
    // PAGE LIST avec WebView
    // ============================================

    override fun pageListRequest(chapter: SChapter): Request {
        // Vérifier si l'URL est déjà complète ou relative
        val url = if (chapter.url.startsWith("http")) {
            chapter.url
        } else {
            baseUrl + chapter.url
        }
        return GET(url, headers)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString()

        // Log pour debug
        println("CrunchyScan - Chapter URL: $chapterUrl")

        syncCookiesToWebView(baseUrl)

        val latch = CountDownLatch(1)
        var imageUrls = emptyList<String>()

        Handler(Looper.getMainLooper()).post {
            val webView = WebView(application)

            try {
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = false
                    loadWithOverviewMode = false
                    userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"
                    // Activer le debug JavaScript pour voir les console.log
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                webView.addJavascriptInterface(
                    object : Any() {
                        @JavascriptInterface
                        fun passImages(jsonString: String) {
                            try {
                                println("CrunchyScan - Received JSON: $jsonString")
                                val urls = json.decodeFromString<List<String>>(jsonString)
                                println("CrunchyScan - Parsed ${urls.size} URLs")
                                imageUrls = urls.filter { url ->
                                    url.isNotBlank() &&
                                        !url.contains("get-image") &&
                                        (url.startsWith("http") || url.startsWith("blob:") || url.startsWith("data:") || url.startsWith("/"))
                                }
                                println("CrunchyScan - Filtered to ${imageUrls.size} URLs")
                            } catch (e: Exception) {
                                println("CrunchyScan - Error parsing images: ${e.message}")
                                e.printStackTrace()
                            } finally {
                                latch.countDown()
                            }
                        }
                    },
                    "Android",
                )

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        webView.evaluateJavascript(
                            """
                            (async function() {
                                try {
                                    let retries = 0;
                                    while (typeof allImg === 'undefined' && retries < 50) {
                                        await new Promise(r => setTimeout(r, 100));
                                        retries++;
                                    }

                                    if (typeof allImg === 'undefined') {
                                        Android.passImages('[]');
                                        return;
                                    }

                                    const processedUrls = await Promise.all(
                                        allImg.map(async (url) => {
                                            if (url.startsWith('blob:')) {
                                                try {
                                                    const response = await fetch(url);
                                                    const blob = await response.blob();
                                                    return new Promise((resolve) => {
                                                        const reader = new FileReader();
                                                        reader.onloadend = () => resolve(reader.result);
                                                        reader.readAsDataURL(blob);
                                                    });
                                                } catch (e) {
                                                    return url;
                                                }
                                            }
                                            return url;
                                        })
                                    );

                                    Android.passImages(JSON.stringify(processedUrls));
                                } catch (e) {
                                    Android.passImages('[]');
                                }
                            })();
                            """.trimIndent(),
                            null,
                        )
                    }

                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        super.onReceivedError(view, errorCode, description, failingUrl)
                        latch.countDown()
                    }
                }

                webView.loadUrl(chapterUrl)
            } catch (e: Exception) {
                e.printStackTrace()
                latch.countDown()
            }
        }

        val success = latch.await(30, TimeUnit.SECONDS)

        if (!success || imageUrls.isEmpty()) {
            throw Exception("Timeout ou aucune image trouvée")
        }

        return imageUrls.mapIndexed { index, url ->
            val finalUrl = when {
                url.startsWith("http") -> url
                url.startsWith("data:image") -> url
                url.startsWith("/") -> baseUrl + url
                else -> url
            }
            Page(index, imageUrl = finalUrl)
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used")
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: ""

        if (imageUrl.startsWith("data:image")) {
            return GET(imageUrl, headers)
        }

        val cleanUrl = imageUrl.replace("&amp;", "&")
        val cookieString = cookieManager.getCookie(baseUrl) ?: ""

        val requestHeaders = headers.newBuilder()
            .add("Referer", "$baseUrl/")
            .add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .apply {
                if (cookieString.isNotEmpty()) {
                    add("Cookie", cookieString)
                }
                if (csrfToken.isNotEmpty()) {
                    add("X-CSRF-TOKEN", csrfToken)
                }
            }
            .build()

        return GET(cleanUrl, requestHeaders)
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
