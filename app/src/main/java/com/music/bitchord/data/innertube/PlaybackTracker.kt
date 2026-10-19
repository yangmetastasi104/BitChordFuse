package com.music.bitchord.data.innertube

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * YouTube Music history registration — permanently disabled.
 *
 * Plays were reported to the signed-in account's YTM history via playback /
 * atr / watchtime pings so the home feed reflected listening. With YTM
 * traffic switched off in this build, no session is ever opened and no ping
 * is ever sent. Local listening history ([ListeningStats]) is the record of
 * plays now and is unaffected.
 *
 * The entry points are kept (rather than deleted with their callers)
 * because the playback service's progress sampler calls them on every tick;
 * they are no-ops. [registeredPlays] is kept because the home screen
 * collects it; it never emits, so the home feed never refetches on its
 * account.
 */
object PlaybackTracker {

    private val _registeredPlays = MutableStateFlow(0)

    /** Never emits in this build; kept for the home screen's collector. */
    val registeredPlays: StateFlow<Int> = _registeredPlays.asStateFlow()

    /** Disabled: no history session is opened. */
    fun onPlaying(videoId: String) {
    }

    /** Disabled: no session is ever opened. */
    fun onTrackChanged(positionSeconds: Long) {
    }

    /** Disabled: with no session there is nothing to report. */
    fun onProgress(videoId: String, positionSeconds: Long) {
    }

    /** Disabled: queue end and service teardown report nothing. */
    fun onPlaybackFinished(positionSeconds: Long) {
    }
}
