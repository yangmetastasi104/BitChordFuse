package com.music.bitchord.data.spotify

import android.content.Context
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-scoped Spotify session: user-supplied `sp_dc` cookie + library sync.
 *
 * MetroFuse structure: no OAuth, no client id. The listener pastes their own
 * `sp_dc` session cookie (taken once from a logged-in Spotify web player);
 * [SpotifyToken] mints the web-player bearer from it, and the library is
 * read through Spotify's internal endpoints ([SpotifyLibrary]).
 *
 * Honest limits, stated up front: these are unofficial endpoints. Spotify can
 * rotate them at any time, breaking sync until the hashes/parsing are
 * updated. Stored tokens are wiped only on explicit disconnect — a network
 * timeout or 429 keeps the cookie, and the user retries.
 *
 * Playback never reads audio here — only identities resolved through the
 * enabled provider sources at play time.
 */
object SpotifySession {
    sealed interface State {
        data object SignedOut : State
        data object Syncing : State
        data class Synced(val snapshot: SpotifyCache.Snapshot) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var authStore: AuthStore

    private val _state = MutableStateFlow<State>(State.SignedOut)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * First live Home-section failure (editorial/top/recent), if any. Shown
     * as diagnostics so a silently empty shelf becomes a reportable reason
     * instead of a mystery. Cleared on every successful sync and disconnect.
     */
    private val _homeError = MutableStateFlow<String?>(null)
    val homeError: StateFlow<String?> = _homeError.asStateFlow()

    fun noteHomeError(message: String) {
        if (_homeError.value == null) _homeError.value = message.take(200)
    }

    fun clearHomeError() {
        _homeError.value = null
    }

    @Volatile
    var snapshot: SpotifyCache.Snapshot = SpotifyCache.Snapshot()
        private set

    fun init(context: Context, store: AuthStore) {
        appContext = context.applicationContext
        if (::authStore.isInitialized) return
        authStore = store
        // Snapshot decode + encrypted-prefs read leave the main thread: a
        // large synced library JSON-decoding in onCreate drops startup frames.
        // State starts conservative (SignedOut) and corrects itself below.
        scope.launch {
            val cookie = authStore.spotifySpdc.orEmpty()
            AppSettings.setSpotifySpdcToken(cookie)
            snapshot = load(appContext ?: context)
            _state.value =
                if (cookie.isNotBlank() && AppSettings.spotifyConnected.value) {
                    State.Synced(snapshot)
                } else {
                    State.SignedOut
                }
        }
    }

    /**
     * Accepts a raw `sp_dc` value or a whole cookie string pasted from
     * browser devtools (`sp_dc=...; ...`), stores it encrypted, and validates
     * it by minting a token and reading the library. On success the library
     * syncs immediately.
     */
    fun connectWithCookie(raw: String, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        if (!::authStore.isInitialized) {
            onDone(false, "Not initialized yet — try again in a moment")
            return
        }
        val cookie = SpotifyCookie.normalize(raw)
        if (cookie.isNullOrBlank()) {
            onDone(false, "That doesn't look like an sp_dc cookie")
            return
        }
        scope.launch {
            _state.value = State.Syncing
            AppSettings.setSpotifySpdcToken(cookie)
            SpotifyToken.invalidate()
            val tokens = runCatching { SpotifyLibrary.auth?.tokens() }.getOrNull()
            if (tokens == null) {
                AppSettings.setSpotifySpdcToken("")
                _state.value = State.SignedOut
                onDone(false, "Spotify rejected that cookie — paste a fresh sp_dc value")
                return@launch
            }
            authStore.spotifySpdc = cookie
            AppSettings.setSpotifyConnected(true)
            sync()
            onDone(true, null)
        }
    }

    /** Playlists from the last successful sync (empty when never synced). */
    fun cachedPlaylists(): List<SpotifyPlaylist> =
        snapshot.playlists.map {
            SpotifyPlaylist(id = it.id, name = it.name, owner = it.owner, imageUrl = it.imageUrl)
        }

    /** Tracks of one synced playlist, or Liked Songs for [SpotifyLibrary.LIKED_ID]. */
    fun cachedTracks(playlistId: String): List<SpotifyTrack> {
        val cached = if (playlistId == SpotifyLibrary.LIKED_ID) {
            snapshot.liked
        } else {
            snapshot.tracksByPlaylist[playlistId].orEmpty()
        }
        return cached.map {
            SpotifyTrack(
                id = it.id, title = it.title, artist = it.artist,
                album = it.album, durationMs = it.durationMs, imageUrl = it.imageUrl,
            )
        }
    }

