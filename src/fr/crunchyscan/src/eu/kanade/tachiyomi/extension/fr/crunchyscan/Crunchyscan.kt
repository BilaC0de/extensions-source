package eu.kanade.tachiyomi.extension.fr.crunchyscan

import android.content.ComponentName
import android.content.Intent
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.applicationContext
import keiyoushi.utils.parseAs
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Source
abstract class Crunchyscan : HttpSource() {

    // name, lang, baseUrl, id injectés automatiquement par KSP via le bloc source {}

    override val supportsLatest = true

    override val client = network.client

    private var csrfToken: String = ""

    private fun fetchCsrfToken(): String {
        if (csrfToken.isNotEmpty()) return csrfToken
        val document = client.newCall(GET(baseUrl, headers)).execute().asJsoup()
        csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        return csrfToken
    }

    private fun headersWithCsrf(): Headers = headers.newBuilder().add("X-CSRF-TOKEN", fetchCsrfToken()).add("X-Requested-With", "XMLHttpRequest").build()

    // ============================================
    // POPULAR MANGA
    // ============================================

    override fun popularMangaRequest(page: Int): Request {
        val formBody =
            FormBody.Builder().add("affichage", "grid").add("team", "").add("artist", "").add("author", "").add("page", page.toString())
                .add("chapters[]", "0").add("chapters[]", "200").add("searchTerm", "").add("orderWith", "Vues").add("orderBy", "desc")
                .build()
        return POST("$baseUrl/api/manga/search/advance", headersWithCsrf(), formBody)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val apiResponse = response.parseAs<ApiResponse>()
        val mangaList = apiResponse.data.map { it.toSManga() }
        val hasNextPage = apiResponse.meta.currentPage < apiResponse.meta.lastPage
        return MangasPage(mangaList, hasNextPage)
    }

    // ============================================
    // LATEST UPDATES
    // ============================================

