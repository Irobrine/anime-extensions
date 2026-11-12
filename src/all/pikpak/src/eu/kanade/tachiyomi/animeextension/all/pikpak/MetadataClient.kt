package eu.kanade.tachiyomi.animeextension.all.pikpak

import android.util.Log
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.AniListMedia
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.AniListRequest
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.AniListResponse
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.AniListVariables
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.TmdbDetails
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.TmdbSearchResponse
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.TmdbSeasonDetails
import keiyoushi.network.get
import keiyoushi.network.post
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class AnimeMetadata(
    val title: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val studios: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val characters: List<CharacterInfo> = emptyList(),
    val recommendations: List<Suggestion> = emptyList(),
    val related: List<Suggestion> = emptyList(),
    val episodes: List<EpisodeInfo> = emptyList(),
    val episodeCount: Int? = null,
) {
    fun resolveEpisodeNumber(fileName: String): Int? {
        val normalized = normalize(fileName)
        if (normalized.isBlank()) return null

        return episodes.firstOrNull { episode ->
            val title = normalize(episode.title)
            title.isNotBlank() && (normalized.contains(title) || title.contains(normalized))
        }?.number
    }

    companion object {
        fun normalize(value: String?): String = value.orEmpty()
            .lowercase(Locale.US)
            .replace(Regex("\\.[a-z0-9]{2,4}$"), "")
            .replace(Regex("[^a-z0-9]+"), "")
    }
}

data class CharacterInfo(
    val name: String,
    val detail: String? = null,
)

data class Suggestion(
    val title: String,
    val label: String? = null,
)

data class EpisodeInfo(
    val number: Int,
    val title: String? = null,
    val airDate: Long? = null,
)

