package eu.kanade.tachiyomi.extension.fr.crunchyscan

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ApiResponse(
    val data: List<MangaItem>,
    val meta: Meta,
)

@Serializable
class MangaItem(
    private val name: String,
    private val slug: String,
    @SerialName("cover_url") private val coverUrl: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        title = name
        url = "/lecture-en-ligne/$slug"
        // Le site renvoie parfois cover_url en http://, ce qui déclenche un challenge
        // Cloudflare (403) car le domaine force le HTTPS - on force donc le schéma ici.
        thumbnail_url = coverUrl?.replaceFirst("http://", "https://")
        initialized = false
    }
}

@Serializable
class Meta(
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
)
