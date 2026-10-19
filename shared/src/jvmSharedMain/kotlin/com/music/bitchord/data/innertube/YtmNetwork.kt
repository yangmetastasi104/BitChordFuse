package com.music.bitchord.data.innertube

import java.io.IOException

/**
 * Separate switches for YouTube Music catalog traffic vs playback traffic.
 *
 * - **Catalog** (`youtubei` browse/search/account/library reads, visitor and
 *   session fetches, lyrics/transcript lookups): metadata only. Enabled
 *   wherever the app shows a catalog — Android and desktop alike.
 * - **Playback** (InnerTubeX/NewPipe extraction, `googlevideo` probes and
 *   fetches, stats pings, PoToken minting): audio bytes. Disabled on Android,
 *   where every play must resolve through the enabled add-on/provider
 *   sources; the desktop app leaves it on.
 *
 * Failing closed (throwing [DisabledException]) rather than returning empty
 * is deliberate: `YtMusicRepository.call` wraps everything in `Result`, and
 * the source layer treats a throw as "this source missed", so screens degrade
 * to explicit empty/error states instead of silently showing nothing.
 */
object YtmNetwork {
    class DisabledException(message: String) : IOException(message)

    /** Catalog reads allowed. The Android app leaves this on. */
    @Volatile
    var catalogEnabled: Boolean = true

    /**
     * Audio resolution allowed. The Android app sets this false
     * unconditionally at startup — not a user setting, nothing in the UI can
     * turn it back on.
     */
    @Volatile
    var playbackEnabled: Boolean = true

    /** Backwards-compatible master switch: false disables both. */
    @Volatile
    var enabled: Boolean = true

    /** Throws [DisabledException] unless catalog traffic is allowed. */
    fun requireCatalog() {
        if (!enabled || !catalogEnabled) {
            throw DisabledException("YouTube Music catalog is disabled in this build")
        }
    }

    /** Throws [DisabledException] unless playback traffic is allowed. */
    fun requirePlayback() {
        if (!enabled || !playbackEnabled) {
            throw DisabledException(
                "YouTube Music playback is disabled in this build; " +
                    "configure an add-on source instead",
            )
        }
    }

    /** True when catalog traffic is switched off. */
    fun isCatalogDisabled(): Boolean = !enabled || !catalogEnabled

    /** True when playback traffic is switched off. */
    fun isPlaybackDisabled(): Boolean = !enabled || !playbackEnabled

    /** True when YouTube Music traffic is switched off. */
    fun isDisabled(): Boolean = !enabled
}
