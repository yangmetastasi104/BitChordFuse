package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A Spotify playlist's page id in the detail stack: this plus the playlist id (or [SpotifyLibrary.LIKED_ID]). */
const val SPOTIFY_PAGE_PREFIX = "spotify:playlist:"

/** Log tag for the diagnostics the library prints when a fetch fails. */
private const val TAG = "BitChord"

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: String?,
    val imageUrl: String?,
)

data class SpotifyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Int,
    val imageUrl: String?,
)

object SpotifyLibrary {
    /** The web player's bearer, and its client token when there is one. */
    class Tokens(val bearer: String, val clientToken: String?)

    /**
     * Where the tokens come from: the phone mints them in a WebView, the desktop in JavaFX's.
     * Null tokens mean signed out or expired.
     */
    fun interface Auth {
        suspend fun tokens(): Tokens?
    }

    /** Installed by each application at start-up; until then every call fails as signed out. */
    @Volatile
    var auth: Auth? = null

    /**
     * The `sp_dc` cookie behind the session, installed by the app from the
     * user's pasted/WebView-captured value. MetroFuse-proven: endpoints that
     * 403 a bare bearer accept it once the cookie rides along, so it travels
     * with every request alongside the minted token.
     */
    @Volatile
    var cookieProvider: (() -> String?)? = null

