package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.TrackLog
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Reports local plays to Spotify's listening history, MetroFuse-style.
 *
 * When a Spotify-library row (pending `sp:` id, or a provider row matched
 * from one — see [SpotifySession.noteResolved]) passes the scrobble
 * threshold, a start + finalize event pair goes to the history batch
 * endpoint with the web-player bearer and the session cookie. That feeds
 * "Recently played" and the recommendation input.
 *
 * Honest limits, stated in the UI too: this is history reporting through
 * unofficial endpoints, not official playback — it does NOT count toward
 * Spotify Wrapped (only streams inside Spotify clients do), and Spotify can
 * ignore or reject the events at any time. Best-effort and toggleable.
 */
object SpotifyScrobbler {
    private const val TAG = "BitChord"
    private const val BATCH_URL = "https://gew1-spclient.spotify.com/melody/v1/msg/batch"
    private const val CLIENT_VERSION = "0.0.0"
    private const val PLATFORM = "web_player windows 10;chrome 148.0.0.0;desktop"
    private const val SDK_ID = "harmony:4.72.0"
    private const val DEVICE_CLIENT_ID = "spotify-web-player"

    /** Minimum track length worth reporting at all (Last.fm rule). */
    private const val MIN_DURATION_MS = 30_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activeUri: String? = null
    private var activeDurationMs: Long = 0L
    private var activeStartedAtMs: Long = 0L
    private var activePositionMs: Long = 0L
    private var activeReported: Boolean = false
    private var activePlaybackId: String? = null
    private var activeSessionId: String? = null

    private fun enabled(): Boolean =
        AppSettings.spotifyScrobbleEnabled.value &&
            AppSettings.spotifyConnected.value &&
            AppSettings.spotifySpdcToken.value.isNotBlank()

    /** Call when a new track becomes audible. */
    fun onSongStart(song: Song?, durationMs: Long?) {
        endActive(finalize = true)
        val uri = song?.let(SpotifySession::spotifyUriFor) ?: return
        val duration: Long = durationMs?.takeIf { it > 0 }
            ?: song?.durationText?.let(TrackMatcherSeconds::of)?.times(1000)?.toLong()
            ?: return
        if (!enabled() || duration < MIN_DURATION_MS) return
        activeUri = uri
        activeDurationMs = duration
        activeStartedAtMs = System.currentTimeMillis()
        activePositionMs = 0L
        activeReported = false
        activePlaybackId = UUID.randomUUID().toString()
        activeSessionId = UUID.randomUUID().toString()
    }

    /** Call every progress tick while audio is coming out. */
    fun onPosition(positionMs: Long) {
        val uri = activeUri ?: return
        if (activeReported) {
            activePositionMs = positionMs
            return
        }
        activePositionMs = positionMs
        if (!enabled()) return
        // Threshold: half the track or 4 minutes, whichever comes first —
        // the Last.fm rule, same bar MetroFuse configures by default.
        val half: Long = activeDurationMs / 2L
        val threshold: Long = if (half < 240_000L) half else 240_000L
        if (positionMs >= threshold) {
            activeReported = true
            report(uri, start = true, positionMs = positionMs)
        }
    }

    /** Call when the track changes or stops. Finalizes a reported play. */
    fun onSongStop() {
        endActive(finalize = true)
    }

    private fun endActive(finalize: Boolean) {
        val uri = activeUri
        val wasReported = activeReported
        val position = activePositionMs
        activeUri = null
        activeReported = false
        if (finalize && wasReported && uri != null && enabled()) {
            report(uri, start = false, positionMs = position)
        }
        activePlaybackId = null
        activeSessionId = null
    }

