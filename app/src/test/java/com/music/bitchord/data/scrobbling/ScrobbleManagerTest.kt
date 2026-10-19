package com.music.bitchord.data.scrobbling

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scrobbling arithmetic that is invisible when it is wrong.
 *
 * A listen filed too early, twice, or for the wrong track still looks like a
 * working feature — it just quietly writes the wrong thing into somebody's
 * listening history. Everything here runs on virtual time against a fake
 * [Scrobbler]: no credentials, no network, no sleeping.
 */
class ScrobbleManagerTest {

    @Test
    fun `a track scrobbles once it has been heard for half its length`() =
        runTest {
            val fake = FakeScrobbler()
            val manager = manager(fake)
            manager.onSongStart(song("a", seconds = 200))
            advanceTimeBy(99_000)
            assertEquals(0, fake.scrobbles.size)
            advanceUntilIdle()
            assertEquals(1, fake.scrobbles.size)
            assertEquals(200, fake.scrobbles.single().durationSeconds)
        }

    @Test
    fun `a long track scrobbles at four minutes rather than half its length`() =
        runTest {
            val fake = FakeScrobbler()
            val manager = manager(fake)
            // Twenty minutes, so half is ten: only the four-minute cap can fire.
            manager.onSongStart(song("a", seconds = 1200))
            advanceTimeBy(239_000)
            assertEquals(0, fake.scrobbles.size)
            advanceUntilIdle()
            assertEquals(1, fake.scrobbles.size)
        }

    @Test
    fun `a track played for less than the threshold is never scrobbled`() =
        runTest {
            val fake = FakeScrobbler()
            val manager = manager(fake)
            manager.onSongStart(song("a", seconds = 200))
            advanceTimeBy(30_000)
            manager.onSongStop()
            advanceUntilIdle()
            assertEquals(0, fake.scrobbles.size)
        }

    @Test
    fun `a track of thirty seconds or less is never scrobbled`() =
        runTest {
            val fake = FakeScrobbler()
            val manager = manager(fake)
            manager.onSongStart(song("a", seconds = 30))
            advanceUntilIdle()
            manager.onSongPause()
            manager.onSongResume(song("a", seconds = 30))
            advanceUntilIdle()
            assertEquals(0, fake.scrobbles.size)
            // Still announced, though: the length rule is about history, not status.
            assertEquals(1, fake.nowPlaying.size)
        }

    @Test
    fun `paused time does not count towards the threshold`() =
        runTest {
            val fake = FakeScrobbler()
            val manager = manager(fake)
            manager.onSongStart(song("a", seconds = 200))
            advanceTimeBy(40_000)
            manager.onSongPause()
            // Ten minutes on the lock screen, which is not ten minutes of listening.
            advanceTimeBy(600_000)
            manager.onSongResume(song("a", seconds = 200))
            advanceTimeBy(59_000)
            assertEquals(0, fake.scrobbles.size)
            advanceUntilIdle()
            assertEquals(1, fake.scrobbles.size)
        }

    @Test
    fun `a track whose length is unknown does not inherit the previous track's countdown`() =
        runTest {
            val fake = FakeScrobbler()
            val manager = manager(fake)
            manager.onSongStart(song("long", seconds = 200))
            // Ninety seconds in, ten left on the outgoing track's countdown.
            advanceTimeBy(90_000)
            // Nothing here can be timed: neither the callback nor the row states a length.
            manager.onSongStart(song("unknown", seconds = null), durationMs = 0L)
            manager.onSongPause()
            advanceTimeBy(5_000)
            manager.onSongResume(song("unknown", seconds = null))
            advanceUntilIdle()
            assertEquals(0, fake.scrobbles.size)
        }

    @Test
    fun `a different track arriving while playing is started, not resumed`() =
        runTest {
            val fake = FakeScrobbler()
            val manager = manager(fake)
            manager.onSongStart(song("a", title = "A"))
            advanceTimeBy(90_000)
            manager.onPlayerStateChanged(
                isPlaying = true,
                song = song("b", title = "B"),
                durationMs = 200_000L,
            )
            advanceUntilIdle()
            // A never reached its threshold, and B got its own timer and status line.
            assertEquals(listOf("B"), fake.scrobbles.map { it.track })
            assertEquals(listOf("A", "B"), fake.nowPlaying.map { it.track })
        }

