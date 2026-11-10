package eu.kanade.tachiyomi.animeextension.all.pikpak.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The user-data blob StremThru expects in the addon path.
 *
 * The token is the PikPak account in `email:password` form.
 */
@Serializable
data class StremThruUserData(
    @SerialName("store_name") val storeName: String,
    @SerialName("store_token") val storeToken: String,
)

@Serializable
data class CatalogResponse(
    val metas: List<MetaPreview>? = null,
)

@Serializable
data class MetaPreview(
    val id: String,
    val type: String? = null,
    val name: String = "",
    val description: String? = null,
    val poster: String? = null,
    val background: String? = null,
)

@Serializable
data class MetaResponse(
    val meta: Meta? = null,
)

@Serializable
data class Meta(
    val id: String,
    val type: String? = null,
    val name: String = "",
    val description: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val videos: List<MetaVideo>? = null,
)

@Serializable
data class MetaVideo(
    val id: String,
    val title: String = "",
    val released: String? = null,
    val thumbnail: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

@Serializable
data class StreamResponse(
    val streams: List<Stream>? = null,
)

@Serializable
data class Stream(
    val url: String? = null,
    val name: String? = null,
    val title: String? = null,
)

@Serializable
data class TorzResponse(
    val data: TorzData? = null,
)

@Serializable
data class TorzData(
    val id: String = "",
    val name: String = "",
    val files: List<TorzFile>? = null,
)

@Serializable
data class TorzFile(
    val index: Int? = null,
    val link: String = "",
    val name: String = "",
    val path: String = "",
    val size: Long? = null,
)

@Serializable
data class PikPakEpisodeUrl(
    val fileId: String? = null,
    val streamId: String,
)
