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
        thumbnail_url = coverUrl
        initialized = false
    }
}

@Serializable
class Meta(
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
)