    private fun report(uri: String, start: Boolean, positionMs: Long) {
        val cookie = AppSettings.spotifySpdcToken.value
        if (cookie.isBlank()) return
        scope.launch {
            val token = runCatching { SpotifyToken.accessToken() }.getOrNull()
            if (token.isNullOrBlank()) {
                TrackLog.w(TAG, "Spotify scrobble skipped: no web token")
                return@launch
            }
            val playbackId = activePlaybackId ?: return@launch
            val sessionId = activeSessionId ?: return@launch
            val event = if (start) {
                startEvent(uri, playbackId, sessionId)
            } else {
                statsEvent(uri, playbackId, sessionId, positionMs)
            }
            val payload = buildJsonObject {
                put("client_version", CLIENT_VERSION)
                put("platform", PLATFORM)
                put("sdk_id", SDK_ID)
                putJsonArray("messages") { add(event) }
            }
            val request = Request.Builder()
                .url(BATCH_URL)
                .header("User-Agent", SpotifyWebHeaders.USER_AGENT)
                .header("Accept", "*/*")
                .header("Content-Type", "application/json")
                .header("App-Platform", "WebPlayer")
                .header("Referer", "https://open.spotify.com/")
                .header("Origin", "https://open.spotify.com")
                .header("Cookie", "sp_dc=$cookie")
                .header("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody(mediaType))
                .build()
            runCatching {
                Http.client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        TrackLog.d(TAG, "Spotify scrobble ${if (start) "start" else "finalize"} accepted for $uri")
                    } else {
                        TrackLog.w(TAG, "Spotify scrobble rejected: HTTP ${response.code}")
                    }
                }
            }.onFailure {
                TrackLog.w(TAG, "Spotify scrobble failed: ${it.message}")
            }
        }
    }

    private fun startEvent(uri: String, playbackId: String, sessionId: String): JsonObject =
        buildJsonObject {
            put("type", "jssdk_playback_start")
            put("message", buildJsonObject {
                put("play_track", uri)
                put("file_id", "")
                put("playback_id", playbackId)
                put("session_id", sessionId)
                put("ms_start_position", 0)
                put("initially_paused", false)
                put("client_id", DEVICE_CLIENT_ID)
                put("correlation_id", UUID.randomUUID().toString())
                put("feature_identifier", "web-player")
            })
        }

    private fun statsEvent(uri: String, playbackId: String, sessionId: String, positionMs: Long): JsonObject =
        buildJsonObject {
            put("type", "jssdk_playback_stats")
            put("message", buildJsonObject {
                put("play_track", uri)
                put("file_id", "")
                put("playback_id", playbackId)
                put("internal_play_id", playbackId)
                put("memory_cached", false)
                put("persistent_cached", false)
                put("audio_format", "")
                put("video_format", "")
                put("manifest_id", "")
                put("protected", false)
                put("key_system", "")
                put("key_system_impl", "")
                put("urls_json", "[]")
                put("start_time", activeStartedAtMs)
                put("end_time", activeStartedAtMs + positionMs.coerceAtLeast(0L))
                put("external_start_time", activeStartedAtMs)
                put("ms_play_latency", 0)
                put("ms_init_latency", 0)
                put("ms_head_latency", 0)
                put("ms_first_bytes_latency", 0)
                put("ms_manifest_latency", 0)
                put("ms_resolve_latency", 0)
                put("ms_playing_time", positionMs.coerceAtLeast(0L))
                put("ms_total_play_time", positionMs.coerceAtLeast(0L))
            })
        }
}

/** Web-player UA shared by the history calls. */
internal object SpotifyWebHeaders {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/148.0.0.0 Safari/537.36"
}

/** Duration text (`m:ss`) to seconds, without pulling in the matcher. */
private object TrackMatcherSeconds {
    fun of(durationText: String?): Int? {
        if (durationText.isNullOrBlank()) return null
        val parts = durationText.split(":").mapNotNull { it.trim().toIntOrNull() }
        if (parts.isEmpty()) return null
        var total = 0
        for (part in parts) total = total * 60 + part
        return total.takeIf { it > 0 }
    }
}
