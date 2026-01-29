package eu.kanade.tachiyomi.extension.fr.rimuscans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

class RimuScans : HttpSource() {

    override val name = "RimuScans"
    override val baseUrl = "https://rimuscans.com"
    override val lang = "fr"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override val client: OkHttpClient =
        network.cloudflareClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    override fun headersBuilder() =
        super.headersBuilder()
            .add("Referer", baseUrl)
            .add("User-Agent", "Mozilla/5.0")

    /* =========================
       Popular / Latest / Search
       ========================= */

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/api/manga?page=$page&limit=24&sortBy=views", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/api/manga?page=$page&limit=24&sortBy=latest", headers)

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = popularMangaRequest(page)

    override fun popularMangaParse(response: Response): MangasPage {
        val result =
            json.decodeFromString<MangaListResponse>(
                response.body.string(),
            )

        val mangas = result.mangas.map { it.toSManga() }
        return MangasPage(mangas, mangas.size >= 24)
    }

    override fun latestUpdatesParse(response: Response): MangasPage =
        popularMangaParse(response)

    override fun searchMangaParse(response: Response): MangasPage =
        popularMangaParse(response)

    /* =========================
       Manga details
       ========================= */

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = org.jsoup.Jsoup.parse(response.body.string())

        return SManga.create().apply {
            title =
                document.title()
                    .replace(" - Rimu Scans", "")
                    .replace(" - RimuScans", "")
                    .trim()

            description =
                document
                    .selectFirst("meta[name=description]")
                    ?.attr("content")
                    ?: ""

            initialized = true
        }
    }

    /* =========================
       Chapter list
       ========================= */

    override fun chapterListRequest(manga: SManga): Request =
        GET("$baseUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val slug = response.request.url.pathSegments.last()

        return try {
            val apiResponse =
                client.newCall(
                    GET("$baseUrl/api/manga?slug=$slug", headers),
                ).execute()

            if (apiResponse.code == 200) {
                val result =
                    json.decodeFromString<MangaDetailResponse>(
                        apiResponse.body.string(),
                    )

                result.manga.chapters
                    .filter {
                        it.status == "PUBLISHED" &&
                            it.type == "NORMAL"
                    }
                    .map { chapter ->
                        SChapter.create().apply {
                            name = chapter.title
                            chapter_number = chapter.number.toFloat()
                            url =
                                "/api/manga?slug=$slug&chapter=${chapter.number}"
                            date_upload = 0L
                        }
                    }
                    .reversed()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /* =========================
       Page list
       ========================= */

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        return try {
            val result =
                json.decodeFromString<MangaDetailResponse>(
                    response.body.string(),
                )

            val url = response.request.url
            val chapterNumber =
                url.queryParameter("chapter")?.toIntOrNull()
                    ?: return emptyList()

            val chapter =
                result.manga.chapters.find {
                    it.number == chapterNumber
                }

            chapter?.images
                ?.sortedBy { it.order }
                ?.map { image ->
                    Page(
                        image.order - 1,
                        "",
                        "$baseUrl${image.url}",
                    )
                }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()
}

/* =========================
   DTOs
   ========================= */

@Serializable
data class MangaListResponse(
    val success: Boolean = true,
    val mangas: List<MangaDto> = emptyList(),
)

@Serializable
data class MangaDto(
    val slug: String,
    val title: String,
    val description: String = "",
    val cover: String,
    val status: String,
    val genres: List<String> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        url = "/manga/$slug"
        title = this@MangaDto.title
        description = this@MangaDto.description
        thumbnail_url = "https://rimuscans.com$cover"
        genre = genres.joinToString(", ")

        status =
            when (this@MangaDto.status.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
    }
}

@Serializable
data class MangaDetailResponse(
    val manga: MangaDetail,
)

@Serializable
data class MangaDetail(
    val slug: String,
    val title: String,
    val description: String = "",
    val cover: String = "",
    val status: String = "",
    val genres: List<String> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
data class ChapterDto(
    val number: Int,
    val title: String,
    val status: String = "PUBLISHED",
    val type: String = "NORMAL",
    val releaseDate: String = "",
    val images: List<ImageDto> = emptyList(),
)

@Serializable
data class ImageDto(
    val order: Int,
    val url: String,
)
