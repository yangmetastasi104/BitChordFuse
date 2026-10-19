package com.music.bitchord.data.spotify

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Local metadata cache for Spotify library sync.
 *
 * Spotify playlist metadata and audio playback stay separate concerns:
 * this stores identities (playlist/track ids, order) so playback can
 * resolve each row through an enabled provider source at play time. No
 * audio is downloaded.
 *
 * Persistence is file/JSON (no Room, per architecture constraint); the
 * caller supplies load/save lambdas (SharedPreferences on Android).
 */
object SpotifyCache {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class CachedPlaylist(
        val id: String,
        val name: String,
        val owner: String? = null,
        val imageUrl: String? = null,
        /** Null when only metadata (no tracks) was fetched. */
        val trackIds: List<String>? = null,
        val syncedAtMs: Long = 0L,
    )

    @Serializable
    data class CachedTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationMs: Int = 0,
        val imageUrl: String? = null,
    )

    @Serializable
    data class Snapshot(
        val playlists: List<CachedPlaylist> = emptyList(),
        val tracksByPlaylist: Map<String, List<CachedTrack>> = emptyMap(),
        val liked: List<CachedTrack> = emptyList(),
        val syncedAtMs: Long = 0L,
    )

    fun encode(snapshot: Snapshot): String =
        json.encodeToString(Snapshot.serializer(), snapshot)

    fun decode(raw: String?): Snapshot {
        if (raw.isNullOrBlank()) return Snapshot()
        return runCatching { json.decodeFromString(Snapshot.serializer(), raw) }
            .getOrDefault(Snapshot())
    }

    fun fromTracks(tracks: List<SpotifyTrack>): List<CachedTrack> =
        tracks.map {
            CachedTrack(
                id = it.id, title = it.title, artist = it.artist,
                album = it.album, durationMs = it.durationMs, imageUrl = it.imageUrl,
            )
        }

    /** Stale after 24h, or never synced. */
    fun isStale(syncedAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        syncedAtMs == 0L || nowMs - syncedAtMs > 24L * 60 * 60 * 1000
}
