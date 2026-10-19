package com.music.bitchord.data.scrobbling

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * One thing [ScrobbleManager] asks the network to do.
 *
 * A sealed pair rather than two functions because the two requests have
 * opposite failure policies — see [ScrobbleManager.scrobbleSong] and
 * [ScrobbleManager.updateNowPlaying].
 */
sealed interface ScrobbleRequest {
    val artist: String
    val track: String
    val album: String?

    /** The track's length, or 0 when it was never learned. */
    val durationSeconds: Int

    /** "I have started this track." A status line, not a listen. */
    data class NowPlaying(
        override val artist: String,
        override val track: String,
        override val album: String?,
        override val durationSeconds: Int,
    ) : ScrobbleRequest

    /** "I have listened to this track." What ends up in somebody's history. */
    data class Scrobble(
        override val artist: String,
        override val track: String,
        override val album: String?,
        override val durationSeconds: Int,
        /** Unix seconds at which the play began — the value Last.fm files it under. */
        val timestampSeconds: Long,
    ) : ScrobbleRequest
}

/**
 * What an attempt came back as.
 *
 * [RETRY] is reserved for the failures the service itself asks a client to
 * repeat — error 11, service offline; error 16, temporarily unavailable — and
 * for a request that never reached the service at all. Everything else is
 * [FAILED], because sending it again would get exactly the same answer.
 */
enum class Submission { ACCEPTED, RETRY, FAILED }

/** The network half of scrobbling. */
fun interface Scrobbler {
    suspend fun submit(request: ScrobbleRequest): Submission
}

/**
 * Owns *when* a listen becomes a scrobble, and a track becomes "now playing".
 *
 * The arithmetic is Last.fm's, not ours: a track is scrobbled once it has been
 * heard for half its length or four minutes, whichever comes first — and never
 * at all when it is 30 seconds or shorter. "Heard" means audible time, so a
 * pause takes its share back out, and a track that was chosen or preloaded but
 * never played is never submitted.
 *
 * The network is behind [Scrobbler] so that all of the above can be exercised
 * against a fake, with no credentials, no internet, and no waiting.
 */
