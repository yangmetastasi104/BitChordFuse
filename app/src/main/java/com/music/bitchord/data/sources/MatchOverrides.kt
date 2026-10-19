package com.music.bitchord.data.sources

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Per-track playback-source overrides, ported from MetroFuse's
 * `providers/ProviderMatchOverride.kt` (GPL-3.0, MetroFuse/Metrolist authors).
 *
 * A user-pinned mapping of media id → preferred provider track, so an
 * ambiguous match (remix vs original, live vs studio) can be fixed once
 * instead of re-guessed on every play. Stored as JSON string in prefs;
 * empty map means no overrides.
 *
 * MetroFuse prefixes: `tidal:track:`, `deezer:track:`, `qobuz:track:`.
 * BitChord uses its own `src:<configId>::<trackId>` keys (see
 * [SourceRegistry.trackKey]) plus bare YouTube ids; the [providerTrackId]
 * here is the source-local id, resolved against [providerConfigId].
 */
@Serializable
data class ProviderMatchOverride(
    /** SourceRegistry config id of the preferred source. */
    val providerConfigId: String,
    /** That source's own track id. */
    val providerTrackId: String,
    /** Human label shown in settings ("My FLAC server", …). */
    val label: String = "",
)

object ProviderMatchOverrides {
    private val json = Json { ignoreUnknownKeys = true }

    private val mapSerializer = MapSerializer(String.serializer(), ProviderMatchOverride.serializer())

    /** mediaId (queue id / videoId / trackKey) → override. */
    fun decode(raw: String?): Map<String, ProviderMatchOverride> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString(mapSerializer, raw) }.getOrDefault(emptyMap())
    }

    fun encode(overrides: Map<String, ProviderMatchOverride>): String =
        json.encodeToString(mapSerializer, overrides)

    fun withOverride(
        current: Map<String, ProviderMatchOverride>,
        mediaId: String,
        override: ProviderMatchOverride,
    ): Map<String, ProviderMatchOverride> = current + (mediaId to override)

    fun withoutOverride(
        current: Map<String, ProviderMatchOverride>,
        mediaId: String,
    ): Map<String, ProviderMatchOverride> = current - mediaId
}
