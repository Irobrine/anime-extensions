package eu.kanade.tachiyomi.animeextension.all.pikpak.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PikPakCaptchaRequest(
    val action: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("device_id") val deviceId: String,
    val meta: PikPakCaptchaMeta,
)

@Serializable
data class PikPakCaptchaMeta(
    val username: String? = null,
    val email: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
    @SerialName("captcha_sign") val captchaSign: String? = null,
    @SerialName("client_version") val clientVersion: String? = null,
    @SerialName("package_name") val packageName: String? = null,
    val timestamp: String? = null,
    @SerialName("user_id") val userId: String? = null,
)

@Serializable
data class PikPakCaptchaResponse(
    @SerialName("captcha_token") val captchaToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    val url: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
data class PikPakAuthResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("refresh_token") val refreshToken: String = "",
    val sub: String = "",
    @SerialName("user_id") val userId: String = "",
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
data class PikPakRefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("grant_type") val grantType: String = "refresh_token",
)

@Serializable
data class PikPakSignInRequest(
    val username: String,
    val password: String,
    @SerialName("client_id") val clientId: String,
)

@Serializable
data class PikPakFileListResponse(
    val files: List<PikPakFile>? = null,
    @SerialName("next_page_token") val nextPageToken: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
data class PikPakFile(
    val id: String = "",
    val name: String = "",
    val kind: String = "",
    val size: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    val phase: String? = null,
    val trashed: Boolean? = null,
    @SerialName("created_time") val createdTime: String? = null,
    @SerialName("thumbnail_link") val thumbnailLink: String? = null,
    @SerialName("web_content_link") val webContentLink: String? = null,
    val medias: List<PikPakMedia>? = null,
    val params: PikPakFileParams? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
data class PikPakMedia(
    @SerialName("media_name") val mediaName: String? = null,
    val link: PikPakMediaLink? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("is_origin") val isOrigin: Boolean = false,
    @SerialName("resolution_name") val resolutionName: String? = null,
)

@Serializable
data class PikPakMediaLink(
    val url: String? = null,
    val token: String? = null,
    val expire: String? = null,
    val type: String? = null,
)

@Serializable
data class PikPakFileParams(
    val url: String? = null,
)
