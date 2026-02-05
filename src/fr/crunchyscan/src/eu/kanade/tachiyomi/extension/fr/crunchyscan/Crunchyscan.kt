package eu.kanade.tachiyomi.extension.fr.crunchyscan

import android.util.Base64
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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
                url = link?.attr("href")?.let { raw ->
                    when {
                        raw.startsWith("http://") || raw.startsWith("https://") ->
                            raw.removePrefix(baseUrl)

                        raw.startsWith("//") ->
                            raw.removePrefix("//")

                        else ->
                            raw
                    }
                } ?: ""
                name = link?.text()?.trim() ?: "Chapitre"

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
    // PAGE LIST — DÉCRYPTAGE des images
    // ============================================

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body?.string() ?: return emptyList()
        val document = Jsoup.parse(html)

        // Récupérer le data-meta depuis l'élément #a-ads-id
        val dataMeta = document.selectFirst("#a-ads-id")?.attr("data-meta")
            ?: return emptyList()

        // Décrypter les URLs d'images
        val imageUrls = decryptImageUrls(dataMeta)

        return imageUrls.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException("Not used")
    }

    override fun imageRequest(page: Page): Request {
        var imageUrl = page.imageUrl ?: ""

        if (imageUrl.contains("&amp;")) {
            imageUrl = imageUrl.replace("&amp;", "&")
        }

        val requestHeaders = headers.newBuilder()
            .set("secs-ch-aa", "v=\"118\"")
            .removeAll("Referer")
            .removeAll("Accept")
            .build()

        return GET(imageUrl, requestHeaders)
    }

    // ============================================
    // DÉCRYPTAGE — Algorithme Vigenère + XOR + AES-CBC
    // ============================================
    // Algorithme confirmé sur plusieurs chapitres :
    //   1. Hex → String
    //   2. Vigenère decrypt (mario)  avec KEY_MARIO_1
    //   3. XOR decrypt     (tetris) avec KEY_TETRIS_1
    //   4. XOR decrypt     (tetris) avec KEY_TETRIS_2
    //   5. Vigenère decrypt (mario)  avec KEY_MARIO_2
    //   6. Convertir la string en bytes (charCode & 0xFF)
    //   7. AES-CBC : IV = 16 premiers bytes | reste = ciphertext
    //   8. Split par ";" → liste d'URLs

    private fun decryptImageUrls(dataMeta: String): List<String> {
        // Étape 1 : Hex → String
        val encryptedText = hexToString(dataMeta)

        // Étape 2-5 : Chaîne de décryptage Vigenère/XOR
        var temp = decryptVigenere(encryptedText, KEY_MARIO_1)
        temp = decryptXor(temp, KEY_TETRIS_1)
        temp = decryptXor(temp, KEY_TETRIS_2)
        val wasmOutput = decryptVigenere(temp, KEY_MARIO_2)

        // Étape 6 : String → Bytes
        val combinedBytes = ByteArray(wasmOutput.length) { (wasmOutput[it].code and 0xFF).toByte() }

        // Étape 7 : AES-CBC decrypt
        val decryptedBytes = decryptAesCbc(combinedBytes)
        val finalString = String(decryptedBytes, StandardCharsets.UTF_8)

        // Étape 8 : Split et filtrer pour garder seulement les bonnes URLs
        return finalString
            .split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.contains("/get-image", ignoreCase = true) } // FILTRER les URLs get-image
            .map { url ->
                when {
                    url.startsWith("http") -> url
                    url.startsWith("/") -> "$baseUrl$url"
                    else -> url
                }
            }
    }

    // Vigenère decrypt : (charCode - keyCharCode + 256) % 256
    private fun decryptVigenere(input: String, key: String): String {
        val sb = StringBuilder(input.length)
        val keyLen = key.length
        for (i in input.indices) {
            val decrypted = (input[i].code - key[i % keyLen].code + 0x100) % 0x100
            sb.append(decrypted.toChar())
        }
        return sb.toString()
    }

    // XOR decrypt : charCode xor keyCharCode
    private fun decryptXor(input: String, key: String): String {
        val sb = StringBuilder(input.length)
        val keyLen = key.length
        for (i in input.indices) {
            sb.append((input[i].code xor key[i % keyLen].code).toChar())
        }
        return sb.toString()
    }

    // Hex vers String : chaque paire de caractères hex → un char
    private fun hexToString(hex: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < hex.length - 1) {
            sb.append(hex.substring(i, i + 2).toInt(16).toChar())
            i += 2
        }
        return sb.toString()
    }

    // AES-CBC decrypt : IV = 16 premiers bytes | reste = ciphertext | PKCS5 padding
    private fun decryptAesCbc(encryptedBytes: ByteArray): ByteArray {
        val iv = encryptedBytes.copyOfRange(0, 16)
        val cipherText = encryptedBytes.copyOfRange(16, encryptedBytes.size)
        val keyBytes = Base64.decode(AES_KEY_BASE64, Base64.DEFAULT)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))

        return cipher.doFinal(cipherText)
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
        // Clés de décryptage extraites du reader.js / crypto.wasm
        private const val KEY_MARIO_1 = "aYdjAA9bFlWzoO2ZDjvw51DUhIy9"
        private const val KEY_TETRIS_1 = "K0Q6YqGsxCtCLPLG"
        private const val KEY_TETRIS_2 = "3jBYzWHkXj1Gke3VcS6pLDLz"
        private const val KEY_MARIO_2 = "L3EtGmOqE746udz0k8P74tUq"
        private const val AES_KEY_BASE64 = "Tr3eGFZNXPTo8mTEBhu1R+mLy/MCcgG8+7ikXbMVaEQ="

        private const val DAY_IN_MILLIS = 86400000L
        private const val MONTH_IN_MILLIS = 30 * DAY_IN_MILLIS
        private const val YEAR_IN_MILLIS = 365 * DAY_IN_MILLIS
    }
}
