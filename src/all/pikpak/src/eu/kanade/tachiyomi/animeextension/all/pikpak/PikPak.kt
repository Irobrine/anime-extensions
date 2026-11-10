package eu.kanade.tachiyomi.animeextension.all.pikpak

import android.text.InputType
import android.util.Base64
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.CatalogResponse
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.Meta
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.MetaResponse
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.MetaVideo
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.StreamResponse
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.StremThruUserData
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.TorzData
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.TorzFile
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.TorzResponse
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.head
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Browses the PikPak "My Pack" through StremThru.
 *
 * The Stremio addon gives us the top-level My Pack catalog. For torrent items, the
 * StremThru Torz API additionally exposes each file's path, which lets us use the
 * first path segment (S0, SP, Season 1, ...) as the season name. WebDL items only
 * expose file names, so those fall back to S0x markers in the file names.
 */
@Source
abstract class PikPak :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private val noRedirectClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @Volatile
    private var validatedAccount: String? = null

    private val libraryCache = ConcurrentHashMap<String, Pair<Library, Long>>()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        validateAccount()
        return fetchCatalogs(page, null)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): AnimesPage = getPopularAnime(page)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) return getPopularAnime(page)
        validateAccount()
        return fetchCatalogs(page, query)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val (catalogId, season) = parseSeasonUrl(anime.url)
        if (season != null) {
            anime.fetch_type = FetchType.Episodes
            anime.initialized = true
            return anime
        }

        val library = libraryFor(catalogId)
        if (library != null) {
            if (library.name.isNotBlank()) {
                anime.title = cleanAnimeName(library.name)
            }
            val seasons = library.entries.mapNotNull { it.season }.distinct()
            anime.fetch_type = if (seasons.isNotEmpty()) FetchType.Seasons else FetchType.Episodes
            Log.d(TAG, "details catalog=$catalogId webdl=${isWebDl(catalogId)} entries=${library.entries.size} seasons=$seasons type=${anime.fetch_type}")
        } else {
            anime.fetch_type = FetchType.Episodes
            Log.d(TAG, "details catalog=$catalogId webdl=${isWebDl(catalogId)} no library")
        }
        anime.initialized = true
        return anime
    }

    // =============================== Seasons ==============================

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val (catalogId, season) = parseSeasonUrl(anime.url)
        if (season != null) return emptyList()

        val library = libraryFor(catalogId) ?: return emptyList()
        val seasons = library.entries
            .mapNotNull { it.season }
            .distinct()
            .sortedWith(compareBy<String> { seasonSortKey(it) }.thenBy { it })
        Log.d(TAG, "seasons catalog=$catalogId entries=${library.entries.size} seasons=$seasons")
        if (seasons.isEmpty()) return emptyList()

        return seasons.map { label ->
            SAnime.create().apply {
                title = label
                url = "$catalogId$SEASON_SEPARATOR$label"
                season_number = seasonNumberFromLabel(label)
                fetch_type = FetchType.Episodes
                initialized = true
                thumbnail_url = anime.thumbnail_url
                description = anime.description
                genre = anime.genre
                status = anime.status
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (catalogId, seasonFilter) = parseSeasonUrl(anime.url)
        val library = libraryFor(catalogId) ?: return emptyList()

        val filtered = if (seasonFilter == null) {
            library.entries
        } else {
            library.entries.filter { it.season == seasonFilter }
        }

        val numbered = filtered
            .sortedBy { it.fileName }
            .map { entry -> entry to entry.episode }
        var nextEpisode = (numbered.mapNotNull { it.second }.maxOrNull() ?: 0) + 1
        val resolved = numbered.map { (entry, number) ->
            val episode = number ?: nextEpisode++
            entry to episode
        }

        return resolved
            .sortedWith(
                compareBy<Pair<EpisodeEntry, Int>>(
                    { seasonSortKey(it.first.season.orEmpty()) },
                    { it.second },
                    { it.first.fileName },
                ),
            )
            .map { (entry, episode) ->
                SEpisode.create().apply {
                    episode_number = episode.toFloat()
                    name = formatEpisode(episode)
                    url = entry.streamId
                    date_upload = parseDate(entry.released)
                    preview_url = entry.thumbnail.orEmpty()
                }
            }
    }

    // ============================== Hosters ===============================

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        validateAccount()
        val streams = client.get(
            apiUrl("stream", "other", "${episode.url}.json"),
            cacheControl = CacheControl.FORCE_NETWORK,
        ).parseAs<StreamResponse>().streams.orEmpty()

        val videos = streams.mapNotNull { stream ->
            val streamUrl = stream.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Video(
                videoUrl = resolveVideoUrl(streamUrl),
                videoTitle = stream.name ?: stream.title ?: episode.name,
            )
        }

        return videos.toHosterList()
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty()

    // ============================= Utilities ==============================

    private suspend fun fetchCatalogs(page: Int, query: String?): AnimesPage {
        val catalogs = selectedCatalogs()
        if (catalogs.isEmpty()) return AnimesPage(emptyList(), false)

        return coroutineScope {
            val results = catalogs
                .map { catalog -> async { fetchCatalog(catalog, page, query) } }
                .awaitAll()

            AnimesPage(
                animes = results.flatMap { it.first },
                hasNextPage = results.any { it.second },
            )
        }
    }

    private suspend fun fetchCatalog(catalog: String, page: Int, query: String?): Pair<List<SAnime>, Boolean> {
        val skip = (page - 1) * PAGE_SIZE
        val requestUrl = apiUrlBuilder()
            .addPathSegment("catalog")
            .addPathSegment("other")
            .apply {
                when {
                    query.isNullOrBlank() && skip == 0 -> {
                        addPathSegment("$catalog.json")
                    }
                    query.isNullOrBlank() -> {
                        addPathSegment(catalog)
                        addPathSegment("skip=$skip.json")
                    }
                    else -> {
                        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
                        addPathSegment(catalog)
                        addPathSegment("search=$encodedQuery&skip=$skip.json")
                    }
                }
            }
            .build()

        val metas = client.get(
            requestUrl,
            cacheControl = CacheControl.FORCE_NETWORK,
        ).parseAs<CatalogResponse>().metas.orEmpty()

        val animeList = metas.map { meta ->
            SAnime.create().apply {
                title = cleanAnimeName(meta.name)
                url = meta.id
                description = meta.description.orEmpty()
                thumbnail_url = meta.poster?.takeIf { it.isNotBlank() } ?: FALLBACK_POSTER
                meta.background?.takeIf { it.isNotBlank() }?.let { background_url = it }
            }
        }

        return animeList to (metas.size >= PAGE_SIZE)
    }

    private fun apiUrl(vararg segments: String): HttpUrl = apiUrlBuilder()
        .apply { segments.forEach(::addPathSegment) }
        .build()

    private fun apiUrlBuilder(): HttpUrl.Builder = baseUrl.toHttpUrl().newBuilder()
        .addPathSegment("stremio")
        .addPathSegment("store")
        .addPathSegment(encodedUserData())

    private fun apiHeaders(useAuthorization: Boolean = false): Headers = Headers.Builder()
        .add("X-StremThru-Store-Name", STORE_NAME)
        .apply {
            val value = "Bearer ${account()}"
            if (useAuthorization) {
                add("Authorization", value)
            } else {
                add("X-StremThru-Store-Authorization", value)
            }
        }
        .build()

    private suspend fun validateAccount() {
        val key = "${baseUrl.trimEnd('/')}|${account()}"
        if (validatedAccount == key) return

        try {
            client.get(apiUrl("manifest.json")).use { }
        } catch (e: IOException) {
            throw IOException("Could not sign in to PikPak through StremThru. Check the account and base URL. (${e.message})", e)
        }
        validatedAccount = key
    }

    private fun account(): String = preferences.getString(PREF_ACCOUNT_KEY, null)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: throw Exception("Please enter your PikPak email:password in the extension settings.")

    private fun encodedUserData(): String {
        val userData = StremThruUserData(storeName = STORE_NAME, storeToken = account())
        val blob = json.encodeToString(userData)
        return Base64.encodeToString(blob.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun selectedCatalogs(): List<String> = buildList {
        if (preferences.getBoolean(PREF_TORRENTS_KEY, true)) add(CATALOG_TORRENTS)
        if (preferences.getBoolean(PREF_WEB_DOWNLOADS_KEY, true)) add(CATALOG_WEB_DOWNLOADS)
    }

    private suspend fun fetchMeta(catalogId: String): Meta? {
        validateAccount()
        return client.get(
            apiUrl("meta", "other", "$catalogId.json"),
            cacheControl = CacheControl.FORCE_NETWORK,
        ).parseAs<MetaResponse>().meta
    }

    private suspend fun fetchTorz(catalogId: String): TorzData? {
        val itemId = when {
            catalogId.startsWith("$CATALOG_TORRENTS:") -> catalogId.removePrefix("$CATALOG_TORRENTS:")
            catalogId.startsWith("$CATALOG_WEB_DOWNLOADS:") -> catalogId.removePrefix("$CATALOG_WEB_DOWNLOADS:")
            else -> return null
        }
        if (itemId.isBlank()) return null
        validateAccount()

        return requestTorz(itemId, useAuthorization = false)
            ?: requestTorz(itemId, useAuthorization = true)
    }

    private suspend fun requestTorz(itemId: String, useAuthorization: Boolean): TorzData? {
        val headerName = if (useAuthorization) "Authorization" else "X-StremThru-Store-Authorization"
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("v0")
            .addPathSegment("store")
            .addPathSegment("torz")
            .addPathSegment(itemId)
            .build()

        val data = runCatching {
            client.get(url, apiHeaders(useAuthorization), CacheControl.FORCE_NETWORK)
                .parseAs<TorzResponse>()
                .data
        }.onFailure {
            Log.d(TAG, "torz failed item=$itemId header=$headerName url=$url error=${it.message}")
        }.getOrNull()

        if (data == null) {
            Log.d(TAG, "torz empty item=$itemId header=$headerName url=$url")
        } else {
            Log.d(TAG, "torz item=$itemId header=$headerName files=${data.files?.size ?: 0} name=${data.name}")
        }
        return data
    }

    private suspend fun libraryFor(catalogId: String): Library? {
        val key = "${baseUrl.trimEnd('/')}|${account()}|$catalogId"
        val now = System.currentTimeMillis()
        libraryCache[key]?.let { (library, expiresAt) ->
            if (expiresAt > now) return library
        }

        val torz = torzLibrary(catalogId)
        val library = torz?.takeIf { it.entries.any { entry -> entry.season != null } }
            ?: metaLibrary(catalogId)
            ?: torz?.takeIf { it.entries.isNotEmpty() }

        Log.d(
            TAG,
            "library catalog=$catalogId webdl=${isWebDl(catalogId)} torzEntries=${torz?.entries?.size} " +
                "torzSeasons=${torz?.entries?.mapNotNull { it.season }?.distinct()} " +
                "used=entries:${library?.entries?.size} seasons:${library?.entries?.mapNotNull { it.season }?.distinct()}",
        )
        if (library != null) {
            libraryCache[key] = library to (now + LIBRARY_CACHE_MS)
        }
        return library
    }

    private suspend fun torzLibrary(catalogId: String): Library? {
        val data = fetchTorz(catalogId) ?: return null
        val itemName = data.name.ifBlank { catalogId.substringAfterLast(':') }
        val entries = data.files.orEmpty()
            .filter { file -> file.link.isNotBlank() && isVideoFile(file.name.ifBlank { file.path }.trim()) }
            .map { file ->
                val fileName = file.name.ifBlank { file.path.substringAfterLast('/') }.trim()
                EpisodeEntry(
                    fileName = fileName,
                    streamId = "$catalogId:${encodePathSegment(file.link)}",
                    season = torzSeasonKey(itemName, file),
                    episode = parseEpisodeNumber(fileName),
                )
            }
        return Library(data.name, entries)
    }

    private suspend fun metaLibrary(catalogId: String): Library? {
        val meta = fetchMeta(catalogId) ?: return null
        val entries = meta.videos.orEmpty().map { video ->
            val fileName = videoFileName(video)
            EpisodeEntry(
                fileName = fileName,
                streamId = video.id,
                season = seasonKey(video),
                episode = parseEpisodeNumber(fileName) ?: video.episode,
                released = video.released,
                thumbnail = video.thumbnail,
            )
        }
        return Library(meta.name, entries)
    }

    private fun isWebDl(catalogId: String): Boolean = catalogId.startsWith("$CATALOG_WEB_DOWNLOADS:")

    private fun torzSeasonKey(itemName: String, file: TorzFile): String? {
        val path = file.path.replace('\\', '/').trim('/').ifBlank { file.name }
        val segments = path.split('/').map { it.trim() }.filter { it.isNotBlank() }
        if (segments.size < 2) return null

        val first = segments[0]
        val firstClean = cleanAnimeName(first).lowercase(Locale.US)
        val itemClean = cleanAnimeName(itemName).lowercase(Locale.US)
        val firstLooksLikeSeason = seasonSortKey(first) != Int.MAX_VALUE
        val isItemFolder = firstClean == itemClean ||
            (!firstLooksLikeSeason && firstClean.length >= 3 && itemClean.startsWith(firstClean))

        val season = if (isItemFolder) {
            segments.getOrNull(1)?.takeIf { segments.size >= 3 }
        } else {
            first
        }
        if (season == null && segments.size > 1) {
            Log.d(TAG, "no season path=${file.path} item=$itemName segments=$segments")
        }
        return season
    }

    private fun videoFileName(video: MetaVideo): String = video.title.substringAfterLast('\n').removePrefix("📄 ").trim()

    private fun seasonKey(video: MetaVideo): String? {
        val fileName = videoFileName(video)
        if (SPECIAL_REGEX.containsMatchIn(fileName)) return "SP"
        SEASON_EPISODE_REGEX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()?.let { return "S$it" }
        SEASON_NAME_REGEX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()?.let { return "S$it" }
        SEASON_ONLY_REGEX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()?.let { return "S$it" }
        return video.season?.let { "S$it" }
    }

    private fun parseEpisodeNumber(fileName: String): Int? {
        SEASON_EPISODE_REGEX.find(fileName)?.groupValues?.get(2)?.toIntOrNull()?.let { return it }
        SPECIAL_REGEX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        EPISODE_WORD_REGEX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        BRACKET_EPISODE_REGEX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        BRACKET_EXPLICIT_EPISODE_REGEX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        PAREN_EPISODE_REGEX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        PAREN_EXPLICIT_EPISODE_REGEX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        val parseText = VIDEO_TYPE_REGEX.replace(
            PAREN_TAG_REGEX.replace(BRACKET_TAG_REGEX.replace(fileName, " "), " "),
            " ",
        )

        DASH_EPISODE_REGEX.find(parseText)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        EXPLICIT_EPISODE_REGEX.find(parseText)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        DECIMAL_EPISODE_REGEX.find(parseText)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        SPACE_EPISODE_REGEX.find(parseText)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        BARE_EPISODE_REGEX.find(parseText)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun seasonSortKey(season: String): Int {
        if (season.equals("SP", ignoreCase = true) || season.contains("special", ignoreCase = true)) return 0
        SEASON_NAME_REGEX.find(season)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        SEASON_EPISODE_REGEX.find(season)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        SEASON_ONLY_REGEX.find(season)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return Int.MAX_VALUE
    }

    private fun seasonNumberFromLabel(season: String): Double {
        val key = seasonSortKey(season)
        return if (key == Int.MAX_VALUE) 0.0 else key.toDouble()
    }

    private fun parseSeasonUrl(url: String): Pair<String, String?> {
        val separator = url.lastIndexOf(SEASON_SEPARATOR)
        if (separator == -1) return url to null

        val season = url.substring(separator + SEASON_SEPARATOR.length)
        if (season.isBlank()) return url to null
        return url.substring(0, separator) to season
    }

    private fun cleanAnimeName(name: String): String {
        if (name.isBlank()) return name

        var cleaned = BRACKET_TAG_REGEX.replace(name) { match ->
            val content = match.groupValues[1].trim()
            if (isVideoTypeTag(content) || isIdTag(content)) "" else match.value
        }

        while (true) {
            val match = LEADING_BRACKET_REGEX.find(cleaned) ?: break
            val rest = cleaned.substring(match.range.last + 1).trimStart(' ', '-', '_', '.')
            if (rest.isBlank()) break
            cleaned = rest
        }

        cleaned = cleaned.trim()
        cleaned = VIDEO_EXTENSION_REGEX.replace(cleaned, "")
        cleaned = WHITESPACE_REGEX.replace(cleaned, " ").trim(' ', '-', '_', '.')
        return cleaned.ifBlank { name.trim() }
    }

    private fun isVideoTypeTag(tag: String): Boolean = VIDEO_TYPE_REGEX.containsMatchIn(tag)

    private fun isIdTag(tag: String): Boolean = ID_TAG_REGEX.matches(tag.trim())

    private fun isVideoFile(name: String): Boolean = VIDEO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    private fun encodePathSegment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun formatEpisode(episode: Int): String = String.format(Locale.US, "E%02d", episode)

    private fun parseDate(date: String?): Long {
        val value = date?.take(19) ?: return 0L
        val parsed = synchronized(DATE_FORMATTER) { DATE_FORMATTER.tryParse(value) }
        return parsed.takeIf { it > 0L } ?: 0L
    }

    /**
     * StremThru returns a redirect to a short-lived PikPak link. Resolve it here so the
     * player never sees the account credentials embedded in the addon URL.
     */
    private suspend fun resolveVideoUrl(url: String): String = runCatching {
        noRedirectClient.head(url, cacheControl = CacheControl.FORCE_NETWORK, ensureSuccess = false).use { response ->
            response.header("Location")
                ?.takeIf { it.startsWith("http", ignoreCase = true) }
                ?: url
        }
    }.getOrDefault(url)

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_ACCOUNT_KEY
            title = "PikPak account"
            summary = "Email:password. Sent only to the configured StremThru instance."
            setDefaultValue("")
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, (newValue as String).trim()).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TORRENTS_KEY
            title = "Show torrent downloads"
            summary = "Items in My Pack that were added from magnet links."
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_WEB_DOWNLOADS_KEY
            title = "Show web downloads"
            summary = "Files in My Pack that were not added from magnet links."
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }

    private data class Library(
        val name: String,
        val entries: List<EpisodeEntry>,
    )

    private data class EpisodeEntry(
        val fileName: String,
        val streamId: String,
        val season: String?,
        val episode: Int?,
        val released: String? = null,
        val thumbnail: String? = null,
    )

    companion object {
        private const val TAG = "PikPak"
        private const val STORE_NAME = "pikpak"

        private const val PREF_ACCOUNT_KEY = "pikpak_account"
        private const val PREF_TORRENTS_KEY = "show_torrents"
        private const val PREF_WEB_DOWNLOADS_KEY = "show_web_downloads"

        private const val CATALOG_TORRENTS = "st:store:pp"
        private const val CATALOG_WEB_DOWNLOADS = "st:store:pp-webdl"

        private const val SEASON_SEPARATOR = "|"

        private val SEASON_EPISODE_REGEX = Regex("(?i)S(\\d{1,2})[\\s._-]*E(\\d{1,4})")
        private val SEASON_NAME_REGEX = Regex("(?i)Season\\s*(\\d{1,2})")
        private val SEASON_ONLY_REGEX = Regex("(?i)(?:^|[^A-Za-z0-9])S(\\d{1,2})(?![0-9])")
        private val SPECIAL_REGEX = Regex("(?i)(?:^|[^A-Za-z0-9])SP(\\d{1,3})(?![A-Za-z0-9])")
        private val EPISODE_WORD_REGEX = Regex("(?i)Episode\\s*(\\d{1,4})")
        private val EXPLICIT_EPISODE_REGEX = Regex("(?i)(?<![0-9])E(\\d{1,4})(?![0-9A-Fa-f])")
        private val DASH_EPISODE_REGEX = Regex("(?i)-\\s*(\\d{1,3})(?:v\\d+)?(?=\\s|$|\\[|\\(|\\.|_)")
        private val DECIMAL_EPISODE_REGEX = Regex("(?i)(?:^|[\\s._-])(\\d{1,3})\\.5(?=[\\s._-]|$|\\[|\\()")
        private val SPACE_EPISODE_REGEX = Regex(
            "(?i)(?:^|[\\s._-])(?<!\\b\\d\\.)(\\d{1,3})(?:v\\d+)?(?!\\.\\d)(?=[\\s._-]|$|\\[|\\()",
        )
        private val BARE_EPISODE_REGEX = Regex("(?i)(?:^|[\\s._-])(?<!\\d\\.)(\\d{1,3})(?:v\\d+)?\\.[a-z0-9]{2,4}$")
        private val BRACKET_EPISODE_REGEX = Regex("\\[(\\d{1,3})(?:v\\d+)?]")
        private val BRACKET_EXPLICIT_EPISODE_REGEX = Regex("(?i)\\[E(\\d{1,3})]")
        private val PAREN_EPISODE_REGEX = Regex("\\((\\d{1,3})(?:v\\d+)?\\)")
        private val PAREN_EXPLICIT_EPISODE_REGEX = Regex("(?i)\\(E(\\d{1,3})\\)")
        private val PAREN_TAG_REGEX = Regex("\\(([^)]*)\\)")

        private val BRACKET_TAG_REGEX = Regex("\\[([^\\]]+)]")
        private val LEADING_BRACKET_REGEX = Regex("^\\s*\\[[^\\]]+]\\s*")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val ID_TAG_REGEX = Regex("(?i)(?=.*\\d)[0-9a-f]{6,16}")
        private val VIDEO_TYPE_REGEX = Regex(
            "(?i)\\b(?:" +
                "\\d{3,4}p|4k|8k|" +
                "web[- ]?dl|web[- ]?rip|web|blu[- ]?ray|bd[- ]?rip|br[- ]?rip|hd[- ]?rip|dvd[- ]?rip|dvd|hdtv|tv[- ]?rip|remux|" +
                "h\\.?26[45]|x\\.?26[45]|hevc|avc\\d?|av1|vp9|" +
                "hdr10(?:\\+|plus)?|hdr|dolby[- ]?vision|dv|" +
                "aac(?:\\d\\.\\d)?|ac3(?:\\d\\.\\d)?|eac3(?:\\d\\.\\d)?|dts(?:[- ]?hd)?(?:\\d\\.\\d)?|truehd|atmos|flac|opus|mp3|ddp?(?:\\d\\.\\d)?|" +
                "(?:8|10|12)[- ]?bit|" +
                "dual[- ]?audio|multi[- ]?audio|multi[- ]?subs?|subs?|subbed|dubbed|softsub|hardsub|" +
                "eng(?:lish)?[- ]?sub|baha|cr|nf|amzn" +
                ")\\b",
        )
        private val VIDEO_EXTENSION_REGEX = Regex("(?i)\\.(?:mp4|mkv|avi|flv|mov|webm|wmv|m4v|ts|m2ts|mpg|mpeg|3gp|ogv)$")
        private val VIDEO_EXTENSIONS = listOf(
            ".mp4", ".mkv", ".avi", ".flv", ".mov", ".webm", ".wmv", ".m4v",
            ".ts", ".m2ts", ".mpg", ".mpeg", ".3gp", ".ogv",
        )

        private const val LIBRARY_CACHE_MS = 2_000L
        private const val PAGE_SIZE = 100
        private const val FALLBACK_POSTER = "https://mypikpak.com/android-chrome-192x192.png"

        private val DATE_FORMATTER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
