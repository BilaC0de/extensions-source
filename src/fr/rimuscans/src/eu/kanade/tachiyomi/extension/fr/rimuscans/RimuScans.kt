package eu.kanade.tachiyomi.extension.fr.rimuscans

import android.content.SharedPreferences
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

class RimuScans : HttpSource(), ConfigurableSource {

    override val name = "RimuScans"
    override val lang = "fr"
    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val internalBaseUrl get() = preferences.getString(BASE_URL_PREF, DEFAULT_BASE_URL)!!

    override val baseUrl get() = internalBaseUrl

    companion object {
        private const val DEFAULT_BASE_URL = "https://rimu-scans.fr/"
        private const val BASE_URL_PREF = "BASE_URL"
        private const val BASE_URL_PREF_TITLE = "URL du site"
        private const val BASE_URL_PREF_SUMMARY =
            "Entrez l'URL complète (ex: https://rimu-scans.fr/)"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override val client: OkHttpClient =
        network.cloudflareClient.newBuilder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).build()

    override fun headersBuilder() = super.headersBuilder().add("Referer", internalBaseUrl)

    private var lastSearchQuery: String = ""
    private var lastSearchFilters: FilterList = FilterList()

    /* =========================
       Popular / Latest / Search
       ========================= */

    override fun popularMangaRequest(page: Int): Request =
        GET("$internalBaseUrl/api/manga?page=$page&limit=24&sortBy=views", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$internalBaseUrl/api/manga?page=$page&limit=24&sortBy=latest", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        lastSearchQuery = query
        lastSearchFilters = filters
        val limit = 200
        return GET("$internalBaseUrl/api/manga?page=$page&limit=$limit&sortBy=views", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = json.decodeFromString<MangaListResponse>(response.body.string())
        val mangas = result.mangas.map { it.toSManga(internalBaseUrl) }
        return MangasPage(mangas, mangas.size >= 24)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaParse(response: Response): MangasPage {
        val result = json.decodeFromString<MangaListResponse>(response.body.string())

        var filteredData = result.mangas

        if (lastSearchQuery.isNotBlank()) {
            filteredData = filteredData.filter { manga ->
                manga.title.contains(lastSearchQuery, ignoreCase = true)
            }
        }

        lastSearchFilters.forEach { filter ->
            when (filter) {
                is TypeFilter -> {
                    val selectedType = filter.values[filter.state]
                    if (selectedType != "Tous") {
                        filteredData = filteredData.filter { manga ->
                            when (selectedType) {
                                "Manga" -> manga.type.equals("manga", ignoreCase = true)
                                "Manhwa" -> manga.type.equals(
                                    "webtoon",
                                    ignoreCase = true,
                                ) || manga.type.equals("manhwa", ignoreCase = true)

                                else -> true
                            }
                        }
                    }
                }

                else -> {}
            }
        }

        lastSearchFilters.forEach { filter ->
            when (filter) {
                is StatusFilter -> {
                    val selectedStatus = filter.values[filter.state]
                    if (selectedStatus != "Tous") {
                        filteredData = filteredData.filter { manga ->
                            when (selectedStatus) {
                                "En Cours" -> manga.status.equals("ongoing", ignoreCase = true)
                                "Terminé" -> manga.status.equals("completed", ignoreCase = true)
                                "En Pause" -> manga.status.equals(
                                    "paused",
                                    ignoreCase = true,
                                ) || manga.status.equals("hiatus", ignoreCase = true)

                                else -> true
                            }
                        }
                    }
                }

                else -> {}
            }
        }

        lastSearchFilters.forEach { filter ->
            when (filter) {
                is MinChaptersFilter -> {
                    val minChapters = when (filter.state) {
                        1 -> 10
                        2 -> 25
                        3 -> 50
                        4 -> 100
                        else -> 0
                    }
                    if (minChapters > 0) {
                        filteredData = filteredData.filter { manga ->
                            manga.chapterCount >= minChapters
                        }
                    }
                }

                else -> {}
            }
        }

        lastSearchFilters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    val selectedGenres = filter.state.filter { it.state }.map { it.name }
                    if (selectedGenres.isNotEmpty()) {
                        filteredData = filteredData.filter { manga ->
                            selectedGenres.all { selectedGenre ->
                                manga.genres.any { mangaGenre ->
                                    mangaGenre.equals(selectedGenre, ignoreCase = true)
                                }
                            }
                        }
                    }
                }

                else -> {}
            }
        }

