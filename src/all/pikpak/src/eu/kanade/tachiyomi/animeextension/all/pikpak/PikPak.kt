package eu.kanade.tachiyomi.animeextension.all.pikpak

import android.text.InputType
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.CatalogResponse
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.MetaResponse
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.MetaVideo
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.StreamResponse
import eu.kanade.tachiyomi.animeextension.all.pikpak.dto.StremThruUserData
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
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

/**
 * Browses the PikPak "My Pack" through the StremThru Store Stremio addon.
 *
 * StremThru handles PikPak's captcha-based login and short-lived link generation, so the
 * extension only has to speak the Stremio catalog/meta/stream protocol. The addon
 * configuration (store name + PikPak credentials) is base64-encoded into the path.
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

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    // =============================== Seasons ==============================

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        validateAccount()
        val meta = client.get(apiUrl("meta", "other", "${anime.url}.json"))
            .parseAs<MetaResponse>()
            .meta
            ?: return emptyList()

        return meta.videos.orEmpty()
            .sortedWith(
                compareBy<MetaVideo>(
                    { it.season ?: 0 },
                    { it.episode ?: 0 },
                    { it.title },
                ),
            )
            .mapIndexed { index, video ->
                SEpisode.create().apply {
                    episode_number = (video.episode?.takeIf { it > 0 } ?: (index + 1)).toFloat()
                    name = episodeTitle(video.title, index)
                    url = video.id
                    date_upload = parseDate(video.released)
                    preview_url = video.thumbnail.orEmpty()
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

        val metas = client.get(requestUrl).parseAs<CatalogResponse>().metas.orEmpty()

        val animeList = metas.map { meta ->
            SAnime.create().apply {
                title = meta.name
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

    private fun episodeTitle(rawTitle: String, index: Int): String {
        val fallback = "Episode ${index + 1}"
        if (rawTitle.isBlank()) return fallback
        if (!preferences.getBoolean(PREF_FILENAME_KEY, PREF_FILENAME_DEFAULT)) return rawTitle

        return rawTitle.substringAfterLast('\n')
            .removePrefix("📄 ")
            .trim()
            .ifBlank { rawTitle }
    }

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

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_FILENAME_KEY
            title = "Only display filename"
            summary = "Hide the matched episode title StremThru adds before the file name."
            setDefaultValue(PREF_FILENAME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val STORE_NAME = "pikpak"

        private const val PREF_ACCOUNT_KEY = "pikpak_account"
        private const val PREF_TORRENTS_KEY = "show_torrents"
        private const val PREF_WEB_DOWNLOADS_KEY = "show_web_downloads"
        private const val PREF_FILENAME_KEY = "filename_only"
        private const val PREF_FILENAME_DEFAULT = true

        private const val CATALOG_TORRENTS = "st:store:pp"
        private const val CATALOG_WEB_DOWNLOADS = "st:store:pp-webdl"

        private const val PAGE_SIZE = 100
        private const val FALLBACK_POSTER = "https://mypikpak.com/android-chrome-192x192.png"

        private val DATE_FORMATTER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