    override fun latestUpdatesRequest(page: Int): Request {
        val formBody =
            FormBody.Builder().add("affichage", "grid").add("team", "").add("artist", "").add("author", "").add("page", page.toString())
                .add("chapters[]", "0").add("chapters[]", "200").add("searchTerm", "").add("orderWith", "Récent").add("orderBy", "desc")
                .build()
        return POST("$baseUrl/api/manga/search/advance", headersWithCsrf(), formBody)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================================
    // SEARCH
    // ============================================

    override fun getFilterList() = FilterList(
        GenreFilter(),
        YearFilter(),
        StatusFilter(),
        TypeFilter(),
        ChapterMinFilter(),
        ChapterMaxFilter(),
    )

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val formBody =
            FormBody.Builder().add("affichage", "grid").add("team", "").add("artist", "").add("author", "").add("page", page.toString())
                .add("searchTerm", query).add("orderWith", "Vues").add("orderBy", "desc")

        var chaptersMin = "0"
        var chaptersMax = "200"

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> filter.state.forEach { genre ->
                    when (genre.state) {
                        Filter.TriState.STATE_INCLUDE -> formBody.add("genres[]", genre.name)
                        Filter.TriState.STATE_EXCLUDE -> formBody.add("exclude_genres[]", genre.name)
                        else -> {}
                    }
                }

                is YearFilter -> filter.state.filter { it.state }.forEach { formBody.add("year[]", it.name) }
                is StatusFilter -> filter.state.filter { it.state }.forEach { formBody.add("status[]", it.name) }
                is TypeFilter -> filter.state.filter { it.state }.forEach { formBody.add("types[]", it.name) }
                is ChapterMinFilter -> chaptersMin = filter.state
                is ChapterMaxFilter -> chaptersMax = filter.state
                else -> {}
            }
        }

        formBody.add("chapters[]", chaptersMin).add("chapters[]", chaptersMax)

        return POST("$baseUrl/api/manga/search/advance", headersWithCsrf(), formBody.build())
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================================
    // MANGA DETAILS
    // ============================================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h2.text-3xl, h1.text-2xl")!!.text()
            description = document.selectFirst("div.mt-12 > p")?.text() ?: document.selectFirst("p.whitespace-pre-line")?.text()
            thumbnail_url = document.selectFirst("img.manga_cover")?.attr("abs:src")
            author = document.select("a[href*='/catalog/author/']").joinToString { it.text() }
            genre = document.select("a[href*='/catalog/genre/']").joinToString { it.text() }
            val statusText = document.select("h3:contains(Status)").first()?.nextElementSibling()?.text()?.lowercase()
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

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("div#ChapterWrap > div.chapterBox").mapNotNull { element ->
            val link = element.selectFirst("a.chapter-link[href*='/read/']") ?: return@mapNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.text().ifEmpty { "Chapitre" }
                val dateElement = element.select("i.fa-timer").first()?.parent()?.nextElementSibling()
                date_upload = parseChapterDate(dateElement?.text())
            }
        }
    }

    private fun parseChapterDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L
        val normalized = dateStr.lowercase().trim()
        val value = normalized.filter { it.isDigit() }.toLongOrNull() ?: return 0L
        val now = System.currentTimeMillis()
        return when {
            normalized.contains("min") -> now - (value * 60 * 1000)
            normalized.contains("heure") || normalized.contains("h") -> now - (value * 60 * 60 * 1000)
            normalized.contains("jour") -> now - (value * DAY_IN_MILLIS)
            normalized.contains("mois") -> now - (value * MONTH_IN_MILLIS)
            normalized.contains("année") || normalized.contains("an") -> now - (value * YEAR_IN_MILLIS)
            else -> 0L
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    // ============================================
    // PAGE LIST
    // ============================================

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString().substringBefore("?")
        val document = response.asJsoup()

        val hasTurnstile = document.selectFirst(".cf-turnstile") != null
        val dataMeta = document.selectFirst("#a-ads-id")?.attr("data-meta") ?: ""

        if (hasTurnstile || dataMeta.isEmpty()) {
            if (isDownloadContext()) {
                throw Exception("Protégé par Cloudflare, non téléchargeable")
            }

            val opened = tryOpenWebView(chapterUrl)
            throw Exception(
                if (opened) {
                    "Chapitre protégé par Cloudflare : ouverture du WebView, lisez-y le chapitre puis fermez-la."
                } else {
                    "Chapitre protégé par Cloudflare. Ouvrez-le manuellement via \"Ouvrir dans le WebView\" (menu ⋮)."
                },
            )
        }

        val imageUrls = decryptImageUrls(dataMeta)
        return imageUrls.mapIndexed { index, _ ->
            Page(index, url = "$chapterUrl?imgIndex=$index")
        }
    }

    private fun isDownloadContext(): Boolean = Exception().stackTrace.any {
        it.className.contains("eu.kanade.tachiyomi.data.download", ignoreCase = true) || it.className.contains(
            "Downloader",
            ignoreCase = true,
        )
    }

    private fun tryOpenWebView(url: String): Boolean = try {
        val context = applicationContext
        val intent = Intent().apply {
            component = ComponentName(context, "eu.kanade.tachiyomi.ui.webview.WebViewActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("url_key", url)
            putExtra("source_key", id)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    override fun imageUrlRequest(page: Page): Request {
        val chapterUrl = page.url.substringBefore("?imgIndex")
        val imgIndex = page.url.substringAfter("?imgIndex=")
        return GET("$chapterUrl?imgIndex=$imgIndex", headers).newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build()
    }

    override fun imageUrlParse(response: Response): String {
        val targetIndex = response.request.url.queryParameter("imgIndex")?.toIntOrNull() ?: 0
        val document = response.asJsoup()
        val dataMeta = document.selectFirst("#a-ads-id")?.attr("data-meta") ?: return ""
        val imageUrls = decryptImageUrls(dataMeta)
        val url = imageUrls.getOrNull(targetIndex) ?: return ""

        return if (url.contains("/get-image")) "$url&cid=$FINGERPRINT_DEFAULT" else url
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl.orEmpty().replace("&amp;", "&")

        val referer = if (imageUrl.contains("/get-image") && page.url.isNotEmpty()) {
            page.url.substringBefore("?imgIndex")
        } else {
            baseUrl
        }

        val requestHeaders = headers.newBuilder().set("secs-ch-aa", "v=\"118\"").set("Referer", referer).removeAll("Accept").build()

        return GET(imageUrl, requestHeaders)
    }

    // ============================================
    // DÉCRYPTAGE
    // ============================================

    private fun decryptImageUrls(dataMeta: String): List<String> {
        val encryptedText = hexToString(dataMeta)
        var temp = decryptVigenere(encryptedText, KEY_MARIO_1)
        temp = decryptXor(temp, KEY_TETRIS_1)
        temp = decryptXor(temp, KEY_TETRIS_2)
        val wasmOutput = decryptVigenere(temp, KEY_MARIO_2)
        val combinedBytes = ByteArray(wasmOutput.length) { (wasmOutput[it].code and 0xFF).toByte() }
        val decryptedBytes = decryptAesCbc(combinedBytes)
        val finalString = String(decryptedBytes, StandardCharsets.UTF_8)

        return finalString.split(";").map { it.trim() }.filter { it.isNotBlank() }.filterNot { it.contains("get-lmage") }.map { url ->
            when {
                url.startsWith("http") -> url
                url.startsWith("/") -> "$baseUrl$url"
                else -> url
            }
        }
    }

    private fun decryptVigenere(input: String, key: String): String {
        val sb = StringBuilder(input.length)
        for (i in input.indices) {
            sb.append(((input[i].code - key[i % key.length].code + 0x100) % 0x100).toChar())
        }
        return sb.toString()
    }

    private fun decryptXor(input: String, key: String): String {
        val sb = StringBuilder(input.length)
        for (i in input.indices) {
            sb.append((input[i].code xor key[i % key.length].code).toChar())
        }
        return sb.toString()
    }

    private fun hexToString(hex: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < hex.length - 1) {
            sb.append(hex.substring(i, i + 2).toInt(16).toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun decryptAesCbc(encryptedBytes: ByteArray): ByteArray {
        val iv = encryptedBytes.copyOfRange(0, 16)
        val cipherText = encryptedBytes.copyOfRange(16, encryptedBytes.size)
        val keyBytes = Base64.decode(AES_KEY_BASE64, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(cipherText)
    }

    companion object {
        private const val KEY_MARIO_1 = "aYdjAA9bFlWzoO2ZDjvw51DUhIy9"
        private const val KEY_TETRIS_1 = "K0Q6YqGsxCtCLPLG"
        private const val KEY_TETRIS_2 = "3jBYzWHkXj1Gke3VcS6pLDLz"
        private const val KEY_MARIO_2 = "L3EtGmOqE746udz0k8P74tUq"
        private const val AES_KEY_BASE64 = "Tr3eGFZNXPTo8mTEBhu1R+mLy/MCcgG8+7ikXbMVaEQ="
        private const val FINGERPRINT_DEFAULT = "0000000000000000000000000000000000000000000000000000000000000000"

        private const val DAY_IN_MILLIS = 86400000L
        private const val MONTH_IN_MILLIS = 30 * DAY_IN_MILLIS
        private const val YEAR_IN_MILLIS = 365 * DAY_IN_MILLIS
    }
}
