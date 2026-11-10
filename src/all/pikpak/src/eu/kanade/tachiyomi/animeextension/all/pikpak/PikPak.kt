package eu.kanade.tachiyomi.animeextension.all.pikpak

import android.text.InputType
import android.util.Log
import androidx.preference.EditTextPreference
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
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
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
    private val catalogCache = ConcurrentHashMap<String, Pair<List<PikPakFile>, Long>>()

    @Volatile
    private var directClientAccount: String? = null

    @Volatile
    private var directClient: PikPakClient? = null

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage = catalogPage(myPack(), page)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getLatestUpdates(page: Int): AnimesPage = getPopularAnime(page)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) return getPopularAnime(page)

        val filtered = myPack().filter { it.name.contains(query, ignoreCase = true) }
        return catalogPage(filtered, page)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
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
        val seasons = library.entries.mapNotNull { it.season }.distinct()
        anime.fetch_type = if (seasons.isNotEmpty()) FetchType.Seasons else FetchType.Episodes
        anime.initialized = true
        Log.d(TAG, "details item=$itemId seasons=$seasons type=${anime.fetch_type}")
        return anime
    }

    // =============================== Seasons ==============================

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        val (itemId, season) = parseSeasonUrl(anime.url)
        if (season != null) return emptyList()

        val library = libraryFor(itemId)
        val seasons = library.entries
            .mapNotNull { it.season }
            .distinct()
            .sortedWith(compareBy<String> { seasonSortKey(it) }.thenBy { it })
        Log.d(TAG, "seasons item=$itemId seasons=$seasons")
        if (seasons.isEmpty()) return emptyList()

        return seasons.map { label ->
            SAnime.create().apply {
                title = label
                url = "$itemId$SEASON_SEPARATOR$label"
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
        val (itemId, seasonFilter) = parseSeasonUrl(anime.url)
        val library = libraryFor(itemId)

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

    private suspend fun myPack(): List<PikPakFile> {
        val key = account()
        val now = System.currentTimeMillis()
        catalogCache[key]?.let { (items, expiresAt) ->
            if (expiresAt > now) return filterCatalog(items)
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

        catalogCache[key] = items to (now + CATALOG_CACHE_MS)
        return filterCatalog(items)
    }

    private fun filterCatalog(items: List<PikPakFile>): List<PikPakFile> {
        val showTorrents = preferences.getBoolean(PREF_TORRENTS_KEY, true)
        val showWebDownloads = preferences.getBoolean(PREF_WEB_DOWNLOADS_KEY, true)

        return items
            .filter { item ->
                val isTorrent = item.params?.url?.startsWith("magnet:", ignoreCase = true) == true
                if (isTorrent) showTorrents else showWebDownloads
            }
            .sortedWith(
                compareByDescending<PikPakFile> { it.createdTime.orEmpty() }
                    .thenBy { it.name.lowercase(Locale.US) },
            )
    }

    private fun catalogPage(items: List<PikPakFile>, page: Int): AnimesPage {
        val start = (page - 1) * PAGE_SIZE
        if (start >= items.size) return AnimesPage(emptyList(), false)

        val end = minOf(start + PAGE_SIZE, items.size)
        val animes = items.subList(start, end).map { item ->
            SAnime.create().apply {
                title = cleanAnimeName(item.name).ifBlank { item.id }
                url = item.id
                description = if (item.params?.url?.startsWith("magnet:", ignoreCase = true) == true) {
                    "PikPak torrent"
                } else {
                    "PikPak file"
                }
                thumbnail_url = item.thumbnailLink?.takeIf { it.isNotBlank() } ?: FALLBACK_POSTER
            }
        }

        return AnimesPage(animes, end < items.size)
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

        val finalEntries = withOtherSeason(entries)
        Log.d(
            TAG,
            "direct item=$itemId files=${listing.files.size} videos=${finalEntries.size} " +
                "seasons=${finalEntries.mapNotNull { it.season }.distinct()}",
        )
        return Library(listing.name, finalEntries)
    }

    @Synchronized
    private fun directClient(): PikPakClient? {
        val account = account()
        directClient?.takeIf { directClientAccount == account }?.let { return it }

        val parts = account.split(':', limit = 2)
        if (parts.size < 2 || parts[1].isBlank()) return null

        return PikPakClient(client, parts[0], parts[1]).also {
            directClient = it
            directClientAccount = account
        }
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
                directClient = null
                directClientAccount = null
                preferences.edit().putString(key, value).commit()
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
        val fileId: String,
        val season: String?,
        val episode: Int?,
        val released: String? = null,
        val thumbnail: String? = null,
    )

    companion object {
        private const val TAG = "PikPak"

        private const val PREF_ACCOUNT_KEY = "pikpak_account"
        private const val PREF_TORRENTS_KEY = "show_torrents"
        private const val PREF_WEB_DOWNLOADS_KEY = "show_web_downloads"

        private const val SEASON_SEPARATOR = "|"
        private const val OTHER_SEASON = "Other"

        private const val CATALOG_CACHE_MS = 5_000L
        private const val LIBRARY_CACHE_MS = 2_000L
        private const val PAGE_SIZE = 100

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
    }
}
