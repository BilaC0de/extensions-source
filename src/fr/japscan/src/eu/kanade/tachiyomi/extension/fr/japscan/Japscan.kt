package eu.kanade.tachiyomi.extension.fr.japscan

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Japscan : ParsedHttpSource(), ConfigurableSource {

    override val id: Long = 11

    override val name = "Japscan"

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val internalBaseUrl get() = preferences.getString(BASE_URL_PREF, DEFAULT_BASE_URL)!!

    override val baseUrl get() = "$internalBaseUrl/mangas/?sort=popular&p=1"

    override val lang = "fr"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(1, 2)
        .build()

    companion object {
        val dateFormat by lazy {
            SimpleDateFormat("dd MMM yyyy", Locale.US)
        }

        private const val DEFAULT_BASE_URL = "https://www.japscan.foo"
        private const val BASE_URL_PREF = "BASE_URL"
        private const val BASE_URL_PREF_TITLE = "URL du site"
        private const val BASE_URL_PREF_SUMMARY =
            "Entrez l'URL complète (ex: https://www.japscan.foo)"

        private const val SHOW_SPOILER_CHAPTERS_TITLE =
            "Les chapitres en Anglais ou non traduit sont upload en tant que \" Spoilers \" sur Japscan"
        private const val SHOW_SPOILER_CHAPTERS = "JAPSCAN_SPOILER_CHAPTERS"
        private val prefsEntries = arrayOf(
            "Montrer uniquement les chapitres traduit en Français",
            "Montrer les chapitres spoiler",
        )
        private val prefsEntryValues = arrayOf("hide", "show")
    }

    private fun chapterListPref() = preferences.getString(SHOW_SPOILER_CHAPTERS, "hide")

    override fun headersBuilder() = super.headersBuilder()
        .add("referer", "$internalBaseUrl/")

    // Popular
    override fun popularMangaRequest(page: Int): Request =
        GET("$internalBaseUrl/mangas/?sort=popular&p=$page", headers)

    override fun popularMangaNextPageSelector() = ".pagination > li:last-child:not(.disabled)"

    override fun popularMangaSelector() = ".mangas-list .manga-block:not(:has(a[href='']))"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("a").first()!!.let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.text()
            manga.thumbnail_url = it.selectFirst("img")?.attr("abs:data-src")
        }
        return manga
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$internalBaseUrl/mangas/?sort=updated&p=$page", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Si une recherche textuelle est fournie, utiliser l'API de recherche
        if (query.isNotEmpty()) {
            val formBody = FormBody.Builder()
                .add("search", query)
                .build()
            val searchHeaders = headers.newBuilder()
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            return POST("$internalBaseUrl/ls/", searchHeaders, formBody)
        }

        // Sinon, utiliser les filtres
        val url = internalBaseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("mangas")
            addPathSegment("")

            var status = "all"
            val types = mutableListOf<String>()
            val demographies = mutableListOf<String>()
            val genres = mutableListOf<String>()
            val years = mutableListOf<String>()
            var sortBy = "name"

            filters.forEach { filter ->
                when (filter) {
                    is StatusFilter -> {
                        status = when (filter.state) {
                            0 -> "all"
                            1 -> "encours"
                            2 -> "termine"
                            else -> "all"
                        }
                    }

                    is TypeFilter -> {
                        filter.state.forEach { checkbox ->
                            if (checkbox.state) {
                                types.add((checkbox as TypeCheckBox).value)
                            }
                        }
                    }

                    is DemographyFilter -> {
                        filter.state.forEach { checkbox ->
                            if (checkbox.state) {
                                demographies.add((checkbox as DemographyCheckBox).value)
                            }
                        }
                    }

                    is GenreFilter -> {
                        filter.state.forEach { checkbox ->
                            if (checkbox.state) {
                                genres.add(checkbox.name)
                            }
                        }
                    }

                    is YearFilter -> {
                        filter.state.forEach { checkbox ->
                            if (checkbox.state) {
                                years.add(checkbox.name)
                            }
                        }
                    }

                    is SortFilter -> {
                        sortBy = when (filter.state?.index) {
                            0 -> "name"
                            1 -> "popular"
                            2 -> "updated"
                            else -> "name"
                        }
                    }

                    else -> {}
                }
            }

            // Ajouter les paramètres à l'URL
            addQueryParameter("sort", sortBy)
            addQueryParameter("status", status)

            types.forEach { type ->
                addQueryParameter("type[]", type)
            }

            demographies.forEach { demog ->
                addQueryParameter("demog[]", demog)
            }

            genres.forEach { genre ->
                addQueryParameter("genre[]", genre)
            }

            years.forEach { year ->
                addQueryParameter("year[]", year)
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    override fun searchMangaSelector(): String = ".mangas-list .manga-block:not(:has(a[href='']))"

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.pathSegments.first() == "ls") {
            val jsonResult = json.parseToJsonElement(response.body.string()).jsonArray
            val mangaList = jsonResult.map { jsonEl -> searchMangaFromJson(jsonEl.jsonObject) }
            return MangasPage(mangaList, hasNextPage = false)
        }

        val baseUrlHost = internalBaseUrl.toHttpUrl().host
        val document = response.asJsoup()
        val manga = document
            .select(searchMangaSelector())
            .filter { it ->
                val href = it.select("a").attr("abs:href")
                href.isNotEmpty() && href.toHttpUrl().host == baseUrlHost
            }
            .map { popularMangaFromElement(it) }
        val hasNextPage = document.selectFirst(searchMangaNextPageSelector()) != null

        return MangasPage(manga, hasNextPage)
    }

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    private fun searchMangaFromJson(jsonObj: JsonObject): SManga = SManga.create().apply {
        url = jsonObj["url"]!!.jsonPrimitive.content
        title = jsonObj["name"]!!.jsonPrimitive.content
        thumbnail_url = internalBaseUrl + jsonObj["image"]!!.jsonPrimitive.content
    }

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(internalBaseUrl + manga.url, headers)

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.selectFirst("#main .card-body")!!
        val manga = SManga.create()

        manga.thumbnail_url = infoElement.selectFirst("img")?.attr("abs:src")

        val infoRows = infoElement.select(".row, .d-flex")
        infoRows.select("p").forEach { el ->
            when (el.select("span").text().trim()) {
                "Auteur(s):" -> manga.author = el.text().replace("Auteur(s):", "").trim()
                "Artiste(s):" -> manga.artist = el.text().replace("Artiste(s):", "").trim()
                "Genre(s):" -> manga.genre = el.text().replace("Genre(s):", "").trim()
                "Statut:" -> manga.status = el.text().replace("Statut:", "").trim().let {
                    parseStatus(it)
                }
            }
        }
        manga.description =
            infoElement.selectFirst("div:contains(Synopsis) + p")?.ownText().orEmpty()

        return manga
    }

    private fun parseStatus(status: String) = when {
        status.contains("En Cours") -> SManga.ONGOING
        status.contains("Terminé") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun getChapterUrl(chapter: SChapter): String = internalBaseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request =
        GET(internalBaseUrl + manga.url, headers)

    override fun chapterListSelector() = "#list_chapters > div.collapse > div.list_chapters" +
        if (chapterListPref() == "hide") {
            ":not(:has(.badge:contains(SPOILER),.badge:contains(RAW),.badge:contains(VUS)))"
        } else {
            ""
        }

    override fun chapterFromElement(element: Element): SChapter {
        val urlPairs = element.getElementsByTag("a")
            .mapNotNull { el ->
                val attrMatch = el.attributes().asList().firstOrNull { attr ->
                    val value = attr.value
                    value.startsWith("/manga/") || value.startsWith("/manhua/") ||
                        value.startsWith("/manhwa/") || value.startsWith("/bd/") ||
                        value.startsWith("/comic/")
                }
                if (attrMatch != null) {
                    val name = el.ownText().ifBlank { el.text() }
                    val isNonHref = attrMatch.key != "href"
                    Triple(name, attrMatch.value, isNonHref)
                } else {
                    null
                }
            }
            .distinctBy { it.second }
            .sortedWith(
                compareByDescending<Triple<String, String, Boolean>> { it.third }
                    .thenBy { it.second.length },
            )
            .map { Pair(it.first, it.second) }

        val foundPair: Pair<String, String>? = urlPairs.firstOrNull()

        if (foundPair == null) {
            throw Exception("Impossible de trouver l'URL du chapitre")
        }

        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(foundPair.second)
        chapter.name = foundPair.first
        chapter.date_upload =
            element.selectFirst("span")?.text()?.trim()?.let { parseChapterDate(it) } ?: 0L
        return chapter
    }

    private fun parseChapterDate(date: String) = runCatching {
        dateFormat.parse(date)!!.time
    }.getOrDefault(0L)

    @Serializable
    class ChapterDetails(
        val imagesLink: List<String>,
    )

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        try {
            val document = client.newCall(GET("$internalBaseUrl${chapter.url}")).execute().asJsoup()

            // Chercher l'attribut data-* avec les données cryptées
            // Le site change dynamiquement le nom (data-atad, data-f3db1f, data-ad4fef, etc.)
            val iElements = document.select("i")

            var atadBrut: String? = null
            var maxLength = 0

            // Trouver l'attribut data-* le plus long (c'est forcément les données cryptées)
            for (element in iElements) {
                for (attr in element.attributes()) {
                    val attrName = attr.key
                    val value = attr.value

                    // Ignorer les attributs techniques connus
                    if (attrName.startsWith("data-") &&
                        attrName != "data-index" &&
                        value.length > maxLength
                    ) {
                        atadBrut = value
                        maxLength = value.length
                    }
                }
            }

            if (atadBrut == null || atadBrut.length < 7) {
                throw Exception("Impossible de trouver les données cryptées")
            }

            val atad = atadBrut.substring(7)

            val mapping =
                "M7HXtiwLKdpIBkEbQ2OaF8Sxmz1yGReU4q5DncgsT6jVA3Pfv0WuJ9YCZNhlor".reversed()
            val reference =
                "uGJ657yOSbZRtplgHEYPBwCqaxQIizDWmTLMsAeNocnX0d98rf4Kj1kvh3UFV2".reversed()

            val decrypted =
                atad.replace(Regex("[A-Z0-9]", RegexOption.IGNORE_CASE)) { matchResult ->
                    val char = matchResult.value[0]
                    val index = reference.indexOf(char)
                    (if (index != -1) mapping[index] else char).toString()
                }

            val fromB64 = String(Base64.decode(decrypted, Base64.DEFAULT)).parseAs<ChapterDetails>()

            if (fromB64.imagesLink.isEmpty()) throw UnsupportedOperationException("Can't parse Images")

            return Observable.just(
                fromB64.imagesLink.mapIndexed { i, url ->
                    Page(i, imageUrl = "$url?o=1")
                },
            )
        } catch (e: Exception) {
            return fallbackFetchPageList(chapter)
        }
    }

    fun fallbackFetchPageList(chapter: SChapter): Observable<List<Page>> {
        val interfaceName = randomString()

        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val jsInterface = JsInterface(latch)
        var webView: WebView? = null

        handler.post {
            val innerWv = WebView(Injekt.get<Application>())

            webView = innerWv
            innerWv.settings.domStorageEnabled = true
            innerWv.settings.javaScriptEnabled = true
            innerWv.settings.blockNetworkImage = true
            innerWv.settings.userAgentString = headers["User-Agent"]
            innerWv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            innerWv.addJavascriptInterface(jsInterface, interfaceName)

            innerWv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(
                        """
                            Object.defineProperty(Object.prototype, 'imagesLink', {
                                set: function(value) {
                                    window.$interfaceName.passPayload(JSON.stringify(value));
                                    Object.defineProperty(this, '_imagesLink', {
                                        value: value,
                                        writable: true,
                                        enumerable: false,
                                        configurable: true
                                    });
                                },
                                get: function() {
                                    return this._imagesLink;
                                },
                                enumerable: false,
                                configurable: true
                            });
                        """.trimIndent(),
                    ) {}
                }
            }

            innerWv.loadUrl(
                "$internalBaseUrl${chapter.url}",
                headers.toMap(),
            )
        }

        latch.await(10, TimeUnit.SECONDS)
        handler.post { webView?.destroy() }

        if (latch.count == 1L) {
            throw Exception("Erreur lors de la récupération des pages")
        }

        val baseUrlHost = internalBaseUrl.toHttpUrl().host.substringAfter("www.")
        val images = jsInterface
            .images
            .filter { it.toHttpUrl().host.endsWith(baseUrlHost) }
            .mapIndexed { i, url ->
                Page(i, imageUrl = url)
            }

        return Observable.just(images)
    }

    override fun pageListParse(document: Document) = throw Exception("Not used")

    override fun imageUrlParse(document: Document): String = ""

    // Filters
    override fun getFilterList() = FilterList(
        Filter.Header("NOTE: La recherche de texte et les filtres ne peuvent pas être utilisés ensemble"),
        Filter.Separator(),
        SortFilter(),
        Filter.Separator(),
        StatusFilter(),
        Filter.Separator(),
        TypeFilter(),
        Filter.Separator(),
        DemographyFilter(),
        Filter.Separator(),
        GenreFilter(),
        Filter.Separator(),
        YearFilter(),
    )

    private class SortFilter : Filter.Sort(
        "Trier par",
        arrayOf("Nom", "Popularité", "Mise à jour"),
        Selection(0, true),
    )

    private class StatusFilter : Filter.Select<String>(
        "Statut",
        arrayOf("Tous", "En Cours", "Terminé"),
    )

    private class TypeFilter : Filter.Group<TypeCheckBox>(
        "Type",
        listOf(
            TypeCheckBox("Manga", "Manga", true),
            TypeCheckBox("Manhwa", "Manhwa", true),
            TypeCheckBox("Manhua", "Manhua", true),
        ),
    )

    private class TypeCheckBox(name: String, val value: String, state: Boolean = false) :
        Filter.CheckBox(name, state)

    private class DemographyFilter : Filter.Group<DemographyCheckBox>(
        "Démographie",
        listOf(
            DemographyCheckBox("Shōnen", "shonen", true),
            DemographyCheckBox("Shōjo", "shojo", true),
            DemographyCheckBox("Seinen", "seinen", true),
            DemographyCheckBox("Josei", "josei", true),
        ),
    )

    private class DemographyCheckBox(name: String, val value: String, state: Boolean = false) :
        Filter.CheckBox(name, state)

    private class GenreFilter : Filter.Group<GenreCheckBox>(
        "Genres",
        listOf(
            GenreCheckBox("Action"),
            GenreCheckBox("Aliens"),
            GenreCheckBox("Amitié"),
            GenreCheckBox("Amour"),
            GenreCheckBox("Animaux"),
            GenreCheckBox("Arts Martiaux"),
            GenreCheckBox("Assassinat"),
            GenreCheckBox("Autre Monde"),
            GenreCheckBox("Aventure"),
            GenreCheckBox("Combats"),
            GenreCheckBox("Comédie"),
            GenreCheckBox("Crime"),
            GenreCheckBox("Travestissement"),
            GenreCheckBox("Cuisine"),
            GenreCheckBox("Cyborgs"),
            GenreCheckBox("Détective"),
            GenreCheckBox("Démons"),
            GenreCheckBox("Documentaire"),
            GenreCheckBox("Drame"),
            GenreCheckBox("Dragons"),
            GenreCheckBox("Espace"),
            GenreCheckBox("Extra-Terrestres"),
            GenreCheckBox("Famille"),
            GenreCheckBox("Fantastique"),
            GenreCheckBox("Ghosts"),
            GenreCheckBox("Grimoire"),
            GenreCheckBox("Guerre"),
            GenreCheckBox("Historique"),
            GenreCheckBox("Horreur"),
            GenreCheckBox("Histoire"),
            GenreCheckBox("Humour"),
            GenreCheckBox("Isekai"),
            GenreCheckBox("Jeux"),
            GenreCheckBox("Magie"),
            GenreCheckBox("Mariage"),
            GenreCheckBox("Mechas"),
            GenreCheckBox("Militaire"),
            GenreCheckBox("Monstres"),
            GenreCheckBox("Musique"),
            GenreCheckBox("Mystère"),
            GenreCheckBox("Mature"),
            GenreCheckBox("Mafia"),
            GenreCheckBox("Ninjas"),
            GenreCheckBox("Philosophique"),
            GenreCheckBox("Politique"),
            GenreCheckBox("Post-Apocalyptique"),
            GenreCheckBox("Pouvoirs-Psychiques"),
            GenreCheckBox("Policier"),
            GenreCheckBox("Prison"),
            GenreCheckBox("Pirates"),
            GenreCheckBox("Psychologique"),
            GenreCheckBox("Réincarnation"),
            GenreCheckBox("Romance"),
            GenreCheckBox("Robots"),
            GenreCheckBox("Samouraï"),
            GenreCheckBox("Science-Fiction"),
            GenreCheckBox("Social"),
            GenreCheckBox("Sport"),
            GenreCheckBox("Survivre"),
            GenreCheckBox("Suicide"),
            GenreCheckBox("Scientifique"),
            GenreCheckBox("Super-Héros"),
            GenreCheckBox("Super-Pouvoirs"),
            GenreCheckBox("Suspense"),
            GenreCheckBox("Surnaturel"),
            GenreCheckBox("Technologie"),
            GenreCheckBox("Thriller"),
            GenreCheckBox("Time Travel"),
            GenreCheckBox("Technologies"),
            GenreCheckBox("Titans"),
            GenreCheckBox("Tragédie"),
            GenreCheckBox("Tranche De Vie"),
            GenreCheckBox("Vampires"),
            GenreCheckBox("Vie Scolaire"),
            GenreCheckBox("Viking"),
            GenreCheckBox("Virus"),
            GenreCheckBox("Western"),
            GenreCheckBox("Wuxia"),
            GenreCheckBox("Zombies"),
        ),
    )

    private class GenreCheckBox(name: String) : Filter.CheckBox(name)

    private class YearFilter : Filter.Group<YearCheckBox>(
        "Années",
        listOf(
            YearCheckBox("1929-1980"), YearCheckBox("1981-1990"), YearCheckBox("1991-2000"),
            YearCheckBox("2000"), YearCheckBox("2001"), YearCheckBox("2002"),
            YearCheckBox("2003"), YearCheckBox("2004"), YearCheckBox("2005"),
            YearCheckBox("2006"), YearCheckBox("2007"), YearCheckBox("2008"),
            YearCheckBox("2009"), YearCheckBox("2010"), YearCheckBox("2011"),
            YearCheckBox("2012"), YearCheckBox("2013"), YearCheckBox("2014"),
            YearCheckBox("2015"), YearCheckBox("2016"), YearCheckBox("2017"),
            YearCheckBox("2018"), YearCheckBox("2019"), YearCheckBox("2020"),
            YearCheckBox("2021"), YearCheckBox("2022"), YearCheckBox("2023"),
            YearCheckBox("2024"), YearCheckBox("2025"),
        ),
    )

    private class YearCheckBox(name: String) : Filter.CheckBox(name)

    // Preferences
    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(DEFAULT_BASE_URL)
            dialogTitle = BASE_URL_PREF_TITLE

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val url = newValue as String
                    val cleanUrl = url.trim().removeSuffix("/")
                    if (cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
                        preferences.edit().putString(BASE_URL_PREF, cleanUrl).commit()
                        true
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    false
                }
            }
        }

        val chapterListPref = androidx.preference.ListPreference(screen.context).apply {
            key = SHOW_SPOILER_CHAPTERS
            title = SHOW_SPOILER_CHAPTERS_TITLE
            entries = prefsEntries
            entryValues = prefsEntryValues
            setDefaultValue("hide")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(SHOW_SPOILER_CHAPTERS, entry).commit()
            }
        }

        screen.addPreference(baseUrlPref)
        screen.addPreference(chapterListPref)
    }

    private fun randomString(length: Int = 10): String {
        val charPool = ('a'..'z') + ('A'..'Z')
        return List(length) { charPool.random() }.joinToString("")
    }

    internal class JsInterface(private val latch: CountDownLatch) {
        var images: List<String> = listOf()
            private set

        @JavascriptInterface
        @Suppress("UNUSED")
        fun passPayload(rawData: String) {
            try {
                images = rawData.parseAs<List<String>>()
                    .map { "$it?y=1" }
                latch.countDown()
            } catch (_: Exception) {
                return
            }
        }
    }
}