    private const val GQL = "https://api-partner.spotify.com/pathfinder/v2/query"
    private const val LIBRARY = "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3"
    private const val PLAYLIST = "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763"
    /** The pseudo-playlist id for the Liked Songs collection, which has no playlist uri. */
    const val LIKED_ID = "liked"
    /** Spotify's own Liked Songs artwork, the purple heart; the collection has no cover of its own. */
    private const val LIKED_COVER = "https://misc.scdn.co/liked-songs/liked-songs-640.png"
    private const val LIKED = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    /** The phone's canvas client sent this one; kept so its requests look unchanged. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /** Every playlist in the library, however many pages it takes. */
    suspend fun playlists(retries: Int = 3): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        val headers = try {
            authHeaders()
        } catch (e: Exception) {
            TrackLog.w(TAG, "Spotify playlists auth failed: ${e.message ?: e.javaClass.simpleName}")
            return@withContext emptyList()
        }
        val collected = mutableListOf<SpotifyPlaylist>()
        var offset = 0
        var firstPage = true
        while (true) {
            val page = try {
                playlistPage(headers, offset, retries)
            } catch (e: Exception) {
                // A refusal on the very first page is a failed sync, not an
                // empty library. Swallowing it here returned just the Liked
                // Songs stub, which then overwrote a good synced list with a
                // single row — how a 429 became "there is no Spotify here".
                if (firstPage) throw e
                TrackLog.w(TAG, "Spotify playlists page failed: ${e.message ?: e.javaClass.simpleName}")
                break
            }
            firstPage = false
            collected += page.first
            offset += page.third
            if (page.third == 0 || offset >= page.second) break
        }
        // Liked Songs is not a playlist to Spotify's library query, so it is
        // always offered first rather than depending on it turning up there.
        listOf(SpotifyPlaylist(id = LIKED_ID, name = "Liked Songs", owner = null, imageUrl = LIKED_COVER)) + collected
    }

    /**
     * Every track of a playlist, however long. [onPage] gets the running list
     * after each page, so a thousand-song playlist fills in as it loads
     * rather than appearing all at once at the end.
     */
    suspend fun tracks(
        playlistId: String,
        onPage: (List<SpotifyTrack>) -> Unit = {},
    ): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val headers = try {
            authHeaders()
        } catch (e: Exception) {
            TrackLog.w(TAG, "Spotify tracks auth failed: ${e.message ?: e.javaClass.simpleName}")
            return@withContext emptyList()
        }
        val collected = mutableListOf<SpotifyTrack>()
        var offset = 0
        while (true) {
            val (items, total, raw) = try {
                if (playlistId == LIKED_ID) {
                    likedPage(headers, offset)
                } else {
                    trackPage(headers, playlistId, offset)
                }
            } catch (e: Exception) {
                TrackLog.w(TAG, "Spotify tracks page failed: ${e.message ?: e.javaClass.simpleName}")
                break
            }
            collected += items
            offset += raw
            onPage(collected.toList())
            if (raw == 0 || offset >= total) break
        }
        collected
    }

    private suspend fun playlistPage(
        headers: Map<String, String>,
        offset: Int,
        retries: Int,
    ): Triple<List<SpotifyPlaylist>, Int, Int> {
        val variables = buildJsonObject {
            putJsonArray("filters") { add("Playlists") }
            put("order", null as String?)
            put("textFilter", "")
            putJsonArray("features") {
                add("LIKED_SONGS")
                add("YOUR_EPISODES_V2")
                add("PRERELEASES")
                add("EVENTS")
            }
            put("limit", 50)
            put("offset", offset)
            put("flatten", true)
            putJsonArray("expandedFolders") {}
            put("folderUri", null as String?)
            put("includeFoldersWhenFlattening", false)
        }
        return parsePlaylistPage(gql("libraryV3", LIBRARY, variables, headers, retries))
    }

    private suspend fun trackPage(
        headers: Map<String, String>,
        playlistId: String,
        offset: Int,
    ): Triple<List<SpotifyTrack>, Int, Int> {
        val variables = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", offset)
            put("limit", 100)
            put("enableWatchFeedEntrypoint", false)
        }
        return parseTrackPage(gql("fetchPlaylist", PLAYLIST, variables, headers))
    }

    /**
     * The playlist's cover at its largest. The library list only carries a
     * thumbnail, which looks soft once it fills a playlist page's header, so
     * the page asks for the real image after it opens.
     */
    suspend fun cover(playlistId: String): String? = withContext(Dispatchers.IO) {
        if (playlistId == LIKED_ID) return@withContext LIKED_COVER
        val variables = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", 0)
            put("limit", 1)
            put("enableWatchFeedEntrypoint", false)
        }
        val root = gql("fetchPlaylist", PLAYLIST, variables, authHeaders())
        largestSource(root.obj("data")?.obj("playlistV2")?.obj("images"))
    }

    /**
     * Spotify's editorial picks (`GET /v1/browse/featured-playlists`):
     * Today's Top Hits, RapCaviar and the like. Public data — the web-player
     * bearer minted from the user's own cookie reads it with no extra scopes.
     * Feeds the merged Home feed, MetroFuse-style.
     */
    suspend fun featuredPlaylists(limit: Int = 20, retries: Int = 3): List<SpotifyPlaylist> =
        withContext(Dispatchers.IO) {
            val headers = try {
                authHeaders()
            } catch (e: Exception) {
                TrackLog.w(TAG, "Spotify featured playlists auth failed: ${e.message ?: e.javaClass.simpleName}")
                return@withContext emptyList()
            }
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/browse/featured-playlists?limit=$limit")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            val text = withRateLimitRetry(maxAttempts = retries) {
                Http.client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (response.code == 429) {
                        throw SpotifyRateLimitedException(response.header("Retry-After")?.toLongOrNull() ?: 2L)
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Spotify featured playlists failed (${response.code})")
                    }
                    payload
                }
            }
            parseFeaturedPlaylists(json.parseToJsonElement(text).jsonObject)
        }

    /**
     * Fallback when featured-playlists answers empty: playlists of one browse
     * category (`GET /v1/browse/categories/{id}/playlists`), the same
     * `{playlists: {items}}` shape as featured.
     */
    suspend fun categoryPlaylists(categoryId: String, limit: Int = 20, retries: Int = 3): List<SpotifyPlaylist> =
        withContext(Dispatchers.IO) {
            val headers = try {
                authHeaders()
            } catch (e: Exception) {
                TrackLog.w(TAG, "Spotify category playlists auth failed: ${e.message ?: e.javaClass.simpleName}")
                return@withContext emptyList()
            }
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/browse/categories/$categoryId/playlists?limit=$limit")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            val text = withRateLimitRetry(maxAttempts = retries) {
                Http.client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (response.code == 429) {
                        throw SpotifyRateLimitedException(response.header("Retry-After")?.toLongOrNull() ?: 2L)
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Spotify category playlists failed (${response.code})")
                    }
                    payload
                }
            }
            parseFeaturedPlaylists(json.parseToJsonElement(text).jsonObject)
        }

    /**
     * The listener's most-played tracks (`GET /v1/me/top/tracks`). Same
     * full-track objects as saved tracks, so the same parser reads them.
     * Feeds the "Your top songs" Home shelf, MetroFuse-style.
     */
    suspend fun topTracks(limit: Int = 20, timeRange: String = "short_term", retries: Int = 3): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            val headers = try {
                authHeaders()
            } catch (e: Exception) {
                TrackLog.w(TAG, "Spotify top tracks auth failed: ${e.message ?: e.javaClass.simpleName}")
                return@withContext emptyList()
            }
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/top/tracks?limit=$limit&time_range=$timeRange")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            val text = withRateLimitRetry(maxAttempts = retries) {
                Http.client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (response.code == 429) {
                        throw SpotifyRateLimitedException(response.header("Retry-After")?.toLongOrNull() ?: 2L)
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Spotify top tracks failed (${response.code})")
                    }
                    payload
                }
            }
            parseTopTracks(json.parseToJsonElement(text).jsonObject)
        }

    /**
     * Recently played (`GET /v1/me/player/recently-played`): items wrapping
     * full track objects, the same shape as saved tracks.
     */
    suspend fun recentlyPlayed(limit: Int = 20, retries: Int = 3): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            val headers = try {
                authHeaders()
            } catch (e: Exception) {
                TrackLog.w(TAG, "Spotify recently played auth failed: ${e.message ?: e.javaClass.simpleName}")
                return@withContext emptyList()
            }
            val request = Request.Builder()
                .url("https://api.spotify.com/v1/me/player/recently-played?limit=$limit")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            val text = withRateLimitRetry(maxAttempts = retries) {
                Http.client.newCall(request).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (response.code == 429) {
                        throw SpotifyRateLimitedException(response.header("Retry-After")?.toLongOrNull() ?: 2L)
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Spotify recently played failed (${response.code})")
                    }
                    payload
                }
            }
            parseWrappedTracks(json.parseToJsonElement(text).jsonObject)
        }

    private suspend fun likedPage(headers: Map<String, String>, offset: Int): Triple<List<SpotifyTrack>, Int, Int> {
        val variables = buildJsonObject {
            put("offset", offset)
            put("limit", 100)
        }
        return parseLikedPage(gql("fetchLibraryTracks", LIKED, variables, headers))
    }

    private suspend fun authHeaders(): Map<String, String> {
        val tokens = auth?.tokens()
            ?: throw IllegalStateException("Spotify sign-in expired")
        return buildMap {
            put("Authorization", "Bearer ${tokens.bearer}")
            put("Accept", "application/json")
            put("app-platform", "WebPlayer")
            put("Origin", "https://open.spotify.com")
            put("Referer", "https://open.spotify.com/")
            put("User-Agent", USER_AGENT)
            tokens.clientToken?.let { put("Client-Token", it) }
            // MetroFuse-proven: the session cookie travels with the request,
            // not just inside the minted token. Endpoints that 403 a bare
            // bearer accept it once `sp_dc` rides along.
            cookieProvider?.invoke()?.takeIf { it.isNotBlank() }?.let { put("Cookie", "sp_dc=$it") }
        }
    }

    private suspend fun gql(
        operation: String,
        hash: String,
        variables: JsonObject,
        headers: Map<String, String>,
        retries: Int = 3,
    ): JsonObject {
        val body = buildJsonObject {
            put("variables", variables)
            put("operationName", operation)
            putJsonObject("extensions") {
                putJsonObject("persistedQuery") {
                    put("version", 1)
                    put("sha256Hash", hash)
                }
            }
        }
        val request = Request.Builder()
            .url(GQL)
            .post(body.toString().toRequestBody(mediaType))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val text = withRateLimitRetry(maxAttempts = retries) {
            Http.client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (response.code == 429) {
                    throw SpotifyRateLimitedException(response.header("Retry-After")?.toLongOrNull() ?: 2L)
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("Spotify $operation failed (${response.code})")
                }
                payload
            }
        }
        val root = json.parseToJsonElement(text).jsonObject
        val error = root.arr("errors")?.firstOrNull()?.jsonObject?.str("message")
        if (!error.isNullOrBlank()) throw IllegalStateException(error)
        return root
    }
}

