package eu.kanade.tachiyomi.extension.fr.crunchyscan

import eu.kanade.tachiyomi.source.model.Filter

class Genre(name: String) : Filter.TriState(name)
class Year(name: String) : Filter.CheckBox(name)
class Status(name: String) : Filter.CheckBox(name)
class Type(name: String) : Filter.CheckBox(name)

class GenreFilter : Filter.Group<Genre>("Genres", GENRES.map(::Genre))
class YearFilter : Filter.Group<Year>("Années", YEARS.map(::Year))
class StatusFilter : Filter.Group<Status>("Statuts", STATUSES.map(::Status))
class TypeFilter : Filter.Group<Type>("Types", TYPES.map(::Type))

class ChapterMinFilter : Filter.Text("Chapitres (min)", "0")
class ChapterMaxFilter : Filter.Text("Chapitres (max)", "200")

private val GENRES = listOf(
    "Action", "Amour", "Aventure", "Arts Martiaux", "Combats", "Comédie", "Démons", "Drame",
    "Fantastique", "Historique", "Guerre", "Horreur", "Isekai", "Magie", "Mechas", "Militaire",
    "Monstres", "Mystère", "Mature", "Post Apocalyptique", "Psychologique", "Réincarnation",
    "Romance", "Science Fiction", "Sport", "Surnaturel", "Thriller", "Tranche De Vie",
    "Vie Scolaire", "Piccoma", "Delitoon", "Toomics", "Webtoon", "Tappytoon", "Harem", "Ono",
    "BL", "Oneshot", "Yuri", "Adulte", "Pocket Comics", "Pornhwa", "Mangadon", "HoneyToon",
)

private val YEARS = (2025 downTo 1987).map { it.toString() }

private val STATUSES = listOf("En cours", "Terminé", "Abandonné")

private val TYPES = listOf("Manga", "Manhwa", "Manhua", "Bande Dessinée")
