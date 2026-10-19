package com.music.bitchord.playback.smart

/**
 * Keeps Automix analysis on the canonical YouTube Opus rendition.
 *
 * Playback may deliberately use a source substitute (including JioSaavn), but
 * its cache entry is not interchangeable with the YouTube recording that the
 * analysis fetcher owns. The plain video id is the cache key pinned to that
 * YouTube copy; suffixed keys (`#alt`, `#hifi`, ...) belong to substitutes or
 * upgrades.
 */
 object AutomixAnalysisSource {
    const val OPUS_QUERY_PARAMETER = "automix_opus"

    /**
     * Whether the YouTube Opus analysis input is available.
     *
     * The Android app sets this false at startup (no YTM audio): [requestsYouTubeOpus]
     * then answers false and no analysis URIs resolve. The desktop app leaves
     * the default and is unaffected.
     */
    @Volatile
    var youtubeOpusEnabled: Boolean = true

    /**
     * Whether analysis may read provider renditions (`#alt`, upgrades).
     *
     * The Android app sets this true alongside disabling the Opus input:
     * with no YouTube copy, the substituted provider copy that playback is
     * actually caching is the only audio there is to measure. The duration
     * cross-check in the analyzer still guards against a different cut.
     * The desktop app leaves the default and stays on the canonical copy.
     */
    @Volatile
    var providerRenditionsAllowed: Boolean = false

    fun isCanonicalYouTubeRendition(videoId: String?, cacheKey: String): Boolean =
        videoId == null || cacheKey == videoId

    /**
     * Whether a cache key names a rendition Automix may measure.
     *
     * The canonical YouTube copy always qualifies. A **provider** copy — any
     * key scoped to this video id but not equal to it: `#alt`, `#hifi`, the
     * upgrade suffixes — qualifies only when [providerRenditionsAllowed] says
     * that copy is the only audio there is to measure. The analyzer's duration
     * cross-check still guards against measuring a different cut of the same
     * song, so this decides *which file* and not *whether it is the right
     * recording*.
     *
     * Lives here rather than in the analyzer because it is a rule about what
     * Automix reads, and because this way it is decided by two flags a test can
     * flip instead of by whatever the device happens to have cached.
     */
    fun isAnalyzableRendition(videoId: String?, cacheKey: String): Boolean {
        if (isCanonicalYouTubeRendition(videoId, cacheKey)) return true
        if (!providerRenditionsAllowed) return false
        if (videoId == null) return true
        return cacheKey.startsWith("$videoId#")
    }

    /**
     * A private playback URI for analysis-only reads.
     *
     * A normal `watch?v=` URI is allowed to reuse the source race already
     * pinned for audible playback. That is exactly the wrong thing for
     * Automix: its independently cached copy must always be YouTube Opus,
     * even while playback is JioSaavn or a lossless upgrade.
     */
    fun opusUri(videoId: String): String = "bitchord://watch?v=$videoId&$OPUS_QUERY_PARAMETER=1"

    fun requestsYouTubeOpus(marker: String?): Boolean = youtubeOpusEnabled && marker == "1"
}