/** The playlists kept, the library's total, and how many entries the page held before filtering. */
internal fun parsePlaylistPage(root: JsonObject): Triple<List<SpotifyPlaylist>, Int, Int> {
    val library = root.obj("data")?.obj("me")?.obj("libraryV3")
        ?: throw IllegalStateException("Spotify library response was empty")
    val rawItems = library.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("item") ?: return@mapNotNull null
        val typeName = wrapper.str("__typename").orEmpty()
        if (!typeName.contains("Playlist", ignoreCase = true)) return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        if (data.str("__typename") != "Playlist") return@mapNotNull null
        val uri = wrapper.str("_uri") ?: return@mapNotNull null
        val name = data.str("name").orEmpty()
        if (name.isBlank()) return@mapNotNull null
        SpotifyPlaylist(
            id = uri.substringAfterLast(":"),
            name = name,
            owner = data.obj("ownerV2")?.obj("data")?.str("name"),
            imageUrl = coverUrl(data.obj("images")),
        )
    }
    return Triple(items, library.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The tracks kept, the playlist's total, and how many entries the page held before filtering. */
internal fun parseTrackPage(root: JsonObject): Triple<List<SpotifyTrack>, Int, Int> {
    val content = root.obj("data")?.obj("playlistV2")?.obj("content")
        ?: throw IllegalStateException("Spotify playlist response was empty")
    val rawItems = content.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val data = element.jsonObject.obj("itemV2")?.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = data.str("uri") ?: data.str("_uri") ?: return@mapNotNull null
        val artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
            artist.jsonObject.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
        }.joinToString(", ")
        val album = data.obj("albumOfTrack")
        SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = artist,
            album = album?.str("name"),
            durationMs = data.obj("duration")?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.lastUrl(),
        )
    }
    return Triple(items, content.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The Liked Songs page: `me.library.tracks`, each entry wrapping the track under `track`. */
internal fun parseLikedPage(root: JsonObject): Triple<List<SpotifyTrack>, Int, Int> {
    val tracks = root.obj("data")?.obj("me")?.obj("library")?.obj("tracks")
        ?: throw IllegalStateException("Spotify liked songs response was empty")
    val rawItems = tracks.arr("items").orEmpty()
    val items = rawItems.mapNotNull { element ->
        val wrapper = element.jsonObject.obj("track") ?: return@mapNotNull null
        val data = wrapper.obj("data") ?: return@mapNotNull null
        val title = data.str("name").orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val uri = wrapper.str("_uri") ?: data.str("uri") ?: return@mapNotNull null
        val album = data.obj("albumOfTrack")
        SpotifyTrack(
            id = uri.substringAfterLast(":"),
            title = title,
            artist = data.obj("artists")?.arr("items").orEmpty().mapNotNull { artist ->
                artist.jsonObject.obj("profile")?.str("name")?.takeIf { it.isNotBlank() }
            }.joinToString(", "),
            album = album?.str("name"),
            durationMs = (data.obj("trackDuration") ?: data.obj("duration"))?.int("totalMilliseconds") ?: 0,
            imageUrl = album?.obj("coverArt")?.arr("sources")?.lastUrl(),
        )
    }
    return Triple(items, tracks.int("totalCount") ?: rawItems.size, rawItems.size)
}

/** The featured-playlists page: each entry is a playlist with a nested `images` list. */
internal fun parseFeaturedPlaylists(root: JsonObject): List<SpotifyPlaylist> =
    root.obj("playlists")?.arr("items").orEmpty().mapNotNull { element ->
        val o = element.jsonObject
        val id = o.str("id") ?: return@mapNotNull null
        SpotifyPlaylist(
            id = id,
            name = o.str("name") ?: "Untitled",
            owner = o.obj("owner")?.str("display_name"),
            imageUrl = o.arr("images")?.firstOrNull()?.jsonObject?.str("url"),
        )
    }

/** The top-tracks page: full track objects, the same shape as saved tracks. */
internal fun parseTopTracks(root: JsonObject): List<SpotifyTrack> =
    root.arr("items").orEmpty().mapNotNull { element -> parseWebApiTrack(element.jsonObject) }

/** The recently-played page: each entry wraps the full track under `track`. */
internal fun parseWrappedTracks(root: JsonObject): List<SpotifyTrack> =
    root.arr("items").orEmpty().mapNotNull { element ->
        val track = element.jsonObject.obj("track") ?: return@mapNotNull null
        parseWebApiTrack(track)
    }

/** A full track as the official Web API returns it, or null when it lacks an id or a name. */
private fun parseWebApiTrack(o: JsonObject): SpotifyTrack? {
    val id = o.str("id") ?: return null
    val title = o.str("name") ?: return null
    val artist = o.arr("artists").orEmpty().mapNotNull { artist ->
        artist.jsonObject.str("name")?.takeIf { it.isNotBlank() }
    }.joinToString(", ")
    val album = o.obj("album")
    return SpotifyTrack(
        id = id,
        title = title,
        artist = artist,
        album = album?.str("name"),
        durationMs = o.int("duration_ms") ?: 0,
        imageUrl = album?.arr("images")?.firstOrNull()?.jsonObject?.str("url"),
    )
}

/** Thrown when Spotify rate-limits us (HTTP 429), carrying its `Retry-After`. */
class SpotifyRateLimitedException(val retryAfterSec: Long) :
    java.io.IOException("Spotify rate limited, retry after $retryAfterSec s")

/**
 * Retries [block] on [SpotifyRateLimitedException], honoring the server's
 * `Retry-After` (via [rateLimitDelayMs], capped at 30s) and using exponential
 * backoff when it's absent. Any other exception passes straight through.
 *
 * [maxAttempts] = 1 means "ask once and report the refusal": the Home feed
 * uses that, because a 429 there has to leave the shelves that are already
 * there alone rather than stall the whole feed for a minute waiting on a
 * retry that is very likely to be refused again.
 */
internal suspend fun <T> withRateLimitRetry(maxAttempts: Int = 3, block: suspend () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: SpotifyRateLimitedException) {
            if (attempt >= maxAttempts - 1) throw e
            delay(rateLimitDelayMs(e.retryAfterSec, attempt))
            attempt++
        }
    }
}

