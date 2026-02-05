package eu.kanade.tachiyomi.extension.fr.rimuscans

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
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
        coerceInputValues = true
    }

    override val client: OkHttpClient =
        network.cloudflareClient.newBuilder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).build()

    override fun headersBuilder() =
        super.headersBuilder().add("Referer", baseUrl).add("User-Agent", "Mozilla/5.0")

    // Variables temporaires pour stocker la recherche et les filtres
    // (utilisées entre searchMangaRequest et searchMangaParse)
    private var lastSearchQuery: String = ""
    private var lastSearchFilters: FilterList = FilterList()

    /* =========================
       Popular / Latest / Search
       ========================= */

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/api/manga?page=$page&limit=24&sortBy=views", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/api/manga?page=$page&limit=24&sortBy=latest", headers)

    // Pour la recherche : on récupère BEAUCOUP de mangas (200) pour pouvoir filtrer côté client
    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        // On sauvegarde la query et les filtres pour les utiliser dans searchMangaParse
        lastSearchQuery = query
        lastSearchFilters = filters

        // On récupère plus de résultats pour avoir de quoi filtrer
        // Page 1 = mangas 1-200, Page 2 = mangas 201-400, etc.
        val limit = 200
        return GET("$baseUrl/api/manga?page=$page&limit=$limit&sortBy=views", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = json.decodeFromString<MangaListResponse>(
            response.body.string(),
        )

        val mangas = result.mangas.map { it.toSManga() }
        return MangasPage(mangas, mangas.size >= 24)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // NOUVELLE FONCTION DE RECHERCHE AVEC FILTRES
    override fun searchMangaParse(response: Response): MangasPage {
        val result = json.decodeFromString<MangaListResponse>(
            response.body.string(),
        )

        // On commence avec tous les mangas
        var filteredData = result.mangas

        // ===== FILTRE 1: RECHERCHE PAR TITRE =====
        if (lastSearchQuery.isNotBlank()) {
            filteredData = filteredData.filter { manga ->
                // Recherche insensible à la casse (majuscules/minuscules = pareil)
                manga.title.contains(lastSearchQuery, ignoreCase = true)
            }
        }

        // ===== FILTRE 2: TYPE (Manga / Manhwa) =====
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

                else -> {} // Ignore les autres filtres pour ce bloc
            }
        }

        // ===== FILTRE 3: STATUT (En Cours / Terminé / En Pause) =====
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

                else -> {} // Ignore les autres filtres pour ce bloc
            }
        }

        // ===== FILTRE 4: CHAPITRES MINIMUM (NOUVEAU - MENU DÉROULANT) =====
        lastSearchFilters.forEach { filter ->
            when (filter) {
                is MinChaptersFilter -> {
                    // Convertir la sélection du menu en nombre
                    val minChapters = when (filter.state) {
                        1 -> 10 // "10+"
                        2 -> 25 // "25+"
                        3 -> 50 // "50+"
                        4 -> 100 // "100+"
                        else -> 0 // "Tous"
                    }

                    if (minChapters > 0) {
                        filteredData = filteredData.filter { manga ->
                            manga.chapterCount >= minChapters
                        }
                    }
                }

                else -> {} // Ignore les autres filtres pour ce bloc
            }
        }

        // ===== FILTRE 5: GENRES (MODE ET - le manga doit avoir TOUS les genres sélectionnés) =====
        lastSearchFilters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    // On récupère tous les genres cochés
                    val selectedGenres =
                        filter.state.filter { it.state } // state = true si la case est cochée
                            .map { it.name } // on prend le nom du genre

                    // Si au moins un genre est sélectionné
                    if (selectedGenres.isNotEmpty()) {
                        filteredData = filteredData.filter { manga ->
                            // Le manga doit avoir TOUS les genres sélectionnés (mode ET)
                            selectedGenres.all { selectedGenre ->
                                manga.genres.any { mangaGenre ->
                                    mangaGenre.equals(selectedGenre, ignoreCase = true)
                                }
                            }
                        }
                    }
                }

                else -> {} // Ignore les autres filtres pour ce bloc
            }
        }

        // On convertit en SManga
        val mangas = filteredData.map { it.toSManga() }

        // On a une page suivante si on a récupéré 200 résultats
        // (ça veut dire qu'il y en a peut-être plus)
        return MangasPage(mangas, result.mangas.size >= 200)
    }

    /* =========================
       Manga details
       ========================= */

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

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

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val slug =
            response.request.url.pathSegments.lastOrNull { it.isNotEmpty() } ?: return emptyList()

        return try {
            val apiResponse = client.newCall(
                GET("$baseUrl/api/manga?slug=$slug", headers),
            ).execute()

            if (!apiResponse.isSuccessful) {
                Log.e("RimuScans", "API failed with code ${apiResponse.code}")
                return emptyList()
            }

            val bodyString = apiResponse.body.string()

            val result = json.decodeFromString<MangaDetailResponse>(bodyString)

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
        GET("$baseUrl${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> {
        return try {
            val result = json.decodeFromString<MangaDetailResponse>(
                response.body.string(),
            )

            val url = response.request.url
            val chapterNumber = url.queryParameter("chapter")?.toFloatOrNull() ?: return emptyList()

            val chapter = result.manga.chapters.find {
                it.number == chapterNumber
            }

            chapter?.images?.sortedBy { it.order }?.map { image ->
                Page(
                    image.order - 1,
                    "",
                    "$baseUrl${image.url}",
                )
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    /* =========================
       FILTRES - C'EST ICI QU'ON DÉCLARE LES FILTRES
       ========================= */

    // Cette fonction dit à Mihon : "Voici tous les filtres disponibles pour cette source"
    override fun getFilterList() = FilterList(
        Filter.Header("NOTE: Les filtres ne fonctionnent que dans la recherche"),
        Filter.Separator(),
        TypeFilter(),
        StatusFilter(),
        MinChaptersFilter(),
        GenreFilter(),
    )

    /* =========================
       Classes des filtres
       ========================= */

    // Filtre TYPE (Tous / Manga / Manhwa)
    private class TypeFilter : Filter.Select<String>(
        "Type",
        arrayOf("Tous", "Manga", "Manhwa"),
    )

    // Filtre STATUT (Tous / En Cours / Terminé / En Pause)
    private class StatusFilter : Filter.Select<String>(
        "Statut",
        arrayOf("Tous", "En Cours", "Terminé", "En Pause"),
    )

    // Filtre CHAPITRES MINIMUM - MENU DÉROULANT (au lieu d'input texte)
    private class MinChaptersFilter : Filter.Select<String>(
        "Chapitres minimum",
        arrayOf("Tous", "10+", "25+", "50+", "100+"),
    )

    // Filtre GENRES - Liste de 42 genres avec cases à cocher
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

    // Case à cocher pour un genre
    private class GenreCheckbox(name: String) : Filter.CheckBox(name)
}

/* =========================
   DTOs (les "objets" qui représentent les données JSON)
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
    val type: String = "", // webtoon, manga, etc.
    val genres: List<String> = emptyList(),
    val chapterCount: Int = 0, // nombre de chapitres
) {
    fun toSManga() = SManga.create().apply {
        url = "/manga/$slug"
        title = this@MangaDto.title
        description = this@MangaDto.description
        thumbnail_url = "https://rimuscans.com$cover"
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
