package eu.kanade.tachiyomi.extension.fr.poseidonscans

import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Source
abstract class PoseidonScans :
    HttpSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val defaultDomain = "https://poseidon-scans.net"

    // Le vrai domaine, utilisé pour toutes les requêtes
    private val domain: String
        get() = preferences.getString(DOMAIN_PREF, defaultDomain)!!.trimEnd('/')

    // baseUrl pointe sur /series (page protégée par Cloudflare).
    // Le WebView de Mihon ouvre directement cette URL → l'utilisateur résout
    // Cloudflare une fois, et tout fonctionne ensuite.
    override val baseUrl: String
        get() = "$domain/series"

    val rscHeaders = headersBuilder().add("RSC", "1").build()

    private fun String.toAbsoluteUrl(): String = if (this.startsWith("http")) this else domain + this

    private fun String.toApiCoverUrl(): String {
        if (this.startsWith("http")) return this
        if (this.contains("storage/covers/")) return "$domain/api/covers/${this.substringAfter("storage/covers/")}"
        if (this.startsWith("/api/covers/")) return domain + this
        if (this.startsWith("/")) return domain + this
        return "$domain/api/covers/$this"
    }

    // Ces overrides sont nécessaires car baseUrl = domain/series,
    // mais les pages manga sont à domain/serie/... (sans 's').
    // Sans eux, Mihon construirait domain/series/serie/slug → 404.
    override fun mangaDetailsRequest(manga: SManga): Request = GET(domain + manga.url, headers)
    override fun chapterListRequest(manga: SManga): Request = GET(domain + manga.url, rscHeaders)
    override fun pageListRequest(chapter: SChapter): Request = GET(domain + chapter.url, rscHeaders)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$domain/api/manga/lastchapters?limit=16&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val apiResponse = response.parseAs<LatestApiResponse>()
        val mangas = apiResponse.data.map { apiManga ->
            SManga.create().apply {
                title = apiManga.title
                url = "/serie/${apiManga.slug}"
                thumbnail_url = apiManga.slug.toApiCoverUrl() + ".webp"
            }
        }
        return MangasPage(mangas, mangas.size == 16)
    }

    // ============================== Popular ===============================

    // On utilise /series?sortBy=popular (liste paginée complète)
    // et non la page d'accueil RSC qui ne contient que ~5 mangas mis en avant.
    override fun popularMangaRequest(page: Int): Request {
        val url = domain.toHttpUrl().newBuilder().apply {
            addPathSegment("series")
            addQueryParameter("sortBy", "popular")
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    // =========================== Manga Details ============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val mangaDto = document.extractNextJs<MangaDetailsData>() ?: throw Exception("Cant scape data from Next.js")

        return SManga.create().apply {
            title = mangaDto.title
            thumbnail_url = "$domain/api/covers/${mangaDto.slug}.webp"
            author = mangaDto.author
            artist = mangaDto.artist
            genre = mangaDto.categories.mapNotNull { it.name.trim().takeIf { name -> name.isNotBlank() } }
                .joinToString(", ") { it.replaceFirstChar { char -> char.titlecase(Locale.FRENCH) } }
            status = parseStatus(mangaDto.status)
            description = mangaDto.description.trim().takeIf { it.isNotEmpty() }
            setUrlWithoutDomain("/serie/${mangaDto.slug}")
        }
    }

    private fun parseStatus(statusString: String?): Int = when (statusString?.trim()?.lowercase(Locale.FRENCH)) {
        "en cours" -> SManga.ONGOING
        "terminé" -> SManga.COMPLETED
        "en pause", "hiatus" -> SManga.ON_HIATUS
        "annulé", "abandonné" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // ============================== Chapters ==============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val url = response.request.url
        val rscBody = response.body.string()
        val chapters = chapterListRsc(rscBody)
        if (chapters.isNotEmpty()) return chapters

        // Les données RSC peuvent être partielles au premier chargement — on réessaie
        val retryUrl = url.newBuilder().addQueryParameter("_", System.currentTimeMillis().toString()).build()
        val retryRequest = response.request.newBuilder().url(retryUrl).header("Cache-Control", "no-cache").build()
        val retryResponse = client.newCall(retryRequest).execute()
        return chapterListRsc(retryResponse.body.string())
    }

    fun chapterListRsc(rscBody: String): List<SChapter> {
        val mangaPageDto = rscBody.extractNextJsRsc<MangaPageDetailsData>() ?: throw Exception("Cant scape data from Next.js")

        val showPremium = preferences.getBoolean(SHOW_PREMIUM_KEY, SHOW_PREMIUM_DEFAULT)

        return mangaPageDto.manga.chapters.mapNotNull { ch ->
            val isLocked = ch.isPremium == true && mangaPageDto.isPremiumUser != true

            if (isLocked && !showPremium) {
                val premiumUntilDate = ch.premiumUntil?.time ?: 0L
                if (System.currentTimeMillis() <= premiumUntilDate) return@mapNotNull null
            }

            SChapter.create().apply {
                val chapterNumberString = ch.number.toString().removeSuffix(".0")
                val isVolume = ch.isVolume == true || (ch.number % 1 == 0f && ch.title?.contains("volume", ignoreCase = true) == true)
                val baseName = if (isVolume) "Volume $chapterNumberString" else "Chapitre $chapterNumberString"
                val title = ch.title?.trim()?.takeIf { it.isNotBlank() }

                name = buildString {
                    if (isLocked) append("🔒 ")
                    append(if (title != null) "$baseName - $title" else baseName)
                    if (isLocked) {
                        val dateParts = formatTimestamp(ch.premiumUntil?.time ?: 0L).split(" ")
                        append(" - Free the ${dateParts.take(2).joinToString(" ")} at ${dateParts.getOrNull(2) ?: ""}")
                    }
                }.trim()

                setUrlWithoutDomain("/serie/${mangaPageDto.manga.slug}/chapter/$chapterNumberString")
                date_upload = ch.createdAt.time
                chapter_number = ch.number
            }
        }.sortedByDescending { it.chapter_number }
    }

    fun formatTimestamp(timestamp: Long): String = SimpleDateFormat("dd MMMM HH:mm", Locale.getDefault()).format(Date(timestamp))

    // =============================== Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        val pageDataDto = response.extractNextJs<PageData>() ?: throw Exception("Cant scape data from Next.js")

        if (pageDataDto.currentChapter.isPremium) {
            if (pageDataDto.sessionStatus == "unauthenticated") {
                throw Exception("Ce chapitre est premium. Connecte-toi via le WebView pour y accéder.")
            }
            if (!pageDataDto.isPremiumUser) {
                throw Exception("Ce chapitre est premium. Tu n'es pas abonné premium.")
            }
        }

        return pageDataDto.initialData.images.map { pageDto ->
            Page(
                index = pageDto.order,
                imageUrl = pageDto.originalUrl.toAbsoluteUrl(),
            )
        }.sortedBy { it.index }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder().set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", page.url.ifBlank { "$domain/" }).build()
        return GET(page.imageUrl!!, imageHeaders)
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = domain.toHttpUrl().newBuilder().apply {
            addPathSegment("series")
            if (query.isNotBlank()) addQueryParameter("search", query)
            if (page > 1) addQueryParameter("page", page.toString())

            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> addQueryParameter("sortBy", filter.getValue())
                    is StatusFilter -> filter.getValue()?.let { addQueryParameter("status", it) }
                    is TypeFilter -> {
                        val selected = filter.getValues()
                        if (selected.isNotEmpty()) addQueryParameter("tags", selected.joinToString(","))
                    }

                    is GenreFilter -> {
                        val selected = filter.getValues()
                        if (selected.isNotEmpty()) addQueryParameter("tags", selected.joinToString(","))
                    }

                    is MinChaptersFilter -> if (filter.state.isNotBlank()) addQueryParameter("minChapters", filter.state)
                    is MaxChaptersFilter -> if (filter.state.isNotBlank()) addQueryParameter("maxChapters", filter.state)
                    else -> {}
                }
            }
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("div.grid a.block.group").map { element ->
            val url = element.attr("href")
            val title = element.selectFirst("h2")?.text()!!
            val thumbnailUrlPath = element.selectFirst("img[alt]")?.attr("srcset")?.substringBefore(" ")
                ?.let { URLDecoder.decode(it, "UTF-8").substringAfter("url=").substringBefore("&") }

            SManga.create().apply {
                setUrlWithoutDomain(url)
                this.title = title
                thumbnail_url = thumbnailUrlPath?.takeIf { it.isNotBlank() }?.toApiCoverUrl()
            }
        }

        val hasNextPage = document.select("nav[aria-label=Pagination] a:contains(Suivant)").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Filters ==============================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Les filtres ne fonctionnent pas avec la recherche par texte"),
        Filter.Separator(),
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
        MinChaptersFilter(),
        MaxChaptersFilter(),
    )

    private class SortFilter :
        Filter.Select<String>(
            "Tri",
            arrayOf("Ajout Récent (Série)", "Dernier Chapitre", "Plus de chapitres", "Popularité", "Ordre alphabétique"),
        ) {
        fun getValue() = when (state) {
            1 -> "latest_chapter"
            2 -> "most_chapters"
            3 -> "popular"
            4 -> "alpha"
            else -> "recent"
        }
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Statut",
            arrayOf("Tous", "En cours", "Terminé", "En pause", "Annulé"),
        ) {
        fun getValue() = when (state) {
            1 -> "en cours"
            2 -> "terminé"
            3 -> "en pause"
            4 -> "annulé"
            else -> null
        }
    }

    private class TypeCheckBox(name: String) : Filter.CheckBox(name)
    private class TypeFilter :
        Filter.Group<TypeCheckBox>(
            "Type",
            listOf(
                TypeCheckBox("MANGA"),
                TypeCheckBox("MANHUA"),
                TypeCheckBox("MANHWA"),
                TypeCheckBox("WEBTOON"),
            ),
        ) {
        fun getValues() = state.filter { it.state }.map { it.name }
    }

    private class GenreCheckBox(name: String) : Filter.CheckBox(name)
    private class GenreFilter :
        Filter.Group<GenreCheckBox>(
            "Genres",
            listOf(
                GenreCheckBox("Délinquant"),
                GenreCheckBox("Détective"),
                GenreCheckBox("Drama"),
                GenreCheckBox("Ecchi"),
                GenreCheckBox("Fantaisie"),
                GenreCheckBox("Fantastique"),
                GenreCheckBox("Mystère"),
                GenreCheckBox("Necromancer"),
                GenreCheckBox("Portail/Donjon"),
                GenreCheckBox("Psychologique"),
                GenreCheckBox("Réincarnation"),
                GenreCheckBox("Regression"),
                GenreCheckBox("Romance"),
                GenreCheckBox("Shojo"),
                GenreCheckBox("Shonen"),
                GenreCheckBox("Sports"),
                GenreCheckBox("Super pouvoirs"),
                GenreCheckBox("Surnaturel"),
                GenreCheckBox("Systeme"),
                GenreCheckBox("Tour"),
                GenreCheckBox("Tragique"),
                GenreCheckBox("Vengeance"),
                GenreCheckBox("Vie scolaire"),
            ),
        ) {
        fun getValues() = state.filter { it.state }.map { it.name }
    }

    private class MinChaptersFilter : Filter.Text("Chapitres min", "0")
    private class MaxChaptersFilter : Filter.Text("Chapitres max", "500")

    // ========================== Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = DOMAIN_PREF
            title = "URL du site"
            summary = "Modifier si le site change de domaine.\nActuellement : $domain"
            setDefaultValue(defaultDomain)
            dialogTitle = "URL du site"
            dialogMessage = "Entrez l'URL complète, ex : https://poseidon-scans.net"
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = SHOW_PREMIUM_KEY
            title = "Afficher les chapitres premium"
            summary = "Affiche les chapitres payants (identifiés par 🔒) dans la liste."
            setDefaultValue(SHOW_PREMIUM_DEFAULT)
        }.also(screen::addPreference)
    }

    companion object {
        private const val DOMAIN_PREF = "pref_domain"
        private const val SHOW_PREMIUM_KEY = "show_premium_chapters"
        private const val SHOW_PREMIUM_DEFAULT = false
    }
}
