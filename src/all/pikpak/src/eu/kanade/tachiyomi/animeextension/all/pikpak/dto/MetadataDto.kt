package eu.kanade.tachiyomi.animeextension.all.pikpak.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============================== AniList ==============================

@Serializable
data class AniListRequest(
    val query: String,
    val variables: AniListVariables,
)

@Serializable
data class AniListVariables(
    val search: String,
)

@Serializable
data class AniListResponse(
    val data: AniListData? = null,
)

@Serializable
data class AniListData(
    val Media: AniListMedia? = null,
)

@Serializable
data class AniListMedia(
    val id: Int = 0,
    val title: AniListTitle? = null,
    val description: String? = null,
    val coverImage: AniListImage? = null,
    val bannerImage: String? = null,
    val genres: List<String>? = null,
    val status: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val format: String? = null,
    val studios: AniListStudios? = null,
    val characters: AniListCharacters? = null,
    val recommendations: AniListRecommendations? = null,
    val relations: AniListRelations? = null,
    val airingSchedule: AniListAiringSchedule? = null,
    val streamingEpisodes: List<AniListStreamingEpisode>? = null,
)

@Serializable
data class AniListTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
    val full: String? = null,
)

@Serializable
data class AniListImage(
    val large: String? = null,
    @SerialName("extraLarge") val extraLarge: String? = null,
)

@Serializable
data class AniListStudios(
    val edges: List<AniListStudioEdge>? = null,
)

@Serializable
data class AniListStudioEdge(
    @SerialName("isMain") val isMain: Boolean = false,
    val node: AniListNamed? = null,
)

@Serializable
data class AniListNamed(
    val name: String? = null,
)

@Serializable
data class AniListCharacters(
    val edges: List<AniListCharacterEdge>? = null,
)

@Serializable
data class AniListCharacterEdge(
    val role: String? = null,
    val node: AniListCharacter? = null,
)

@Serializable
data class AniListCharacter(
    val name: AniListTitle? = null,
    val image: AniListImage? = null,
)

@Serializable
data class AniListRecommendations(
    val nodes: List<AniListRecommendation>? = null,
)

@Serializable
data class AniListRecommendation(
    val mediaRecommendation: AniListMedia? = null,
)

@Serializable
data class AniListRelations(
    val edges: List<AniListRelationEdge>? = null,
)

@Serializable
data class AniListRelationEdge(
    val relationType: String? = null,
    val node: AniListMedia? = null,
)

@Serializable
data class AniListAiringSchedule(
    val nodes: List<AniListAiringNode>? = null,
)

@Serializable
data class AniListAiringNode(
    val episode: Int? = null,
    val airingAt: Long? = null,
)

@Serializable
data class AniListStreamingEpisode(
    val title: String? = null,
    val thumbnail: String? = null,
    val url: String? = null,
    val site: String? = null,
)

// ============================== TMDB ==============================

@Serializable
data class TmdbSearchResponse(
    val results: List<TmdbSearchResult>? = null,
)

@Serializable
data class TmdbSearchResult(
    val id: Int = 0,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
)

@Serializable
data class TmdbDetails(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val genres: List<TmdbName>? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val status: String? = null,
    @SerialName("production_companies") val productionCompanies: List<TmdbName>? = null,
    val networks: List<TmdbName>? = null,
    val credits: TmdbCredits? = null,
    val recommendations: TmdbResults? = null,
    val similar: TmdbResults? = null,
    val seasons: List<TmdbSeason>? = null,
    @SerialName("belongs_to_collection") val belongsToCollection: TmdbCollection? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
)

@Serializable
data class TmdbName(
    val name: String? = null,
)

@Serializable
data class TmdbCredits(
    val cast: List<TmdbCast>? = null,
)

@Serializable
data class TmdbCast(
    val name: String? = null,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbResults(
    val results: List<TmdbSearchResult>? = null,
)

@Serializable
data class TmdbSeason(
    @SerialName("season_number") val seasonNumber: Int? = null,
    val name: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
)

@Serializable
data class TmdbCollection(
    val name: String? = null,
)

@Serializable
data class TmdbSeasonDetails(
    val episodes: List<TmdbEpisode>? = null,
)

@Serializable
data class TmdbEpisode(
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("air_date") val airDate: String? = null,
)
