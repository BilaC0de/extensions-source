package eu.kanade.tachiyomi.extension.fr.phenixscansco

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.float
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class PhenixScans :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val defaultDomain = "https://phenix-scans.co"

    // Le domaine "site" configurable par l'utilisateur
    private val domain: String
        get() = preferences.getString(DOMAIN_PREF, defaultDomain)!!.trimEnd('/')

    // L'API vit sur un sous-domaine dérivé du domaine du site (api.<domaine>)
    private val apiBaseUrl: String
        get() = domain.replaceFirst("https://", "https://api.") + "/api"

    // baseUrl pointe sur /manga : c'est la page utilisée pour "Ouvrir dans le WebView"
    // depuis l'écran Browse, et c'est elle qui doit être présentée à l'utilisateur.
    override val baseUrl: String
        get() = "$domain/manga"

    private val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.FRENCH)

    // ============================== Popular ===============================
    override fun popularMangaRequest(page: Int): Request = GET("$apiBaseUrl/front/homepage?section=top", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<TopMangaDto>()

        val mangas = data.top.map {
            SManga.create().apply {
                title = it.title
                thumbnail_url = "$domain/${it.coverImage}" // Possibility of using ?width=75 and cdn.[...]/?url=
                url = it.slug
            }
        }

        return MangasPage(mangas, false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val apiUrl = "$apiBaseUrl/front/homepage?page=$page&section=latest&limit=12"

        return GET(apiUrl, headers)
    }

    private fun parseMangaList(mangaList: List<LatestMangaItemDto>): List<SManga> = mangaList.map {
        SManga.create().apply {
            title = it.title
            thumbnail_url = "$apiBaseUrl/${it.coverImage}" // Possibility of using ?width=75
            url = it.slug
        }
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val data = response.parseAs<LatestMangaDto>()

        val mangas = parseMangaList(data.latest)

        val hasNextPage = data.pagination.currentPage < data.pagination.totalPages

        return MangasPage(mangas, hasNextPage)
    }

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            // No limits here
            val apiUrl = "$apiBaseUrl/front/manga/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .build()
            return GET(apiUrl, headers)
        }

        val url = "$apiBaseUrl/front/manga".toHttpUrl().newBuilder()
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    url.addQueryParameter("sort", filter.toUriPart())
                }

                is GenreFilter -> {
                    val genres = filter.state
                        .filter { it.state }
                        .map { it.id }

                    url.addQueryParameter("genre", genres.joinToString(","))
                }

                is TypeFilter -> {
                    url.addQueryParameter("type", filter.toUriPart())
                }

                is StatusFilter -> {
                    url.addQueryParameter("status", filter.toUriPart())
                }

                else -> {}
            }
        }
        url.addQueryParameter("limit", "18") // Be cool on the API
        url.addQueryParameter("page", page.toString())

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val data = response.parseAs<SearchResultsDto>()

        val hasNextPage = (data.pagination?.page ?: 0) < (data.pagination?.totalPages ?: 0)

        val mangas = parseMangaList(data.mangas)

        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList(): FilterList = getGlobalFilterList(apiBaseUrl, client, headers)

    // =============================== Manga ==================================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val apiUrl = "$apiBaseUrl/front/manga/${manga.url}"

        return GET(apiUrl, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = response.parseAs<MangaDetailDto>()

        return SManga.create().apply {
            title = data.manga.title
            thumbnail_url = "$domain/${data.manga.coverImage}"
            url = data.manga.slug
            description = data.manga.synopsis
            status = when (data.manga.status) {
                "Ongoing" -> SManga.ONGOING
                "Hiatus" -> SManga.ON_HIATUS
                "Completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // baseUrl inclut déjà "/manga" (nécessaire pour le WebView de la source),
    // donc on ne le réutilise pas ici pour éviter "/manga/manga/...".
    override fun getMangaUrl(manga: SManga): String = "$domain/manga/${manga.url}"

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.parseAs<MangaDetailDto>()

        return data.chapters
            .filter { it.price == 0 }
            .map { chapterDto ->
                SChapter.create().apply {
                    chapter_number = chapterDto.number.float
                    date_upload = simpleDateFormat.tryParse(chapterDto.createdAt)
                    name = "Chapter ${chapterDto.number}"
                    url = "${data.manga.slug}/${chapterDto.number}"
                }
            }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val slug = chapter.url.substringBeforeLast("/")
        val chapterNumber = chapter.url.substringAfterLast("/")
        return "$domain/manga/$slug/chapitre/$chapterNumber"
    }

    // =============================== Pages ================================

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun pageListRequest(chapter: SChapter): Request {
        val slug = chapter.url.substringBeforeLast("/")
        val chapterNumber = chapter.url.substringAfterLast("/")

        val apiUrl = "$apiBaseUrl/front/manga/$slug/chapter/$chapterNumber"

        return GET(apiUrl, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<ChapterContentDto>()

        return data.chapter.images.mapIndexed { index, url ->
            Page(index, imageUrl = "$domain/$url")
        }
    }

    // ========================== Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = DOMAIN_PREF
            title = "URL du site"
            summary = "Modifier si le site change de domaine.\nActuellement : $domain"
            setDefaultValue(defaultDomain)
            dialogTitle = "URL du site"
            dialogMessage = "Entrez l'URL complète, ex : https://phenix-scans.co"
        }.also(screen::addPreference)
    }

    companion object {
        private const val DOMAIN_PREF = "pref_domain"
    }
}