internal class MetadataClient(
    private val client: OkHttpClient,
    private val tmdbApiKey: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val cache = ConcurrentHashMap<String, Pair<AnimeMetadata?, Long>>()

    suspend fun fetch(query: String, seasonNumber: Int? = null): AnimeMetadata? {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return null

        val key = "${cleanQuery.lowercase(Locale.US)}|${seasonNumber ?: -1}"
        val now = System.currentTimeMillis()
        cache[key]?.let { (metadata, expiresAt) ->
            if (expiresAt > now) return metadata
        }

        val metadata = fetchAniList(cleanQuery) ?: fetchTmdb(cleanQuery, seasonNumber)
        cache[key] = metadata to (now + if (metadata != null) CACHE_MS else MISS_CACHE_MS)
        return metadata
    }

    private suspend fun fetchAniList(query: String): AnimeMetadata? {
        val body = json.encodeToString(AniListRequest(ANILIST_QUERY, AniListVariables(query)))
            .toRequestBody(JSON_MEDIA_TYPE)

        val headers = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "application/json")
            .add("User-Agent", "PikPak-Aniyomi-Extension")
            .build()

        return try {
            client.post(ANILIST_URL, headers, body, ensureSuccess = false).use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    Log.d(TAG, "anilist HTTP ${response.code} query=$query")
                    return null
                }

                val data = runCatching { json.decodeFromString<AniListResponse>(text) }.getOrNull()
                val media = data?.data?.Media
                if (media == null) {
                    Log.d(TAG, "anilist no result query=$query")
                    return null
                }
                media.toMetadata()
            }
        } catch (e: Exception) {
            Log.d(TAG, "anilist failed query=$query error=${e.message}")
            null
        }
    }

    private suspend fun fetchTmdb(query: String, seasonNumber: Int?): AnimeMetadata? {
        if (tmdbApiKey.isBlank()) return null

        val search = tmdbGet<TmdbSearchResponse>(
            "search/multi",
            mapOf(
                "query" to query,
                "language" to "en-US",
                "include_adult" to "false",
            ),
        ) ?: return null

        val result = search.results.orEmpty()
            .firstOrNull { it.mediaType == "movie" || it.mediaType == "tv" }
            ?: return null
        val type = result.mediaType ?: return null

        val details = tmdbGet<TmdbDetails>(
            "$type/${result.id}",
            mapOf(
                "language" to "en-US",
                "append_to_response" to "credits,recommendations,similar",
            ),
        ) ?: return null

        val seasonEpisodes = if (type == "tv" && seasonNumber != null) {
            tmdbGet<TmdbSeasonDetails>(
                "tv/${result.id}/season/$seasonNumber",
                mapOf("language" to "en-US"),
            )?.episodes.orEmpty().mapNotNull { episode ->
                val number = episode.episodeNumber ?: return@mapNotNull null
                EpisodeInfo(number, episode.name, parseDate(episode.airDate))
            }
        } else {
            emptyList()
        }

        Log.d(TAG, "tmdb matched query=$query type=$type id=${result.id}")
        return details.toMetadata(type, seasonEpisodes)
    }

    private suspend inline fun <reified T> tmdbGet(path: String, params: Map<String, String>): T? {
        val builder = TMDB_BASE.newBuilder().addPathSegments(path)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }

        val headers = Headers.Builder()
            .add("Accept", "application/json")
            .apply {
                if (tmdbApiKey.startsWith("eyJ")) {
                    add("Authorization", "Bearer $tmdbApiKey")
                } else {
                    builder.addQueryParameter("api_key", tmdbApiKey)
                }
            }
            .build()

        return try {
            client.get(builder.build(), headers, ensureSuccess = false).use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    Log.d(TAG, "tmdb HTTP ${response.code} path=$path")
                    return null
                }
                runCatching { json.decodeFromString<T>(text) }.getOrNull()
            }
        } catch (e: Exception) {
            Log.d(TAG, "tmdb failed path=$path error=${e.message}")
            null
        }
    }

    private fun AniListMedia.toMetadata(): AnimeMetadata {
        val allStudios = studios?.edges.orEmpty()
        val mainStudios = allStudios.filter { it.isMain }.mapNotNull { it.node?.name }
        val studioNames = mainStudios.ifEmpty { allStudios.mapNotNull { it.node?.name } }.distinct()

        val characterList = characters?.edges.orEmpty()
            .filterNot { it.role.equals("BACKGROUND", ignoreCase = true) }
            .mapNotNull { edge ->
                val name = edge.node?.name?.full ?: return@mapNotNull null
                CharacterInfo(name, edge.role?.toRoleLabel())
            }

        val recommendationList = recommendations?.nodes.orEmpty().mapNotNull { node ->
            val media = node.mediaRecommendation ?: return@mapNotNull null
            val title = media.title?.romaji ?: media.title?.english ?: return@mapNotNull null
            Suggestion(title, "Recommended")
        }.distinctBy { it.title.lowercase(Locale.US) }

        val relatedList = relations?.edges.orEmpty().mapNotNull { edge ->
            val media = edge.node ?: return@mapNotNull null
            val title = media.title?.romaji ?: media.title?.english ?: return@mapNotNull null
            Suggestion(title, edge.relationType?.toRelationLabel())
        }.distinctBy { it.title.lowercase(Locale.US) }

        val airDates = airingSchedule?.nodes.orEmpty()
            .mapNotNull { node ->
                val episode = node.episode ?: return@mapNotNull null
                val airingAt = node.airingAt ?: return@mapNotNull null
                episode to airingAt * 1000
            }
            .toMap()

        val episodeMap = linkedMapOf<Int, EpisodeInfo>()
        airDates.forEach { (number, airDate) ->
            episodeMap[number] = EpisodeInfo(number, null, airDate)
        }
        streamingEpisodes.orEmpty().forEach { episode ->
            val number = parseEpisodeNumber(episode.title) ?: return@forEach
            val existing = episodeMap[number]
            episodeMap[number] = EpisodeInfo(number, episode.title, existing?.airDate)
        }

        return AnimeMetadata(
            title = title?.romaji ?: title?.english ?: title?.native,
            description = description?.let { Jsoup.parse(it).wholeText().trim() },
            coverUrl = coverImage?.extraLarge ?: coverImage?.large,
            bannerUrl = bannerImage,
            studios = studioNames,
            genres = genres.orEmpty(),
            status = status,
            characters = characterList,
            recommendations = recommendationList,
            related = relatedList,
            episodes = episodeMap.values.toList(),
            episodeCount = episodes ?: episodeMap.size.takeIf { it > 0 },
        )
    }

    private fun TmdbDetails.toMetadata(type: String, seasonEpisodes: List<EpisodeInfo>): AnimeMetadata {
        val studioNames = productionCompanies?.mapNotNull { it.name }.orEmpty()
            .ifEmpty { networks?.mapNotNull { it.name }.orEmpty() }
            .distinct()

        val characterList = credits?.cast.orEmpty().take(8).mapNotNull { cast ->
            val name = cast.character ?: cast.name ?: return@mapNotNull null
            val detail = cast.name?.takeIf { it.isNotBlank() && it != name }?.let { "Voice: $it" }
            CharacterInfo(name, detail)
        }

        val recommendations = (recommendations?.results.orEmpty() + similar?.results.orEmpty())
            .mapNotNull { result ->
                val title = result.title ?: result.name ?: return@mapNotNull null
                Suggestion(title, "Recommended")
            }
            .distinctBy { it.title.lowercase(Locale.US) }

        val relatedList = if (type == "tv") {
            seasons.orEmpty().mapNotNull { season ->
                val number = season.seasonNumber ?: return@mapNotNull null
                val name = season.name?.takeIf { it.isNotBlank() } ?: "Season $number"
                Suggestion(name, "Season")
            }
        } else {
            listOfNotNull(belongsToCollection?.name?.let { Suggestion(it, "Collection") })
        }

        return AnimeMetadata(
            title = title ?: name,
            description = overview,
            coverUrl = tmdbImage(posterPath, "w500"),
            bannerUrl = tmdbImage(backdropPath, "w1280"),
            studios = studioNames,
            genres = genres?.mapNotNull { it.name }.orEmpty(),
            status = status,
            characters = characterList,
            recommendations = recommendations,
            related = relatedList,
            episodes = seasonEpisodes,
            episodeCount = numberOfEpisodes ?: seasonEpisodes.size.takeIf { it > 0 },
        )
    }

    private fun tmdbImage(path: String?, size: String): String? = path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    private fun parseDate(date: String?): Long? {
        if (date.isNullOrBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)?.time
        }.getOrNull()
    }

    private fun parseEpisodeNumber(title: String?): Int? {
        if (title.isNullOrBlank()) return null

        EPISODE_WORD_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        LEADING_NUMBER_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun String.toRoleLabel(): String = when (uppercase(Locale.US)) {
        "MAIN" -> "Main"
        "SUPPORTING" -> "Supporting"
        "BACKGROUND" -> "Background"
        else -> lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
    }

    private fun String.toRelationLabel(): String = lowercase(Locale.US)
        .split('_')
        .joinToString(" ") { part -> part.replaceFirstChar { it.titlecase(Locale.US) } }

    companion object {
        private const val TAG = "PikPakMetadata"
        private const val CACHE_MS = 6 * 60 * 60 * 1000L
        private const val MISS_CACHE_MS = 30 * 60 * 1000L

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val ANILIST_URL = "https://graphql.anilist.co".toHttpUrl()
        private val TMDB_BASE = "https://api.themoviedb.org/3".toHttpUrl()

        private val EPISODE_WORD_REGEX = Regex("(?i)(?:E|Episode\\s*)(\\d{1,4})")
        private val LEADING_NUMBER_REGEX = Regex("^(\\d{1,4})(?:[\\.\\s:-]|$)")

        private val MEDIA_FIELDS = """
            id
            title { romaji english native }
            description(asHtml: false)
            coverImage { extraLarge large }
            bannerImage
            genres
            status
            season
            seasonYear
            episodes
            duration
            format
            studios { edges { isMain node { name } } }
            characters(perPage: 12, sort: [ROLE, RELEVANCE]) {
                edges { role node { name { full } image { large } } }
            }
            recommendations(perPage: 8) {
                nodes { mediaRecommendation { id title { romaji english } coverImage { large } } }
            }
            relations {
                edges { relationType node { id title { romaji english } coverImage { large } } }
            }
            airingSchedule(perPage: 50) { nodes { episode airingAt } }
            streamingEpisodes { title thumbnail url site }
        """.trimIndent()

        private val ANILIST_QUERY = """
            query (${'$'}search: String) {
                Media(search: ${'$'}search, type: ANIME) {
                    $MEDIA_FIELDS
                }
            }
        """.trimIndent()
    }
}