    @Test
    fun `state callbacks after the threshold do not file the listen twice`() =
        runTest {
            val fake = FakeScrobbler()
            val manager = manager(fake)
            val a = song("a", seconds = 200)
            manager.onSongStart(a)
            advanceUntilIdle()
            assertEquals(1, fake.scrobbles.size)
            repeat(5) { manager.onPlayerStateChanged(isPlaying = true, song = a, durationMs = 200_000L) }
            manager.onSongPause()
            manager.onSongResume(a)
            advanceUntilIdle()
            assertEquals(1, fake.scrobbles.size)
        }

    @Test
    fun `playing the same track again counts as a second listen`() =
        runTest {
            val fake = FakeScrobbler()
            val manager = manager(fake)
            val a = song("a", seconds = 200)
            manager.onSongStart(a)
            advanceUntilIdle()
            manager.onSongStop()
            manager.onSongStart(a)
            advanceUntilIdle()
            assertEquals(2, fake.scrobbles.size)
        }

    @Test
    fun `a retryable failure is retried and every attempt reuses the timestamp`() =
        runTest {
            val fake = FakeScrobbler(answers = mutableListOf(Submission.RETRY, Submission.RETRY))
            val manager = manager(fake)
            manager.onSongStart(song("a", seconds = 200))
            advanceUntilIdle()
            assertEquals(3, fake.scrobbles.size)
            // One find in the profile, not three: Last.fm keys on the timestamp.
            assertEquals(1, fake.scrobbles.map { it.timestampSeconds }.toSet().size)
        }

    @Test
    fun `a refusal is not retried`() =
        runTest {
            val fake = FakeScrobbler(answers = mutableListOf(Submission.FAILED))
            val manager = manager(fake)
            manager.onSongStart(song("a", seconds = 200))
            advanceUntilIdle()
            assertEquals(1, fake.scrobbles.size)
        }

    @Test
    fun `a scrobbler that throws is treated as retryable rather than fatal`() =
        runTest {
            val fake = ThrowingScrobbler(failures = 2)
            val manager = manager(fake)
            manager.onSongStart(song("a", seconds = 200))
            advanceUntilIdle()
            assertEquals(3, fake.scrobbleAttempts)
        }

    @Test
    fun `now playing carries the track's metadata`() =
        runTest {
            val fake = FakeScrobbler()
            val manager = manager(fake)
            manager.onSongStart(song("a", artist = "Kendrick Lamar, SZA", title = "All The Stars"))
            advanceUntilIdle()
            val nowPlaying = fake.nowPlaying.single()
            assertEquals("Kendrick Lamar, SZA", nowPlaying.artist)
            assertEquals("All The Stars", nowPlaying.track)
            assertEquals(200, nowPlaying.durationSeconds)
        }

    @Test
    fun `the primary-artist option trims a joint credit from both submissions`() =
        runTest {
            val fake = FakeScrobbler()
            val manager = manager(fake)
            manager.usePrimaryArtistOnly = true
            manager.onSongStart(song("a", artist = "Kendrick Lamar, SZA"))
            advanceUntilIdle()
            assertEquals("Kendrick Lamar", fake.nowPlaying.single().artist)
            assertEquals("Kendrick Lamar", fake.scrobbles.single().artist)
        }

    @Test
    fun `the now playing opt-out sends nothing`() =
        runTest {
            val fake = FakeScrobbler()
            val manager = manager(fake)
            manager.useNowPlaying = false
            manager.onSongStart(song("a", seconds = 200))
            advanceUntilIdle()
            assertEquals(0, fake.nowPlaying.size)
            assertEquals(1, fake.scrobbles.size)
        }

    @Test
    fun `destroying the manager abandons a pending listen`() =
        runTest {
            val fake = FakeScrobbler()
            val manager = manager(fake)
            manager.onSongStart(song("a", seconds = 200))
            advanceTimeBy(50_000)
            manager.destroy()
            advanceUntilIdle()
            assertEquals(0, fake.scrobbles.size)
            // The status line that was already sent stays sent: Last.fm has no
            // way to take one back.
            assertEquals(1, fake.nowPlaying.size)
        }

