package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyScrobblerTest {

    private fun song(id: String) = Song(
        videoId = id, title = "Song", artist = "Artist",
        thumbnailUrl = null, durationText = "3:20",
    )

    @Test
    fun `pending rows resolve to spotify uris`() {
        assertEquals("spotify:track:t1", SpotifySession.spotifyUriFor(song("sp:t1")))
    }

    @Test
    fun `matched provider rows resolve via the noted mapping`() {
        SpotifySession.noteResolved("t9", "src:cfg::42")
        assertEquals("spotify:track:t9", SpotifySession.spotifyUriFor(song("src:cfg::42")))
    }

    @Test
    fun `unknown rows resolve to nothing`() {
        assertNull(SpotifySession.spotifyUriFor(song("whatever")))
        assertNull(SpotifySession.spotifyUriFor(song("sp:")))
    }

    @Test
    fun `scrobbler is inert without a session`() {
        // No cookie connected in tests: start/position/stop must neither
        // throw nor report anything.
        SpotifyScrobbler.onSongStart(song("sp:t1"), 200_000L)
        SpotifyScrobbler.onPosition(150_000L)
        SpotifyScrobbler.onSongStop()
    }

    @Test
    fun `home error is recorded once and cleared`() {
        SpotifySession.clearHomeError()
        assertNull(SpotifySession.homeError.value)
        SpotifySession.noteHomeError("editorial: boom")
        SpotifySession.noteHomeError("top songs: ignored")
        assertEquals("editorial: boom", SpotifySession.homeError.value)
        SpotifySession.clearHomeError()
        assertNull(SpotifySession.homeError.value)
    }
}