/** Honors `Retry-After` when present (capped at 30s), otherwise exponential backoff, also capped. */
internal fun rateLimitDelayMs(retryAfterSec: Long?, attempt: Int): Long {
    val seconds = retryAfterSec ?: (1L shl attempt.coerceAtMost(5))
    return seconds.coerceIn(0L, 30L) * 1000L
}

/**
 * The widest source of an image's first entry. Spotify lists a playlist's
 * sizes in no fixed order — a 60px thumbnail can come last — so the list is
 * read by width rather than by position.
 */
private fun largestSource(images: JsonObject?): String? =
    images?.arr("items")?.firstOrNull()?.jsonObject?.arr("sources")
        ?.mapNotNull { source ->
            val url = source.jsonObject.str("url") ?: return@mapNotNull null
            url to (source.jsonObject.int("width") ?: 0)
        }
        ?.maxByOrNull { it.second }
        ?.first

private fun coverUrl(images: JsonObject?): String? = largestSource(images)

private fun JsonArray.lastUrl(): String? =
    mapNotNull { it.jsonObject.str("url") }.lastOrNull()

private fun JsonObject.obj(key: String): JsonObject? =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }

private fun JsonObject.arr(key: String): JsonArray? =
    this[key]?.takeIf { it !is JsonNull }?.let { runCatching { it.jsonArray }.getOrNull() }

private fun JsonObject.str(key: String): String? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
