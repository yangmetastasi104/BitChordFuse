package com.music.bitchord

import com.music.bitchord.playback.PlaybackFallback
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFallbackTest {

    @Test
    fun `failed upgraded YouTube item is an alternative`() {
        assertTrue(
            PlaybackFallback.isAlternative(
                "bitchord://watch?v=5IX-nUxDtJI&n=Bin%20Tere&q=hifi",
                substitutedYouTube = false,
            ),
        )
    }

    @Test
    fun `failed substituted base item is an alternative`() {
        assertTrue(
            PlaybackFallback.isAlternative(
                "bitchord://watch?v=5IX-nUxDtJI&n=Bin%20Tere",
                substitutedYouTube = true,
            ),
        )
    }

    @Test
    fun `direct YouTube fallback never falls back recursively`() {
        assertFalse(
            PlaybackFallback.isAlternative(
                "bitchord://watch?v=5IX-nUxDtJI&direct_youtube=1&q=original",
                substitutedYouTube = true,
            ),
        )
    }

    @Test
    fun `ordinary YouTube audio is not treated as a failed alternative`() {
        assertFalse(
            PlaybackFallback.isAlternative(
                "bitchord://watch?v=5IX-nUxDtJI&n=Bin%20Tere",
                substitutedYouTube = false,
            ),
        )
    }

    @Test
    fun `source already forced to YouTube never falls back recursively`() {
        assertFalse(
            PlaybackFallback.isAlternative(
                "bitchord://source?s=addon-1&t=3919244&n=Bin%20Tere&direct_youtube=1&q=original",
                substitutedYouTube = false,
            ),
        )
    }

    @Test
    fun `source failure marker is still recognised as an alternative`() {
        assertTrue(
            PlaybackFallback.isAlternative(
                "bitchord://source?s=addon-1&t=3919244&n=Bin%20Tere&a=Vishal%20%26%20Shekhar" +
                    "&manifest_reopen=dash&q=hifi#part",
                substitutedYouTube = false,
            ),
        )
    }

    @Test
    fun `legacy direct youtube marker never falls back recursively`() {
        // Queues from before the YouTube Music removal may still carry the
        // marker; it must not trigger any fallback construction.
        assertFalse(
            PlaybackFallback.isAlternative(
                "bitchord://source?s=addon-1&t=3919244&n=Bin%20Tere&direct_youtube=1&q=original#part",
                substitutedYouTube = false,
            ),
        )
    }
}
