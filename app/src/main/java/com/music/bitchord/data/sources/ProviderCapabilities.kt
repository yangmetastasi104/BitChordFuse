package com.music.bitchord.data.sources

/**
 * What a source can honestly do, ported from MetroFuse's provider model.
 *
 * MetroFuse (`PreferenceKeys.AudioProviderOrderItem` + `LyricsProvider.isEnabled`)
 * has no generic capability matrix; every `*AudioProvider` is assumed
 * playback-capable. BitChord keeps its [MusicSource] contract and adds this
 * explicit declaration so a source never advertises what it cannot do —
 * in particular a metadata-only source (Spotify) must never claim playback,
 * and no source may claim recommendations (unavailable via current APIs).
 */
data class ProviderCapabilities(
    val search: Boolean = true,
    val trackMetadata: Boolean = true,
    val artistInfo: Boolean = false,
    val albumInfo: Boolean = false,
    val playlistRetrieval: Boolean = false,
    val userLibrary: Boolean = false,
    /** Officially supported discovery only (Top / Recently-played). Never algorithmic recommendations. */
    val discovery: Boolean = false,
    /** True only when [MusicSource.stream] can return an authorized, openable URL. */
    val playback: Boolean = true,
    val authentication: Boolean = false,
    val localFileAccess: Boolean = false,
) {
    companion object {
        /** Full catalogue with streams (YouTube Music built-in). */
        val FULL_PLAYBACK = ProviderCapabilities(
            search = true, trackMetadata = true, artistInfo = true, albumInfo = true,
            playlistRetrieval = true, userLibrary = true, discovery = false,
            playback = true, authentication = true, localFileAccess = false,
        )

        /** Metadata-only (Spotify Web API): library + playlists + top/recent, never audio. */
        val METADATA_ONLY = ProviderCapabilities(
            search = true, trackMetadata = true, artistInfo = true, albumInfo = true,
            playlistRetrieval = true, userLibrary = true, discovery = true,
            playback = false, authentication = true, localFileAccess = false,
        )

        /** Local media: device files only, no network catalogue. */
        val LOCAL_ONLY = ProviderCapabilities(
            search = true, trackMetadata = true, artistInfo = false, albumInfo = true,
            playlistRetrieval = false, userLibrary = false, discovery = false,
            playback = true, authentication = false, localFileAccess = true,
        )

        /** Generic user addon/module: search + streams as its manifest/rows describe. */
        val ADDON_DEFAULT = ProviderCapabilities(
            search = true, trackMetadata = true, artistInfo = false, albumInfo = false,
            playlistRetrieval = false, userLibrary = false, discovery = false,
            playback = true, authentication = false, localFileAccess = false,
        )
    }
}