    /**
     * Library sync: the playlist list, and nothing else.
     *
     * It deliberately does not walk every playlist's tracks any more. That
     * loop was **one request per playlist** — twenty playlists meant twenty-odd
     * requests fired back to back, and that burst is what pushed the
     * cookie-minted bearer into a 429 which then starved the whole Home feed.
     * Tracks are now read when a playlist is actually opened (see
     * `openSpotifyPage`) and kept by [rememberTracks], so the cost is per
     * playlist the listener visits rather than per playlist they own.
     */
    fun sync() {
        val ctx = appContext ?: return
        scope.launch {
            val wait = retryAfterMs()
            if (wait > 0) {
                val msg = "Spotify rate limited, retry in ${(wait / 1000).coerceAtLeast(1)}s"
                AppSettings.setSpotifyLastSync(false, msg)
                _state.value = State.Failed(msg)
                return@launch
            }
            _state.value = State.Syncing
            try {
                val now = System.currentTimeMillis()
                val playlists = SpotifyLibrary.playlists(retries = 1)
                // Profiles keep whatever track pages opening a playlist has
                // already paid for; a sync must not throw those away.
                val previous = snapshot
                val cached = SpotifyCache.Snapshot(
                    playlists = playlists.map {
                        SpotifyCache.CachedPlaylist(
                            id = it.id, name = it.name, owner = it.owner, imageUrl = it.imageUrl,
                            trackIds = previous.tracksByPlaylist[it.id]?.map { t -> t.id },
                            syncedAtMs = now,
                        )
                    },
                    tracksByPlaylist = previous.tracksByPlaylist,
                    liked = previous.liked,
                    syncedAtMs = now,
                )
                snapshot = cached
                save(ctx, cached)
                clearHomeError()
                AppSettings.setSpotifyLastSync(true)
                _state.value = State.Synced(cached)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is SpotifyRateLimitedException) noteRateLimit(e.retryAfterSec)
                // Cookie kept: only an explicit disconnect wipes it. A dead
                // cookie surfaces here on every sync until the user pastes a
                // fresh one or disconnects.
                val msg = (e.message ?: "Sync failed").take(300)
                AppSettings.setSpotifyLastSync(false, msg)
                _state.value = State.Failed(msg)
            }
        }
    }

    /**
     * Caches a playlist's tracks as the detail page reads them, so the page
     * opens from the snapshot next time and survives a spell offline. This is
     * the on-demand replacement for the old eager walk over every playlist.
     */
    fun rememberTracks(playlistId: String, tracks: List<SpotifyTrack>) {
        if (tracks.isEmpty()) return
        val previous = snapshot
        val updated = if (playlistId == SpotifyLibrary.LIKED_ID) {
            previous.copy(liked = SpotifyCache.fromTracks(tracks))
        } else {
            previous.copy(
                tracksByPlaylist = previous.tracksByPlaylist +
                    (playlistId to SpotifyCache.fromTracks(tracks)),
            )
        }
        snapshot = updated
        appContext?.let { save(it, updated) }
    }

    /**
     * Live Home sections (editorial/charts/top/recent) with fetch time, so
     * repeated Home loads inside the TTL reuse them instead of hammering the
     * API into a 429. Snapshot shelves never need this — they are already local.
     */
    private var homeLive: Pair<Long, List<HomeShelf>>? = null

    fun cachedHomeLive(maxAgeMs: Long = 30L * 60 * 1000): List<HomeShelf>? {
        val (at, shelves) = homeLive ?: return null
        return shelves.takeIf { System.currentTimeMillis() - at < maxAgeMs }
    }

    fun storeHomeLive(shelves: List<HomeShelf>) {
        homeLive = System.currentTimeMillis() to shelves
    }

    fun clearHomeLive() {
        homeLive = null
    }

    /**
     * The instant Spotify last told us to back off, taken from its
     * `Retry-After`. Until it passes every request would be refused again, so
     * Home serves what it already has and a sync waits — spending the window
     * on retries only ever extends it. Capped at five minutes: the header is a
     * hint, and a stale one must not lock Spotify out for good.
     */
    @Volatile
    private var rateLimitedUntilMs: Long = 0L

    fun noteRateLimit(retryAfterSec: Long) {
        val until = System.currentTimeMillis() + retryAfterSec.coerceIn(1L, 300L) * 1000L
        if (until > rateLimitedUntilMs) rateLimitedUntilMs = until
    }

    /** Milliseconds left in Spotify's back-off window, or 0 when there is none. */
    fun retryAfterMs(): Long = (rateLimitedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

    fun clearRateLimit() {
        rateLimitedUntilMs = 0L
    }

    /**
     * Provider rows matched from Spotify rows, for the scrobbler: a matched
     * `src:` row no longer carries its Spotify id, so the mapping is kept
     * here from match time.
     */
    private val resolvedUris = mutableMapOf<String, String>()

    fun noteResolved(spotifyId: String, providerKey: String) {
        synchronized(resolvedUris) {
            if (resolvedUris.size > 500) resolvedUris.clear()
            resolvedUris[providerKey] = "spotify:track:$spotifyId"
        }
    }

    /** The `spotify:track:` URI behind a queued song, if it came from Spotify. */
    fun spotifyUriFor(song: com.music.bitchord.data.model.Song): String? {
        val pending = song.videoId
            .removePrefix(com.music.bitchord.data.model.SPOTIFY_PENDING_PREFIX)
            .takeIf { it != song.videoId }
            ?.takeIf { it.isNotBlank() }
            ?.let { "spotify:track:$it" }
        if (pending != null) return pending
        synchronized(resolvedUris) { return resolvedUris[song.videoId] }
    }

    /** Disconnect: cookie, session flag, sync stamps and the cache are all wiped. */
    fun signOut() {
        if (::authStore.isInitialized) authStore.spotifySpdc = null
        AppSettings.setSpotifySpdcToken("")
        AppSettings.setSpotifyConnected(false)
        SpotifyToken.invalidate()
        SpotifyWebCookies.clear()
        clearHomeError()
        clearHomeLive()
        clearRateLimit()
        snapshot = SpotifyCache.Snapshot()
        appContext?.let { save(it, snapshot) }
        _state.value = State.SignedOut
    }

    private var appContext: Context? = null

    private fun prefs(context: Context) =
        context.getSharedPreferences("bitchord_spotify_cache", Context.MODE_PRIVATE)

    private fun load(context: Context): SpotifyCache.Snapshot =
        SpotifyCache.decode(prefs(context).getString("snapshot", null))

    private fun save(context: Context, snapshot: SpotifyCache.Snapshot) {
        prefs(context).edit().putString("snapshot", SpotifyCache.encode(snapshot)).apply()
    }
}