        val mangas = filteredData.map { it.toSManga(internalBaseUrl) }
        return MangasPage(mangas, result.mangas.size >= 200)
    }

    /* =========================
       Manga details
       ========================= */

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$internalBaseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = org.jsoup.Jsoup.parse(response.body.string())
        return SManga.create().apply {
            title = document.title().replace(" - Rimu Scans", "").replace(" - RimuScans", "").trim()
            description = document.selectFirst("meta[name=description]")?.attr("content") ?: ""
            initialized = true
        }
    }

    /* =========================
       Chapter list
       ========================= */

    override fun chapterListRequest(manga: SManga): Request =
        GET("$internalBaseUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val slug =
            response.request.url.pathSegments.lastOrNull { it.isNotEmpty() } ?: return emptyList()

        return try {
            val apiResponse = client.newCall(
                GET("$internalBaseUrl/api/manga?slug=$slug", headers),
            ).execute()

            if (!apiResponse.isSuccessful) {
                Log.e("RimuScans", "API failed with code ${apiResponse.code}")
                return emptyList()
            }

            val result = json.decodeFromString<MangaDetailResponse>(apiResponse.body.string())

            result.manga.chapters.filter { chapter ->
                (chapter.status?.uppercase() == "PUBLISHED" || chapter.status == null) && (chapter.type?.uppercase() == "NORMAL" || chapter.type == null)
            }.map { chapter ->
                SChapter.create().apply {
                    name = chapter.title ?: "Chapitre ${chapter.number}"
                    chapter_number = chapter.number
                    url = "/api/manga?slug=$slug&chapter=${chapter.number}"
                    date_upload = 0L
                }
            }.reversed()
        } catch (e: Exception) {
            Log.e("RimuScans", "Exception: ${e.javaClass.simpleName}")
            Log.e("RimuScans", "Message: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    /* =========================
       Page list
       ========================= */

    override fun pageListRequest(chapter: SChapter): Request =
        GET("$internalBaseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        return try {
            val result = json.decodeFromString<MangaDetailResponse>(response.body.string())
            val chapterNumber = response.request.url.queryParameter("chapter")?.toFloatOrNull()
                ?: return emptyList()

            val chapter = result.manga.chapters.find { it.number == chapterNumber }

            chapter?.images?.sortedBy { it.order }?.map { image ->
                Page(image.order - 1, "", "$internalBaseUrl${image.url}")
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    /* =========================
       Filtres
       ========================= */

    override fun getFilterList() = FilterList(
        Filter.Header("NOTE: Les filtres ne fonctionnent que dans la recherche"),
        Filter.Separator(),
        TypeFilter(),
        StatusFilter(),
        MinChaptersFilter(),
        GenreFilter(),
    )

    private class TypeFilter : Filter.Select<String>(
        "Type",
        arrayOf("Tous", "Manga", "Manhwa"),
    )

    private class StatusFilter : Filter.Select<String>(
        "Statut",
        arrayOf("Tous", "En Cours", "Terminé", "En Pause"),
    )

    private class MinChaptersFilter : Filter.Select<String>(
        "Chapitres minimum",
        arrayOf("Tous", "10+", "25+", "50+", "100+"),
    )

    private class GenreFilter : Filter.Group<GenreCheckbox>(
        "Genres",
        listOf(
            GenreCheckbox("Action"),
            GenreCheckbox("Arts Martiaux"),
            GenreCheckbox("Assassin"),
            GenreCheckbox("Aventure"),
            GenreCheckbox("Combat"),
            GenreCheckbox("Comedie"),
            GenreCheckbox("Dieu"),
            GenreCheckbox("Drame"),
            GenreCheckbox("Ecchi"),
            GenreCheckbox("Fantaisie"),
            GenreCheckbox("Fantastique"),
            GenreCheckbox("Fantasy"),
            GenreCheckbox("Gender Bender"),
            GenreCheckbox("Harem"),
            GenreCheckbox("Historical"),
            GenreCheckbox("Historique"),
            GenreCheckbox("Horreur"),
            GenreCheckbox("Humour"),
            GenreCheckbox("Isekai"),
            GenreCheckbox("Josei"),
            GenreCheckbox("Magie"),
            GenreCheckbox("Martial Arts"),
            GenreCheckbox("Mature"),
            GenreCheckbox("Mecha"),
            GenreCheckbox("Murim"),
            GenreCheckbox("Mystere"),
            GenreCheckbox("Regression"),
            GenreCheckbox("Romance"),
            GenreCheckbox("Royauté"),
            GenreCheckbox("Réincarnation"),
            GenreCheckbox("Sci-Fi"),
            GenreCheckbox("Shônen"),
            GenreCheckbox("Slice of Life"),
            GenreCheckbox("Super Pouvoir"),
            GenreCheckbox("Surnaturel"),
            GenreCheckbox("Thriller"),
            GenreCheckbox("Tragédie"),
            GenreCheckbox("Vampire"),
            GenreCheckbox("Vengeance"),
            GenreCheckbox("Voyage Temporel"),
            GenreCheckbox("Wuxia"),
            GenreCheckbox("Yakuza"),
        ),
    )

    private class GenreCheckbox(name: String) : Filter.CheckBox(name)

    /* =========================
       Préférences
       ========================= */

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(DEFAULT_BASE_URL)
            dialogTitle = BASE_URL_PREF_TITLE

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val url = (newValue as String).trim().removeSuffix("/")
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        preferences.edit().putString(BASE_URL_PREF, url).commit()
                        true
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    false
                }
            }
        }

        screen.addPreference(baseUrlPref)
    }
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
    val type: String = "",
    val genres: List<String> = emptyList(),
    val chapterCount: Int = 0,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = "/manga/$slug"
        title = this@MangaDto.title
        description = this@MangaDto.description
        thumbnail_url = "$baseUrl$cover"
        genre = genres.joinToString(", ")
        status = when (this@MangaDto.status.lowercase()) {
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
    val number: Float,
    val title: String? = null,
    val status: String? = "PUBLISHED",
    val type: String? = "NORMAL",
    val releaseDate: String? = null,
    val images: List<ImageDto> = emptyList(),
)

@Serializable
data class ImageDto(
    val order: Int,
    val url: String,
)
