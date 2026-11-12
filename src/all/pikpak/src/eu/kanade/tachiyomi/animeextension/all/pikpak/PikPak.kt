package eu.kanade.tachiyomi.animeextension.all.pikpak

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.PikPakFile
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Direct PikPak source.
 *
 * The extension logs in with the configured PikPak email:password, lists "My Pack",
 * resolves season folders from the real PikPak paths, and generates signed PikPak
 * playback links itself. It does not use StremThru.
 */
@Source
abstract class PikPak :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    private val libraryCache = ConcurrentHashMap<String, Pair<Library, Long>>()
    private val catalogCache = ConcurrentHashMap<String, Pair<List<CatalogItem>, Long>>()
    private val extraFolderCache = ConcurrentHashMap<String, Pair<List<CatalogItem>?, Long>>()
    private val seasonProbeCache = ConcurrentHashMap<String, Pair<Boolean, Long>>()

    @Volatile
    private var directClientAccount: String? = null

    @Volatile
    private var directClient: PikPakClient? = null

    @Volatile
    private var metadataClientKey: String? = null

    @Volatile
    private var metadataClient: MetadataClient? = null

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        Log.d(TAG, "popular page=$page build=$BUILD_TAG")
        return catalogPage(myPack(), page)
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

        Log.d(TAG, "search page=$page query=$query")
        val filtered = myPack().filter { it.name.contains(query, ignoreCase = true) }
        return catalogPage(filtered, page)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        Log.d(TAG, "details start url=${anime.url} build=$BUILD_TAG")
        val (itemId, season) = parseSeasonUrl(anime.url)
        if (season != null) {
            anime.fetch_type = FetchType.Episodes
            anime.initialized = true
            return anime
        }

        val library = libraryFor(itemId)
        if (library.name.isNotBlank()) {
            anime.title = cleanAnimeName(library.name)
        }
        val seasons = orderedSeasons(library.entries)
        if (preferences.getBoolean(PREF_METADATA_KEY, true)) {
            // The first season's cover represents the whole show.
            val metadata = fetchMetadata(library.name, seasons.firstOrNull())
            if (metadata != null) applyMetadata(anime, metadata)
        }
        anime.initialized = true
        Log.d(TAG, "details item=$itemId seasons=$seasons type=${anime.fetch_type}")
        return anime
    }

    // =============================== Seasons ==============================

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        Log.d(TAG, "season list start url=${anime.url} build=$BUILD_TAG")
        val (itemId, season) = parseSeasonUrl(anime.url)
        if (season != null) return emptyList()

        val library = libraryFor(itemId)
        val seasons = orderedSeasons(library.entries)
        Log.d(TAG, "seasons item=$itemId seasons=$seasons")
        if (seasons.isEmpty()) {
            return listOf(
                SAnime.create().apply {
                    title = SYNTHETIC_SEASON_TITLE
                    url = "$itemId$SEASON_SEPARATOR$SYNTHETIC_SEASON_ID"
                    season_number = 1.0
                    fetch_type = FetchType.Episodes
                    initialized = true
                    thumbnail_url = anime.thumbnail_url
                    description = anime.description
                    genre = anime.genre
                    status = anime.status
                },
            )
        }

        val metadataEnabled = preferences.getBoolean(PREF_METADATA_KEY, true)
        val parentTitle = cleanAnimeName(library.name)
        return coroutineScope {
            seasons.map { label ->
                async {
                    SAnime.create().apply {
                        title = seasonDisplayTitle(parentTitle, label)
                        url = "$itemId$SEASON_SEPARATOR$label"
                        season_number = seasonNumberFromLabel(label)
                        fetch_type = FetchType.Episodes
                        initialized = true
                        thumbnail_url = anime.thumbnail_url
                        description = anime.description
                        genre = anime.genre
                        status = anime.status
                        if (metadataEnabled) {
                            val metadata = fetchMetadata(library.name, label)
                            if (metadata != null) applyMetadata(this, metadata)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        Log.d(TAG, "episode list start url=${anime.url} build=$BUILD_TAG")
        val (itemId, seasonFilter) = parseSeasonUrl(anime.url)
        val library = libraryFor(itemId)

        val metadata = if (preferences.getBoolean(PREF_METADATA_KEY, true)) {
            val metadataSeason = seasonFilter?.takeUnless { it == SYNTHETIC_SEASON_ID }
            fetchMetadata(library.name, metadataSeason)
        } else {
            null
        }

        val filtered = if (seasonFilter == null || seasonFilter == SYNTHETIC_SEASON_ID) {
            library.entries
        } else {
            library.entries.filter { it.season == seasonFilter }
        }

        val numbered = filtered
            .sortedBy { it.fileName }
            .map { entry -> entry to (entry.episode ?: metadata?.resolveEpisodeNumber(entry.fileName)) }
        var nextEpisode = (numbered.mapNotNull { it.second }.maxOrNull() ?: 0) + 1
        val resolved = numbered.map { (entry, number) ->
            val episode = number ?: nextEpisode++
            entry to episode
        }

        Log.d(TAG, "episodes item=$itemId season=$seasonFilter count=${resolved.size}")
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
                    url = entry.fileId
                    date_upload = parseDate(entry.released)
                    preview_url = entry.thumbnail.orEmpty()
                }
            }
    }

    // ============================== Hosters ===============================

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val fileId = episode.url
        if (fileId.startsWith("{") || fileId.contains(':')) {
            throw IOException("This episode list is from an older version. Refresh the anime.")
        }

        val client = directClient()
            ?: throw IOException("Please enter your PikPak email:password in the extension settings.")

        val url = try {
            client.getStreamUrl(fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "direct stream failed file=$fileId error=${e.message}")
            throw IOException("Could not generate a PikPak playback link: ${e.message}", e)
        } ?: throw IOException("PikPak did not return a playback link for this file.")

        Log.d(TAG, "direct stream file=$fileId ok")
        return listOf(
            Video(
                videoUrl = url,
                videoTitle = episode.name,
            ),
        ).toHosterList()
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList.orEmpty()

    // ============================== Catalog ===============================

    private suspend fun myPack(): List<CatalogItem> {
        val account = account()
        val extras = extraFolders()
        val key = "$account|${extras.joinToString("|")}"
        val now = System.currentTimeMillis()
        catalogCache[key]?.let { (items, expiresAt) ->
            if (expiresAt > now) return items
        }

        val client = directClient()
            ?: throw IOException("Please enter your PikPak email:password in the extension settings.")

        val items = try {
            client.listMyPack()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "my pack failed: ${e.message}")
            throw IOException("Could not list PikPak My Pack: ${e.message}", e)
        }

        val pinned = extras.flatMap { resolveExtraFolders(client, it) }
            .distinctBy { it.id }
            .mapIndexed { index, item -> item.copy(order = index) }
        val pinnedIds = pinned.map { it.id }.toSet()
        val combined = pinned + filterCatalog(items)
            .map { it.toCatalogItem() }
            .filterNot { it.id in pinnedIds }
            .sortedWith(CATALOG_COMPARATOR)

        Log.d(TAG, "my pack items=${items.size} extras=${pinned.size} build=$BUILD_TAG")
        catalogCache[key] = combined to (now + CATALOG_CACHE_MS)
        return combined
    }

    /**
     * Expands a configured extra folder into the anime folders it contains, so the
     * catalog lists those directly. A folder without sub-folders is kept as one entry.
     */
    private suspend fun resolveExtraFolders(client: PikPakClient, input: String): List<CatalogItem> {
        val now = System.currentTimeMillis()
        extraFolderCache[input]?.let { (items, expiresAt) ->
            if (expiresAt > now) return items.orEmpty()
        }

        val items = try {
            val folder = client.resolveFolder(input)
            if (folder == null) {
                emptyList()
            } else {
                val children = client.listChildren(folder.id)
                    .filter { it.kind == KIND_FOLDER && it.id.isNotBlank() }
                    .map { it.toCatalogItem(pinned = true) }
                children.ifEmpty { listOf(folder.toCatalogItem(pinned = true)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "extra folder '$input' error=${e.message}")
            emptyList()
        }

        extraFolderCache[input] = items.ifEmpty { null } to
            (now + if (items.isNotEmpty()) EXTRA_FOLDER_CACHE_MS else EXTRA_FOLDER_MISS_MS)
        Log.d(TAG, "extra folder '$input' anime=${items.size}")
        return items
    }

    private fun extraFolders(): List<String> = preferences.getString(PREF_EXTRA_FOLDERS, "")
        .orEmpty()
        .split(',', ';', '\n', '\r')
        .map { it.trim() }
        .filter { it.isNotBlank() }

    private fun PikPakFile.toCatalogItem(pinned: Boolean = false) = CatalogItem(
        id = id,
        name = name,
        isFolder = kind == KIND_FOLDER,
        isTorrent = params?.url?.startsWith("magnet:", ignoreCase = true) == true,
        isPinned = pinned,
        createdTime = createdTime,
        thumbnail = thumbnailLink,
    )

    private fun filterCatalog(items: List<PikPakFile>): List<PikPakFile> {
        val showTorrents = preferences.getBoolean(PREF_TORRENTS_KEY, true)
        val showWebDownloads = preferences.getBoolean(PREF_WEB_DOWNLOADS_KEY, true)

        return items.filter { item ->
            val isTorrent = item.params?.url?.startsWith("magnet:", ignoreCase = true) == true
            if (isTorrent) showTorrents else showWebDownloads
        }
    }

    private suspend fun catalogPage(items: List<CatalogItem>, page: Int): AnimesPage {
        val start = (page - 1) * PAGE_SIZE
        if (start >= items.size) return AnimesPage(emptyList(), false)

        val end = minOf(start + PAGE_SIZE, items.size)
        val pageItems = items.subList(start, end)

        val client = directClient()
        val foldersWithChildren = if (client == null) {
            emptyMap()
        } else {
            val semaphore = Semaphore(FOLDER_PROBE_CONCURRENCY)
            coroutineScope {
                pageItems.filter { it.isFolder }.map { item ->
                    async {
                        item.id to semaphore.withPermit { folderHasSubfolders(client, item.id) }
                    }
                }.awaitAll().toMap()
            }
        }

        val animes = pageItems.map { item ->
            val hasSeasons = item.isFolder && foldersWithChildren[item.id] != false
            SAnime.create().apply {
                title = cleanAnimeName(item.name).ifBlank { item.id }
                url = item.id
                fetch_type = if (hasSeasons) FetchType.Seasons else FetchType.Episodes
                description = when {
                    item.isPinned -> "PikPak extra folder • $BUILD_TAG"
                    item.isTorrent -> "PikPak torrent • $BUILD_TAG"
                    else -> "PikPak file • $BUILD_TAG"
                }
                thumbnail_url = item.thumbnail?.takeIf { it.isNotBlank() } ?: FALLBACK_POSTER
            }
        }

        return AnimesPage(animes, end < items.size)
    }

    /**
     * A folder without sub-folders is a flat anime: show its episodes directly
     * instead of a synthetic season. Probe failures fall back to the seasons screen.
     */
    private suspend fun folderHasSubfolders(client: PikPakClient, folderId: String): Boolean {
        val now = System.currentTimeMillis()
        seasonProbeCache[folderId]?.let { (has, expiresAt) ->
            if (expiresAt > now) return has
        }

        val has = try {
            client.listChildren(folderId).any { it.kind == KIND_FOLDER }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "season probe failed folder=$folderId error=${e.message}")
            true
        }

        seasonProbeCache[folderId] = has to (now + SEASON_PROBE_CACHE_MS)
        return has
    }

    // ============================== Library ===============================

    private suspend fun libraryFor(itemId: String): Library {
        val key = "${account()}|$itemId"
        val now = System.currentTimeMillis()
        libraryCache[key]?.let { (library, expiresAt) ->
            if (expiresAt > now) return library
        }

        val library = directLibrary(itemId)
        libraryCache[key] = library to (now + LIBRARY_CACHE_MS)
        return library
    }

    private suspend fun directLibrary(itemId: String): Library {
        val client = directClient()
            ?: throw IOException("Please enter your PikPak email:password in the extension settings.")

        val listing = try {
            client.list(itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "direct failed item=$itemId error=${e.message}")
            throw IOException("Could not list PikPak item: ${e.message}", e)
        }

        val entries = listing.files
            .filter { file -> isVideoFile(file.name.ifBlank { file.path.substringAfterLast('/') }) }
            .map { file ->
                val fileName = file.name.ifBlank { file.path.substringAfterLast('/') }
                EpisodeEntry(
                    fileName = fileName,
                    fileId = file.id,
                    season = pathSeasonKey(listing.name, file.path, fileName) ?: seasonKeyFromFileName(fileName),
                    episode = parseEpisodeNumber(fileName),
                    released = file.createdTime,
                    thumbnail = file.thumbnailLink,
                )
            }

        val withFileSeasons = assignFileNameSeasons(entries, listing.name)
        val finalEntries = withOtherSeason(withFileSeasons)
        if (finalEntries.none { it.season != null }) {
            Log.d(TAG, "no seasons item=$itemId paths=${listing.files.take(8).map { it.path }}")
        }
        Log.d(
            TAG,
            "direct item=$itemId files=${listing.files.size} videos=${finalEntries.size} " +
                "seasons=${finalEntries.mapNotNull { it.season }.distinct()}",
        )
        return Library(listing.name, finalEntries)
    }

    @Synchronized
    private fun metadataClient(): MetadataClient {
        val key = preferences.getString(PREF_TMDB_KEY, "").orEmpty().trim()
        metadataClient?.takeIf { metadataClientKey == key }?.let { return it }

        return MetadataClient(client, key).also {
            metadataClient = it
            metadataClientKey = key
        }
    }

    private suspend fun fetchMetadata(parentTitle: String, season: String?): AnimeMetadata? {
        val parent = metadataSearchTitle(parentTitle)
        if (parent.isBlank()) return null

        val queries = metadataQueries(parent, season)
        val tmdbSeason = season?.let { detectedSeasonNumber(it) }
        queries.forEach { query ->
            val metadata = metadataClient().fetch(query, tmdbSeason)
            if (metadata != null) {
                Log.d(TAG, "metadata query=$query season=$season found=true")
                return metadata
            }
        }

        Log.d(TAG, "metadata season=$season found=false tried=$queries")
        return null
    }

    private fun metadataQueries(parent: String, season: String?): List<String> {
        if (season.isNullOrBlank() || season == SYNTHETIC_SEASON_ID) return listOf(parent)

        val number = detectedSeasonNumber(season)
        val queries = mutableListOf<String>()
        when {
            isSpecialSeason(season) -> queries += "$parent Specials"
            number == 0 -> queries += "$parent Season 0"
            number != null -> {
                queries += "$parent Season $number"
                ordinalSeasonSuffix(number)?.let { queries += "$parent $it" }
            }
            else -> {
                val label = metadataSearchTitle(season).ifBlank { cleanAnimeName(season) }
                queries += "$parent $label"
                pluralVariant(label)?.let { queries += "$parent $it" }
            }
        }
        // Last resort: the parent entry, which still gives a cover and description.
        queries += parent
        return queries.distinct()
    }

    private fun pluralVariant(label: String): String? {
        if (label.isBlank() || !label.last().isLetter()) return null
        return if (label.endsWith("s", ignoreCase = true)) {
            label.dropLast(1).takeIf { it.isNotBlank() && !it.endsWith("s", ignoreCase = true) }
        } else {
            "${label}s"
        }
    }

    private fun ordinalSeasonSuffix(number: Int): String? {
        if (number < 2) return null
        val suffix = when {
            number % 100 in 11..13 -> "th"
            number % 10 == 1 -> "st"
            number % 10 == 2 -> "nd"
            number % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$number$suffix Season"
    }

    private fun applyMetadata(anime: SAnime, metadata: AnimeMetadata) {
        metadata.coverUrl?.takeIf { it.isNotBlank() }?.let { anime.thumbnail_url = it }
        metadata.bannerUrl?.takeIf { it.isNotBlank() }?.let { anime.background_url = it }
        anime.author = metadata.studios.joinToString(", ").takeIf { it.isNotBlank() }
        anime.genre = metadata.genres.joinToString(", ").takeIf { it.isNotBlank() }
        anime.status = mapStatus(metadata.status)
        anime.description = buildMetadataDescription(metadata)
    }

    private fun buildMetadataDescription(metadata: AnimeMetadata): String {
        val parts = mutableListOf<String>()
        metadata.description?.takeIf { it.isNotBlank() }?.let { parts += it.trim() }

        if (metadata.characters.isNotEmpty()) {
            parts += buildString {
                append("Characters:")
                metadata.characters.forEach { character ->
                    append("\n• ").append(character.name)
                    character.detail?.takeIf { it.isNotBlank() }?.let {
                        append(" (").append(it).append(")")
                    }
                }
            }
        }

        if (metadata.recommendations.isNotEmpty()) {
            parts += buildString {
                append("Recommendations:")
                metadata.recommendations.forEach { append("\n• ").append(it.title) }
            }
        }

        if (metadata.related.isNotEmpty()) {
            parts += buildString {
                append("Related:")
                metadata.related.forEach { suggestion ->
                    append("\n• ").append(suggestion.title)
                    suggestion.label?.takeIf { it.isNotBlank() }?.let {
                        append(" (").append(it).append(")")
                    }
                }
            }
        }

        return parts.joinToString("\n\n")
    }

    private fun mapStatus(status: String?): Int = when (status?.uppercase(Locale.US)) {
        "FINISHED", "ENDED" -> SAnime.COMPLETED
        "RELEASING", "RETURNING SERIES" -> SAnime.ONGOING
        "NOT_YET_RELEASED", "PLANNED", "IN PRODUCTION" -> SAnime.ONGOING
        "CANCELLED", "CANCELED" -> SAnime.CANCELLED
        "HIATUS" -> SAnime.ON_HIATUS
        else -> SAnime.UNKNOWN
    }

    @Synchronized
    private fun directClient(): PikPakClient? {
        val account = account()
        directClient?.takeIf { directClientAccount == account }?.let { return it }

        val parts = account.split(':', limit = 2)
        if (parts.size < 2 || parts[1].isBlank()) return null

        return PikPakClient(client, parts[0], parts[1], tokenStore(), refreshTokenSeed()).also {
            directClient = it
            directClientAccount = account
        }
    }

    private fun refreshTokenSeed(): String? {
        val raw = preferences.getString(PREF_REFRESH_TOKEN_INPUT, "").orEmpty().trim()
        if (raw.isBlank()) return null

        val token = parseRefreshToken(raw)
        if (token != null) {
            Log.d(TAG, "refresh token seed length=${token.length} prefix=${token.take(2)}")
        } else {
            Log.d(TAG, "refresh token seed could not be parsed length=${raw.length}")
        }
        return token
    }

    private fun parseRefreshToken(raw: String): String? {
        val decoded = if (raw.contains('{') || raw.contains("%7B", ignoreCase = true) || raw.contains("refresh_token=", ignoreCase = true)) {
            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        } else {
            raw
        }

        REFRESH_TOKEN_JSON_REGEX.find(decoded)?.groupValues?.get(1)?.let { return it }

        val tokenKey = "refresh_token="
        val keyIndex = decoded.lowercase(Locale.US).indexOf(tokenKey)
        if (keyIndex >= 0) {
            return decoded.substring(keyIndex + tokenKey.length)
                .substringBefore('&')
                .substringBefore(';')
                .trim('"', ' ', '\'')
                .takeIf { it.isNotBlank() }
        }

        return decoded.takeIf { it.length >= 20 && !it.contains(' ') }
    }

    private fun tokenStore(): PikPakClient.TokenStore = object : PikPakClient.TokenStore {
        override fun load(): PikPakClient.StoredTokens? {
            val version = preferences.getString(PREF_TOKEN_CLIENT, "").orEmpty()
            if (version != TOKEN_STORE_VERSION) return null

            val access = preferences.getString(PREF_ACCESS_TOKEN, "").orEmpty()
            val refresh = preferences.getString(PREF_REFRESH_TOKEN, "").orEmpty()
            if (access.isBlank() || refresh.isBlank()) return null

            return PikPakClient.StoredTokens(
                accessToken = access,
                refreshToken = refresh,
                userId = preferences.getString(PREF_USER_ID, "").orEmpty(),
                expiresAt = preferences.getLong(PREF_TOKEN_EXPIRES_AT, 0L),
            )
        }

        override fun save(tokens: PikPakClient.StoredTokens) {
            preferences.edit()
                .putString(PREF_TOKEN_CLIENT, TOKEN_STORE_VERSION)
                .putString(PREF_ACCESS_TOKEN, tokens.accessToken)
                .putString(PREF_REFRESH_TOKEN, tokens.refreshToken)
                .putString(PREF_USER_ID, tokens.userId)
                .putLong(PREF_TOKEN_EXPIRES_AT, tokens.expiresAt)
                .commit()
        }

        override fun clearRefreshSeed() {
            preferences.edit().remove(PREF_REFRESH_TOKEN_INPUT).commit()
        }
    }

    private fun clearStoredTokens() {
        preferences.edit()
            .remove(PREF_TOKEN_CLIENT)
            .remove(PREF_ACCESS_TOKEN)
            .remove(PREF_REFRESH_TOKEN)
            .remove(PREF_USER_ID)
            .remove(PREF_TOKEN_EXPIRES_AT)
            .commit()
    }

    private fun account(): String = preferences.getString(PREF_ACCOUNT_KEY, null)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: throw Exception("Please enter your PikPak email:password in the extension settings.")

    // ============================= Utilities ==============================

    private fun pathSeasonKey(itemName: String, rawPath: String, fallbackName: String): String? {
        val path = rawPath.replace('\\', '/').trim('/').ifBlank { fallbackName }
        val segments = path.split('/').map { it.trim() }.filter { it.isNotBlank() }
        if (segments.size < 2) return null

        val folders = segments.dropLast(1).toMutableList()
        val itemClean = cleanAnimeName(itemName).lowercase(Locale.US)

        while (folders.isNotEmpty()) {
            val first = folders[0]
            val firstClean = cleanAnimeName(first).lowercase(Locale.US)
            val firstLooksLikeSeason = seasonSortKey(first) != Int.MAX_VALUE
            val isItemFolder = firstClean == itemClean ||
                (!firstLooksLikeSeason && firstClean.length >= 3 && itemClean.startsWith(firstClean))
            if (!isItemFolder) break
            folders.removeAt(0)
        }

        val season = folders.firstOrNull { seasonSortKey(it) != Int.MAX_VALUE }
            ?: folders.firstOrNull { !isVideoTypeTag(it) }

        if (season == null && folders.isNotEmpty()) {
            Log.d(TAG, "no season path=$rawPath fallback=$fallbackName item=$itemName folders=$folders")
        }
        return season
    }

    private fun assignFileNameSeasons(entries: List<EpisodeEntry>, itemName: String): List<EpisodeEntry> {
        if (entries.any { it.season != null }) return entries

        val itemClean = cleanAnimeName(itemName).lowercase(Locale.US)
        val prefixes = entries
            .mapNotNull { fileNameSeasonPrefix(it.fileName) }
            .distinctBy { it.lowercase(Locale.US) }
            .filterNot { it.lowercase(Locale.US) == itemClean }

        if (prefixes.size < 2) return entries

        Log.d(TAG, "file-name season fallback item=$itemName prefixes=$prefixes")
        return entries.map { entry ->
            val prefix = fileNameSeasonPrefix(entry.fileName) ?: return@map entry
            if (prefix.lowercase(Locale.US) == itemClean) entry else entry.copy(season = prefix)
        }
    }

    private fun fileNameSeasonPrefix(fileName: String): String? {
        val base = fileName.substringBeforeLast('.')
        val markers = listOf(
            SEASON_EPISODE_REGEX,
            EPISODE_WORD_REGEX,
            DASH_EPISODE_REGEX,
            BRACKET_EPISODE_REGEX,
            BRACKET_EXPLICIT_EPISODE_REGEX,
            PAREN_EPISODE_REGEX,
            PAREN_EXPLICIT_EPISODE_REGEX,
            EXPLICIT_EPISODE_REGEX,
            SPACE_EPISODE_REGEX,
        )

        val index = markers.mapNotNull { it.find(base)?.range?.first }.minOrNull() ?: return null
        if (index <= 0) return null

        val prefix = cleanAnimeName(base.substring(0, index)).trim()
        return prefix.takeIf { it.isNotBlank() && !isVideoTypeTag(it) }
    }

    private fun withOtherSeason(entries: List<EpisodeEntry>): List<EpisodeEntry> {
        if (entries.none { it.season != null }) return entries
        return entries.map { entry ->
            if (entry.season == null) entry.copy(season = OTHER_SEASON) else entry
        }
    }

    private fun seasonKeyFromFileName(fileName: String): String? {
        if (SPECIAL_REGEX.containsMatchIn(fileName)) return "SP"
        SEASON_EPISODE_REGEX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()?.let { return "S$it" }
        SEASON_NAME_REGEX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()?.let { return "S$it" }
        SEASON_ONLY_REGEX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()?.let { return "S$it" }
        return null
    }

    private fun seasonSortKey(season: String): Int {
        if (isSpecialSeason(season)) return 0
        detectedSeasonNumber(season)?.let { return it }
        return Int.MAX_VALUE
    }

    private fun detectedSeasonNumber(season: String): Int? {
        SEASON_NAME_REGEX.find(season)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        SEASON_ONLY_REGEX.find(season)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        SEASON_EPISODE_REGEX.find(season)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    private fun isSpecialSeason(season: String): Boolean = season.equals("SP", ignoreCase = true) || season.contains("special", ignoreCase = true)

    private fun orderedSeasons(entries: List<EpisodeEntry>): List<String> {
        val unique = entries.mapNotNull { it.season }.distinct()
        val numbered = unique.filter { detectedSeasonNumber(it) != null }
            .sortedBy { detectedSeasonNumber(it) ?: 0 }
        val named = unique.filter { detectedSeasonNumber(it) == null && it != OTHER_SEASON }
        val ordered = numbered + named
        return if (unique.contains(OTHER_SEASON)) ordered + OTHER_SEASON else ordered
    }

    private fun seasonDisplayTitle(parent: String, label: String): String {
        val base = parent.ifBlank { label }
        val number = detectedSeasonNumber(label)
        return when {
            number != null -> "$base ${String.format(Locale.US, "S%02d", number)}"
            isSpecialSeason(label) -> "$base Specials"
            else -> "$base ${cleanAnimeName(label).ifBlank { label }}"
        }
    }

    private fun seasonNumberFromLabel(season: String): Double {
        val key = seasonSortKey(season)
        return if (key == Int.MAX_VALUE) -1.0 else key.toDouble()
    }

    private fun parseSeasonUrl(url: String): Pair<String, String?> {
        val separator = url.lastIndexOf(SEASON_SEPARATOR)
        if (separator == -1) return url to null

        val season = url.substring(separator + SEASON_SEPARATOR.length)
        if (season.isBlank()) return url to null
        return url.substring(0, separator) to season
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

    private fun formatEpisode(episode: Int): String = String.format(Locale.US, "E%02d", episode)

    private fun parseDate(date: String?): Long {
        val value = date?.take(19) ?: return 0L
        val parsed = synchronized(DATE_FORMATTER) { DATE_FORMATTER.tryParse(value) }
        return parsed.takeIf { it > 0L } ?: 0L
    }

    /**
     * Title used for API lookups: drops release noise and years (e.g. "(2020)", "[TV]")
     * that break AniList/TMDB matching, while the catalog keeps the original cleaned name.
     */
    private fun metadataSearchTitle(name: String): String {
        var cleaned = cleanAnimeName(name)

        cleaned = PAREN_TAG_REGEX.replace(cleaned) { match ->
            if (isMetadataNoise(match.groupValues[1])) "" else match.value
        }
        cleaned = BRACKET_TAG_REGEX.replace(cleaned) { match ->
            if (isMetadataNoise(match.groupValues[1])) "" else match.value
        }

        cleaned = WHITESPACE_REGEX.replace(cleaned, " ").trim(' ', '-', '_', '.', ',')
        return cleaned.ifBlank { cleanAnimeName(name) }
    }

    private fun isMetadataNoise(content: String): Boolean {
        val value = content.trim()
        if (value.isBlank()) return true
        if (YEAR_OR_RANGE_REGEX.matches(value)) return true
        if (value.lowercase(Locale.US) in METADATA_NOISE_WORDS) return true
        return isVideoTypeTag(value)
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

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        infoPreference(
            screen,
            "Folder structure",
            "Each folder directly inside My Pack is one anime. Its sub-folders become seasons, " +
                "and a folder without sub-folders shows its episodes directly.",
        )
        infoPreference(
            screen,
            "Season folders",
            "Use names like \"Season 1\" or \"S01\" to get \"Anime S01\", or any label such as " +
                "\"Dear Sister\" to get \"Anime Dear Sister\". Both are matched against the API separately.",
        )
        infoPreference(
            screen,
            "Episode files",
            "Numbers in E09, \"- 09\", [09] or \"Episode 9\" are detected; files without a number are " +
                "numbered in name order. Keep release group and quality tags out of anime folder names " +
                "so the API match is accurate.",
        )
        infoPreference(
            screen,
            "Metadata",
            "Anime folder names are matched against AniList for cover, studio, characters, " +
                "recommendations and related entries. Add a TMDB API key for non-anime movies and TV.",
        )

        EditTextPreference(screen.context).apply {
            key = PREF_ACCOUNT_KEY
            title = "PikPak account"
            summary = "Email:password."
            setDefaultValue("")
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).trim()
                catalogCache.clear()
                libraryCache.clear()
                extraFolderCache.clear()
                seasonProbeCache.clear()
                directClient = null
                directClientAccount = null
                clearStoredTokens()
                preferences.edit()
                    .putString(key, value)
                    .remove(PREF_REFRESH_TOKEN_INPUT)
                    .commit()
            }
        }.also(screen::addPreference)

        Preference().apply {
            title = "Log in with PikPak (web)"
            summary = "Opens the PikPak website, imports the session token, then manages refreshes automatically."
            setOnPreferenceClickListener {
                loginWithWebView(screen)
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_REFRESH_TOKEN_INPUT
            title = "PikPak refresh token (optional)"
            summary = "Manual fallback. Copy refresh_token from mypikpak.com: F12 > Application > Local Storage > credentials."
            setDefaultValue("")
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, (newValue as String).trim()).commit()
                catalogCache.clear()
                libraryCache.clear()
                extraFolderCache.clear()
                seasonProbeCache.clear()
                clearStoredTokens()
                directClient = null
                directClientAccount = null
                true
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

        EditTextPreference(screen.context).apply {
            key = PREF_EXTRA_FOLDERS
            title = "Extra folders"
            summary = "Anime folders inside these are listed at the top of the catalog. Folder IDs or paths, separated by commas. Example: My Shared/Anime"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, (newValue as String).trim()).commit()
                catalogCache.clear()
                libraryCache.clear()
                extraFolderCache.clear()
                seasonProbeCache.clear()
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_METADATA_KEY
            title = "Fetch anime metadata"
            summary = "Use AniList for covers, studio, characters and suggestions. TMDB is used as fallback."
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_TMDB_KEY
            title = "TMDB API key (optional)"
            summary = "Used when AniList has no match, especially for movies and TV shows."
            setDefaultValue("")
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, (newValue as String).trim()).commit()
            }
        }.also(screen::addPreference)
    }

    private fun infoPreference(screen: PreferenceScreen, infoTitle: String, infoSummary: String) {
        Preference().apply {
            title = infoTitle
            summary = infoSummary
            // Non-navigating info row; the stub Preference has no selectable flag.
            setOnPreferenceClickListener { false }
        }.also(screen::addPreference)
    }

    /**
     * Logs in through PikPak's own website in a WebView and imports the session's
     * refresh token. The WebView is destroyed afterwards, so nothing else rotates
     * the token; the extension then refreshes it on its own.
     */
    private fun loginWithWebView(screen: PreferenceScreen) {
        val activity = screen.context.findActivity()
        if (activity == null) {
            Toast.makeText(screen.context, "Could not open the PikPak login.", Toast.LENGTH_SHORT).show()
            return
        }

        val webView = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = DESKTOP_UA
            webViewClient = WebViewClient()
        }

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        dialog.setContentView(webView)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        val handler = Handler(Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                webView.evaluateJavascript(READ_CREDENTIALS_JS) { raw ->
                    val credentials = decodeJavascriptString(raw)
                    val token = credentials?.let { parseRefreshToken(it) }
                    if (token == null) {
                        handler.postDelayed(this, WEB_LOGIN_POLL_MS)
                        return@evaluateJavascript
                    }

                    preferences.edit().putString(PREF_REFRESH_TOKEN_INPUT, token).commit()
                    catalogCache.clear()
                    libraryCache.clear()
                    extraFolderCache.clear()
                    seasonProbeCache.clear()
                    clearStoredTokens()
                    directClient = null
                    directClientAccount = null
                    Log.d(TAG, "web login imported tokenLen=${token.length}")
                    Toast.makeText(activity, "PikPak login imported", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }

        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            webView.destroy()
        }

        dialog.show()
        webView.loadUrl(WEB_LOGIN_URL)
        handler.postDelayed(poll, WEB_LOGIN_POLL_MS)
    }

    private data class Library(
        val name: String,
        val entries: List<EpisodeEntry>,
    )

    private data class CatalogItem(
        val id: String,
        val name: String,
        val isFolder: Boolean,
        val isTorrent: Boolean,
        val isPinned: Boolean = false,
        val createdTime: String? = null,
        val thumbnail: String? = null,
        val order: Int = Int.MAX_VALUE,
    )

    private data class EpisodeEntry(
        val fileName: String,
        val fileId: String,
        val season: String?,
        val episode: Int?,
        val released: String? = null,
        val thumbnail: String? = null,
    )

    companion object {
        private const val TAG = "PikPak"
        private const val BUILD_TAG = "direct-v17"

        private const val PREF_ACCOUNT_KEY = "pikpak_account"
        private const val PREF_REFRESH_TOKEN_INPUT = "pikpak_refresh_token_input"
        private const val PREF_EXTRA_FOLDERS = "pikpak_extra_folders"
        private const val PREF_ACCESS_TOKEN = "pikpak_access_token"
        private const val PREF_REFRESH_TOKEN = "pikpak_refresh_token"
        private const val PREF_USER_ID = "pikpak_user_id"
        private const val PREF_TOKEN_EXPIRES_AT = "pikpak_token_expires_at"
        private const val PREF_TOKEN_CLIENT = "pikpak_token_client"
        private const val TOKEN_STORE_VERSION = "web-v1"
        private const val PREF_TORRENTS_KEY = "show_torrents"
        private const val PREF_WEB_DOWNLOADS_KEY = "show_web_downloads"
        private const val PREF_METADATA_KEY = "fetch_metadata"
        private const val PREF_TMDB_KEY = "tmdb_api_key"

        private const val SEASON_SEPARATOR = "|"
        private const val OTHER_SEASON = "Other"
        private const val SYNTHETIC_SEASON_ID = "__episodes__"
        private const val SYNTHETIC_SEASON_TITLE = "Episodes"
        private const val KIND_FOLDER = "drive#folder"

        private const val CATALOG_CACHE_MS = 5_000L
        private const val LIBRARY_CACHE_MS = 2_000L
        private const val EXTRA_FOLDER_CACHE_MS = 10 * 60 * 1000L
        private const val EXTRA_FOLDER_MISS_MS = 60 * 1000L
        private const val SEASON_PROBE_CACHE_MS = 30 * 60 * 1000L
        private const val FOLDER_PROBE_CONCURRENCY = 6
        private const val PAGE_SIZE = 100

        private val CATALOG_COMPARATOR = compareBy<CatalogItem> { if (it.isPinned) 0 else 1 }
            .thenBy { it.order }
            .thenByDescending { it.createdTime.orEmpty() }
            .thenBy { it.name.lowercase(Locale.US) }

        private const val FALLBACK_POSTER = "https://mypikpak.com/android-chrome-192x192.png"

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

        private val DATE_FORMATTER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private val REFRESH_TOKEN_JSON_REGEX = Regex("\"refresh_token\"\\s*:\\s*\"([^\"]+)\"")

        private val YEAR_OR_RANGE_REGEX = Regex(
            "(?i)^(?:19|20)\\d{2}(?:\\s*[-~–—]\\s*(?:(?:19|20)\\d{2}|present|ongoing))?$",
        )

        private val METADATA_NOISE_WORDS = setOf(
            "tv", "tv series", "series", "movie", "film", "ova", "ona", "special", "specials",
            "complete", "batch", "uncensored", "uncut", "remaster", "remastered",
            "bd", "dvd", "web", "webrip", "web-dl", "bluray", "blu-ray",
            "multi", "multisub", "multi sub", "dual audio",
            "sub", "subs", "dub", "dubbed", "subbed", "english sub", "eng sub",
        )

        private const val WEB_LOGIN_URL = "https://mypikpak.com/"
        private const val WEB_LOGIN_POLL_MS = 1_500L
        private const val READ_CREDENTIALS_JS =
            "(function(){try{return localStorage.getItem('credentials')}catch(e){return null}})()"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun decodeJavascriptString(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
}