class ScrobbleManager(
    private val scope: CoroutineScope,
    private val scrobbler: Scrobbler = LastFmScrobbler,
    var minSongDuration: Int = DEFAULT_MIN_SONG_DURATION,
    var scrobbleDelayPercent: Float = DEFAULT_DELAY_PERCENT,
    var scrobbleDelaySeconds: Int = DEFAULT_DELAY_SECONDS,
    /** Wall clock, in milliseconds. Injectable so the timing can be tested without sleeping. */
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var scrobbleJob: Job? = null
    private var scrobbleRemainingMillis: Long = 0L
    private var scrobbleTimerStartedAt: Long = 0L

    /**
     * Whether [scrobbleTimerStartedAt] is holding a reading worth charging for.
     *
     * A flag rather than a zero sentinel on the timestamp, because a clock is
     * allowed to read zero — a test's virtual one does at the start of every
     * case — and reading that as "never armed" would skip the accounting for
     * the first stretch of the very first track.
     */
    private var timerArmed = false
    private var songStartedAt: Long = 0L
    private var songStarted = false

    /**
     * The submissions in flight, kept so that opting out of scrobbling can
     * abandon them.
     *
     * Two fields rather than one, and neither is touched by [stopScrobbleTimer]:
     * a listen that has already earned its scrobble still has to be filed when
     * the next track begins, so only [destroy] — which is what turning Last.fm
     * off or signing out calls — may cancel them.
     */
    private var submissionJob: Job? = null
    private var nowPlayingJob: Job? = null

    /**
     * Which track the state above belongs to.
     *
     * [songStarted] on its own cannot tell "the same track resumed" from "a
     * different track arrived while the flag was still up", and the second of
     * those was answered with a *resume*: the incoming track was then never
     * timed and never announced, and its scrobble was whatever the outgoing one
     * had happened to leave behind.
     */
    private var currentSongId: String? = null

    /** The length the current play was started with, so a resume need not re-derive it. */
    private var currentDurationSeconds = 0

    /**
     * Set the moment this play's scrobble goes out.
     *
     * Without it a state callback arriving after the threshold — a settings
     * change re-collecting the preferences, a pause and resume, a handoff
     * between players — could arm the timer a second time and file the same
     * listen twice, with two different timestamps.
     */
    private var scrobbledThisPlay = false

    var useNowPlaying = true
    var usePrimaryArtistOnly = false

    fun destroy() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        submissionJob?.cancel()
        submissionJob = null
        nowPlayingJob?.cancel()
        nowPlayingJob = null
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
        timerArmed = false
        songStartedAt = 0L
        songStarted = false
        currentSongId = null
        currentDurationSeconds = 0
        scrobbledThisPlay = false
    }

    fun onSongStart(
        song: Song?,
        durationMs: Long? = null,
    ) {
        if (song == null) return
        songStartedAt = now() / 1000
        songStarted = true
        currentSongId = song.videoId
        scrobbledThisPlay = false
        startScrobbleTimer(song, durationMs)
        if (useNowPlaying) {
            updateNowPlaying(song, durationMs)
        }
    }

    fun onSongResume(
        song: Song,
        durationMs: Long? = null,
    ) {
        resumeScrobbleTimer(song, durationMs)
    }

    fun onSongPause() {
        pauseScrobbleTimer()
    }

    fun onSongStop() {
        stopScrobbleTimer()
        songStarted = false
        currentSongId = null
    }

    /**
     * The one entry point the player's state callbacks need: it decides whether
     * this is the same track resuming, a different one starting, or a pause,
     * from the identity of the track rather than from a bare boolean.
     */
    fun onPlayerStateChanged(
        isPlaying: Boolean,
        song: Song?,
        durationMs: Long? = null,
    ) {
        if (song == null) return
        if (!isPlaying) {
            onSongPause()
            return
        }
        if (!songStarted || song.videoId != currentSongId) {
            onSongStart(song, durationMs)
        } else {
            onSongResume(song, durationMs)
        }
    }

    private fun startScrobbleTimer(
        song: Song,
        durationMs: Long? = null,
    ) {
        scrobbleJob?.cancel()
        scrobbleJob = null
        // Cleared on every path, including the two that give up below. Leaving
        // the previous track's remaining time sitting here is what let a track
        // that can never be timed — one 20 seconds long, or one whose length was
        // never learned — inherit it and be scrobbled after a pause and resume
        // it had done nothing to earn.
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
        timerArmed = false

        val resolved = resolveDurationSeconds(song, durationMs)
        currentDurationSeconds = resolved
        // Last.fm's own floor: a track of 30 seconds or less is never
        // scrobbled, however much of it was heard.
        if (resolved <= minSongDuration) return

        val halfTheTrackMs = (resolved * 1000L * scrobbleDelayPercent).roundToLong()
        val remaining = min(halfTheTrackMs, scrobbleDelaySeconds * 1000L)
        if (remaining <= 0L) {
            scrobbleSong(song, resolved)
            return
        }
        scrobbleRemainingMillis = remaining
        scrobbleTimerStartedAt = now()
        timerArmed = true
        scrobbleJob =
            scope.launch {
                delay(remaining)
                scrobbleSong(song, resolved)
            }
    }

    private fun pauseScrobbleTimer() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        chargeElapsed()
    }

    private fun resumeScrobbleTimer(
        song: Song,
        durationMs: Long? = null,
    ) {
        if (scrobbledThisPlay) return
        val durationSeconds =
            if (currentDurationSeconds > 0) currentDurationSeconds else resolveDurationSeconds(song, durationMs)
        // Charge whatever the arm in flight has already served before arming
        // again. A state callback mid-countdown is the same accounting as a
        // pause immediately followed by a resume — and there is a callback on
        // every play, pause, resume, handoff and settings change, so a resume
        // that restarted the countdown from the top would be reset forever by
        // its own callers and the scrobble would never fire.
        chargeElapsed()
        if (scrobbleRemainingMillis <= 0L) {
            // The wait has been served in full. File it now rather than arming a
            // zero-delay timer that the next callback could charge into
            // negative and lose — but only when this play was timable at all. A
            // track the manager refused to time (too short, or with no length
            // known) has no countdown to have served and must never be filed.
            if (durationSeconds > minSongDuration) scrobbleSong(song, durationSeconds)
            return
        }
        val remaining = scrobbleRemainingMillis
        scrobbleJob?.cancel()
        scrobbleTimerStartedAt = now()
        timerArmed = true
        scrobbleJob =
            scope.launch {
                delay(remaining)
                scrobbleSong(song, durationSeconds)
            }
    }

    /**
     * Takes however long the current arm has been counting for out of
     * [scrobbleRemainingMillis], and stops the arm.
     *
     * Both the pause path and the resume path need this, and neither may skip
     * it: counting the same stretch of listening twice would fire the scrobble
     * early, and counting it not at all — which is what restarting the arm
     * amounts to — would mean it never fires while the player keeps emitting
     * state changes.
     */
    private fun chargeElapsed() {
        if (!timerArmed) return
        val elapsed = now() - scrobbleTimerStartedAt
        scrobbleRemainingMillis = (scrobbleRemainingMillis - elapsed).coerceAtLeast(0L)
        timerArmed = false
    }

    private fun stopScrobbleTimer() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
        timerArmed = false
    }

    private fun scrobbleSong(
        song: Song,
        durationSeconds: Int,
    ) {
        // One scrobble per play, whatever else happens after the threshold. The
        // only thing that clears this is a new [onSongStart] — which is exactly
        // what a repeat, or the same track played again later, looks like.
        if (scrobbledThisPlay) return
        scrobbledThisPlay = true
        // The timer that got here has done its job. Leaving it stamped would
        // let a later pause subtract minutes from a countdown that has already
        // fired.
        scrobbleTimerStartedAt = 0L
        timerArmed = false
        scrobbleRemainingMillis = 0L

        val request =
            ScrobbleRequest.Scrobble(
                artist = song.artist.forScrobble(),
                track = song.title,
                album = song.albumName,
                durationSeconds = durationSeconds,
                timestampSeconds = songStartedAt,
            )
        submissionJob =
            scope.launch {
                // The timestamp is fixed before the first attempt and reused by
                // every retry. That is what makes a retry safe rather than a
                // duplicate: Last.fm keys a scrobble on artist + track + timestamp,
                // so a repeat of a request that actually landed adds nothing.
                var attempt = 1
                while (true) {
                    when (submit(request)) {
                        Submission.ACCEPTED -> {
                            Log.d(TAG, "Scrobbled: ${song.title} by ${song.artist}")
                            return@launch
                        }
                        Submission.FAILED -> {
                            Log.e(TAG, "Scrobble refused, not retrying: ${song.title}")
                            return@launch
                        }
                        Submission.RETRY -> {
                            if (attempt >= MAX_ATTEMPTS) {
                                Log.e(TAG, "Scrobble failed after $attempt attempts: ${song.title}")
                                return@launch
                            }
                            delay(RETRY_BASE_MS * attempt)
                            attempt += 1
                        }
                    }
                }
            }
    }

    /**
     * A single attempt, with a scrobbler that throws treated as a network
     * failure rather than as a reason to give up.
     */
    private suspend fun submit(request: ScrobbleRequest): Submission =
        try {
            scrobbler.submit(request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.d(TAG, "Scrobble submission threw: ${throwable.message}")
            Submission.RETRY
        }

    private fun updateNowPlaying(
        song: Song,
        durationMs: Long? = null,
    ) {
        val request =
            ScrobbleRequest.NowPlaying(
                artist = song.artist.forScrobble(),
                track = song.title,
                album = song.albumName,
                durationSeconds = resolveDurationSeconds(song, durationMs),
            )
        nowPlayingJob =
            scope.launch {
                // Not retried, and not made durable. A "now playing" is a status
                // line: Last.fm says a failed one should not be repeated, and by the
                // time a retry landed the moment it describes would have passed.
                runCatching { scrobbler.submit(request) }
                    .onSuccess { Log.d(TAG, "Updated now playing: ${song.title}") }
                    .onFailure { throwable ->
                        if (throwable is CancellationException) throw throwable
                        Log.d(TAG, "Failed to update now playing: ${song.title}: ${throwable.message}")
                    }
            }
    }

    private fun resolveDurationSeconds(
        song: Song,
        durationMs: Long?,
    ): Int =
        durationMs?.takeIf { it > 0 }?.let { (it / 1000).toInt() }
            ?: song.durationText?.let(::parseDurationSeconds)
            ?: 0

    /**
     * Parse "M:SS" or "MM:SS" duration text to total seconds. Unparseable text
     * answers 0, which reads as "no length", and nothing with no length is ever
     * timed.
     */
    private fun parseDurationSeconds(text: String): Int {
        val parts = text.split(":")
        if (parts.size != 2) return 0
        val minutes = parts[0].toIntOrNull() ?: return 0
        val seconds = parts[1].toIntOrNull() ?: return 0
        return minutes * 60 + seconds
    }

    private fun String.forScrobble(): String = if (usePrimaryArtistOnly) primaryArtist() else this

    companion object {
        private const val TAG = "ScrobbleManager"

        /**
         * Last.fm's floor, and its reason: a track of 30 seconds or less is
         * never scrobbled.
         */
        const val DEFAULT_MIN_SONG_DURATION = 30

        /** Half the track — the first of the two thresholds. */
        const val DEFAULT_DELAY_PERCENT = 0.5f

        /**
         * Four minutes — the cap on the half-the-track rule. See
         * [LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS].
         */
        const val DEFAULT_DELAY_SECONDS = 240

        /** One try plus three, which covers a service restart without hammering. */
        private const val MAX_ATTEMPTS = 4

        /** Backoff step: 2s, 4s, 6s. */
        private const val RETRY_BASE_MS = 2_000L
    }
}
