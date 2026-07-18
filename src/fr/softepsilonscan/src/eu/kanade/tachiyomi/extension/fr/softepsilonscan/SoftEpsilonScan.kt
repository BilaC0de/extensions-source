package eu.kanade.tachiyomi.extension.fr.softepsilonscan

import android.content.ComponentName
import android.content.Intent
import eu.kanade.tachiyomi.multisrc.pam.CheckBoxGroup
import eu.kanade.tachiyomi.multisrc.pam.Pam
import eu.kanade.tachiyomi.multisrc.pam.SortFilter
import eu.kanade.tachiyomi.multisrc.pam.TriStateGroupFilter
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.utils.applicationContext
import okhttp3.Response
import org.jsoup.Jsoup

@Source
abstract class SoftEpsilonScan : Pam() {

    override val popularFilters = FilterList(SortFilter("Sort", sortValues, Filter.Sort.Selection(3, false)))
    override val latestFilters = FilterList(SortFilter("Sort", sortValues, Filter.Sort.Selection(2, false)))

    override fun getFilterList() = FilterList(
        Filter.Header("La recherche textuelle ignore les filtres !"),
        Filter.Separator(),
        SortFilter("Sort", sortValues),
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
    )

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun pageListParse(response: Response): List<Page> = try {
        super.pageListParse(response)
    } catch (e: Exception) {
        val chapterUrl = response.request.url.toString()
        val looksBlocked = runCatching {
            val doc = Jsoup.parse(response.peekBody(Long.MAX_VALUE).string())
            doc.selectFirst(".cf-turnstile") != null || doc.title().contains("Just a moment", ignoreCase = true) || doc.title()
                .contains("Attention Required", ignoreCase = true)
        }.getOrDefault(true) // si le HTML ne parse même pas, on suppose un blocage

        if (!looksBlocked) throw e

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

    private class GenreFilter : TriStateGroupFilter("Genres", genres)
    private class TypeFilter : TriStateGroupFilter("Types", type)
    private class StatusFilter : CheckBoxGroup("Status", status)
}

private val sortValues = listOf(
    "New Series" to "date",
    "Trending" to "trending",
    "Recently Updated" to "recently",
    "Most Views" to "views",
    "A-Z" to "alphabetical",
)

private val genres = listOf(
    "Action" to "action",
    "Ahegao" to "ahegao",
    "Anal" to "anal",
    "Anime bl" to "anime-bl",
    "Arts martiaux" to "arts-martiaux",
    "Aventure" to "aventure",
    "Bdsm" to "bdsm",
    "Bondage" to "bondage",
    "Boys love" to "boys-love",
    "Bureau" to "bureau",
    "Campus" to "campus",
    "Comédie" to "comedie",
    "Comics" to "comics",
    "Cosplay" to "cosplay",
    "Coup d'un soir" to "coup-dun-soir",
    "Dark skin" to "dark-skin",
    "Démon/démone" to "demondemone",
    "Différence d'âge" to "difference-dage",
    "Doujinshi" to "doujinshi",
    "Drame" to "drame",
    "Échangisme" to "echangisme",
    "Elf" to "elf",
    "Espion" to "espion",
    "Exhibitionniste" to "exhibitionniste",
    "Fantaisie" to "fantaisie",
    "Fantastique" to "fantastique",
    "Fétichisme" to "fetichisme",
    "Furry" to "furry",
    "Gangster" to "gangster",
    "Gender bender" to "gender-bender",
    "Girls love" to "girls-love",
    "Gros seins" to "gros-seins",
    "Guideverse" to "guideverse",
    "Hardcore" to "hardcore",
    "Harem" to "harem",
    "Historique" to "historique",
    "Horreur" to "horreur",
    "Hypnose" to "hypnose",
    "Ia" to "ia",
    "Immoral" to "immoral",
    "Isekai" to "isekai",
    "Jeux vidéo" to "jeux-video",
    "Josei" to "josei",
    "Magie" to "magie",
    "Manga bl" to "manga-bl",
    "Manga h" to "manga-h",
    "Manga josei" to "manga-josei",
    "Mature" to "mature",
    "Médical" to "medical",
    "Milf" to "milf",
    "Mini-série" to "mini-serie",
    "Moderne" to "moderne",
    "Muscle" to "muscle",
    "Mystère" to "mystere",
    "Noblesse" to "noblesse",
    "Non-censuré" to "non-censure",
    "Novel" to "novel",
    "Ntr" to "ntr",
    "Omégaverse" to "omegaverse",
    "One shot" to "one-shot",
    "Percing" to "percing",
    "Plan à 3" to "plan-a-3",
    "Pornhwa" to "pornhwa",
    "Professeur" to "professeur",
    "Psychologique" to "psychologique",
    "Réincarnation" to "reincarnation",
    "Romance" to "romance",
    "Science-fiction" to "science-fiction",
    "Showbiz" to "showbiz",
    "Smut" to "smut",
    "Spanking" to "spanking",
    "Sports" to "sports",
    "Succube" to "succube",
    "Surnaturel" to "surnaturel",
    "Système" to "systeme",
    "Thriller" to "thriller",
    "Tragédie" to "tragedie",
    "Tranche de vie" to "tranche-de-vie",
    "Triangle amoureux" to "triangle-amoureux",
    "Tsundere" to "tsundere",
    "Vampire" to "vampire",
    "Vengeance" to "vengeance",
    "Vie scolaire" to "vie-scolaire",
    "Webtoon" to "webtoon",
)

private val type = listOf(
    "Anime bl" to "anime-bl",
    "Boys love" to "boys-love",
    "Doujinshi" to "doujinshi",
    "Girls love" to "girls-love",
    "Hentai" to "hentai",
    "Josei" to "josei",
    "Manga" to "manga",
    "Manga bl" to "manga-bl",
    "Manga h" to "manga-h",
    "Manga josei" to "manga-josei",
    "Manhwa" to "manhwa",
    "Manwha" to "manwha",
    "Novel" to "novel",
    "Other" to "other",
    "Pornhwa" to "pornhwa",
    "Seinen" to "seinen",
)

private val status = listOf(
    "Ongoing" to "ongoing",
    "Finished" to "finished",
    "Dropped" to "dropped",
    "On Hold" to "onhold",
    "Upcoming" to "upcoming",
)