    @Test
    fun `destroying the manager abandons a submission already in flight`() =
        runTest {
            val fake = BlockingScrobbler()
            val manager = manager(fake)
            manager.onSongStart(song("a", seconds = 200))
            // The threshold passes and the submission is entered, but not answered.
            advanceTimeBy(100_001)
            assertEquals(1, fake.started)
            assertEquals(0, fake.completed)
            manager.destroy()
            advanceUntilIdle()
            assertEquals(0, fake.completed)
        }

    @Test
    fun `a state callback mid-countdown extends the wait instead of restarting it`() =
        runTest {
            val fake = FakeScrobbler()
            val manager = manager(fake)
            val a = song("a", seconds = 200)
            manager.onSongStart(a)
            // Ten rounds of nine seconds, each ending in the callback the player
            // emits on every state change. Ninety seconds served, ten to go.
            repeat(10) {
                advanceTimeBy(9_000)
                manager.onPlayerStateChanged(isPlaying = true, song = a, durationMs = 200_000L)
            }
            assertEquals(0, fake.scrobbles.size)
            // Fires at the hundred-second mark it was always due at. A resume
            // that restarted the countdown would put it a hundred seconds later.
            advanceTimeBy(11_000)
            assertEquals(1, fake.scrobbles.size)
        }

    @Test
    fun `a callback-supplied length is used when the row states none`() =
        runTest {
            val fake = FakeScrobbler()
            val manager = manager(fake)
            manager.onSongStart(song("a", seconds = null), durationMs = 200_000L)
            advanceTimeBy(99_000)
            assertEquals(0, fake.scrobbles.size)
            advanceUntilIdle()
            assertEquals(200, fake.scrobbles.single().durationSeconds)
        }

    private fun TestScope.manager(
        scrobbler: Scrobbler,
        minDuration: Int = 30,
        percent: Float = 0.5f,
        capSeconds: Int = ScrobbleManager.DEFAULT_DELAY_SECONDS,
    ) = ScrobbleManager(
        scope = this,
        scrobbler = scrobbler,
        minSongDuration = minDuration,
        scrobbleDelayPercent = percent,
        scrobbleDelaySeconds = capSeconds,
        // The virtual clock, so a pause's arithmetic follows `advanceTimeBy`
        // instead of the wall clock the test thread never moves.
        now = { testScheduler.currentTime },
    )

    private fun song(
        id: String,
        seconds: Int? = 200,
        artist: String = "Artist",
        title: String = "Track",
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = seconds?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
    )

    private class FakeScrobbler(
        private val answers: MutableList<Submission> = mutableListOf(),
    ) : Scrobbler {
        val requests = mutableListOf<ScrobbleRequest>()
        val scrobbles: List<ScrobbleRequest.Scrobble>
            get() = requests.filterIsInstance<ScrobbleRequest.Scrobble>()
        val nowPlaying: List<ScrobbleRequest.NowPlaying>
            get() = requests.filterIsInstance<ScrobbleRequest.NowPlaying>()

        override suspend fun submit(request: ScrobbleRequest): Submission {
            requests += request
            // Only scrobbles draw on the scripted answers. A now playing is a
            // status line whose outcome the manager never reads, so letting it
            // consume one made the scripted sequence count the wrong calls.
            if (request is ScrobbleRequest.NowPlaying) return Submission.ACCEPTED
            return answers.removeFirstOrNull() ?: Submission.ACCEPTED
        }
    }

    private class ThrowingScrobbler(
        private val failures: Int,
    ) : Scrobbler {
        var scrobbleAttempts = 0
            private set

        override suspend fun submit(request: ScrobbleRequest): Submission {
            if (request is ScrobbleRequest.NowPlaying) return Submission.ACCEPTED
            scrobbleAttempts += 1
            if (scrobbleAttempts <= failures) throw java.io.IOException("network is down")
            return Submission.ACCEPTED
        }
    }

    private class BlockingScrobbler : Scrobbler {
        var started = 0
            private set
        var completed = 0
            private set

        override suspend fun submit(request: ScrobbleRequest): Submission {
            if (request is ScrobbleRequest.NowPlaying) return Submission.ACCEPTED
            started += 1
            delay(60_000)
            completed += 1
            return Submission.ACCEPTED
        }
    }
}
