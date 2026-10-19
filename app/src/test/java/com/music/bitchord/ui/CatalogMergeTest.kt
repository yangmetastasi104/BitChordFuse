package com.music.bitchord.ui

import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unified catalog: YTM metadata + provider rows + Spotify library share one
 * Home and one search list. Neither catalog may supply audio — that is
 * asserted by the resolver/gate tests; here the merge itself is.
 */
class CatalogMergeTest {

    private fun song(
        id: String,
        title: String = "Midnight",
        artist: String = "Night Owl",
        duration: String? = "3:20",
    ) = Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, durationText = duration)

    @Test
    fun `both catalogs contribute to the same list`() {
        val merged = CatalogMerge.mergeSongRows(
            ytm = listOf(SearchResult.Track(song("yt1", title = "Only On YTM"))),
            provider = listOf(SearchResult.Track(song("src:cfg::7", title = "Only On Provider"))),
        )
        assertEquals(2, merged.size)
        // Playable provider rows first.
        assertEquals("src:cfg::7", (merged[0] as SearchResult.Track).song.videoId)
        assertEquals(CatalogMerge.ORIGIN_PROVIDER, CatalogMerge.originOf(merged[0]))
        assertEquals(CatalogMerge.ORIGIN_YTM, CatalogMerge.originOf(merged[1]))
    }

    @Test
    fun `one failing source does not blank the other`() {
        assertEquals(
            1,
            CatalogMerge.mergeSongRows(
                ytm = emptyList(),
                provider = listOf(SearchResult.Track(song("src:cfg::7"))),
            ).size,
        )
        assertEquals(
            1,
            CatalogMerge.mergeSongRows(
                ytm = listOf(SearchResult.Track(song("yt1"))),
                provider = emptyList(),
            ).size,
        )
        assertTrue(CatalogMerge.mergeSongRows(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `same recording merges onto the provider row`() {
        val merged = CatalogMerge.mergeSongRows(
            ytm = listOf(SearchResult.Track(song("yt1", duration = "3:20"))),
            provider = listOf(SearchResult.Track(song("src:cfg::7", duration = "3:21"))),
        )
        assertEquals(1, merged.size)
        assertEquals("src:cfg::7", (merged[0] as SearchResult.Track).song.videoId)
    }

    @Test
    fun `different recordings are never merged`() {
        // Remix with the same title/artist but a different runtime stays.
        val merged = CatalogMerge.mergeSongRows(
            ytm = listOf(SearchResult.Track(song("yt1", duration = "5:47"))),
            provider = listOf(SearchResult.Track(song("src:cfg::7", duration = "3:20"))),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `unknown runtimes never merge`() {
        // A live cut and a studio cut share title/artist; without runtimes
        // only a duration can tell them apart, so both are kept.
        val merged = CatalogMerge.mergeSongRows(
            ytm = listOf(SearchResult.Track(song("yt1", duration = null))),
            provider = listOf(SearchResult.Track(song("src:cfg::7", duration = null))),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `videos never merge`() {
        val merged = CatalogMerge.mergeSongRows(
            ytm = listOf(
                SearchResult.Track(song("yt1").copy(isVideo = true)),
            ),
            provider = listOf(SearchResult.Track(song("src:cfg::7"))),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `spotify shelves carry provenance and playlist ids`() {
        val shelves = CatalogMerge.spotifyShelves(
            listOf(
                SpotifyPlaylist(id = SpotifyLibrary.LIKED_ID, name = "Liked Songs", owner = null, imageUrl = null),
                SpotifyPlaylist(id = "abc", name = "Road", owner = "Amit", imageUrl = "https://img"),
            ),
        )
        assertEquals(2, shelves.size)
        assertTrue(shelves.all { it.subtitle == "Spotify" })
        val liked = shelves[0].items.single()
        assertEquals("spotify:playlist:liked", liked.browseId)
        val road = shelves[1].items.single()
        assertEquals("spotify:playlist:abc", road.browseId)
        assertEquals("Road", road.title)
    }

    @Test
    fun `no spotify shelves without a library`() {
        assertTrue(CatalogMerge.spotifyShelves(emptyList()).isEmpty())
    }

    @Test
    fun `editorial picks become one shelf`() {
        val shelves = CatalogMerge.editorialShelves(
            listOf(
                SpotifyPlaylist(id = "a", name = "Today's Top Hits", owner = "Spotify", imageUrl = "https://img"),
                SpotifyPlaylist(id = "b", name = "RapCaviar", owner = "Spotify", imageUrl = null),
            ),
        )
        assertEquals(1, shelves.size)
        assertEquals("Spotify picks", shelves[0].title)
        assertEquals("Spotify", shelves[0].subtitle)
        assertEquals(2, shelves[0].items.size)
        assertEquals("spotify:playlist:a", shelves[0].items[0].browseId)
        assertEquals("Today's Top Hits", shelves[0].items[0].title)
    }

    @Test
    fun `no editorial shelf without picks`() {
        assertTrue(CatalogMerge.editorialShelves(emptyList()).isEmpty())
    }

    @Test
    fun `made for you lifts algorithmic mixes`() {
        val shelves = CatalogMerge.madeForYouShelves(
            listOf(
                SpotifyPlaylist(id = "m1", name = "Daily Mix 1", owner = "Spotify", imageUrl = null),
                SpotifyPlaylist(id = "m2", name = "Discover Weekly", owner = "Spotify", imageUrl = null),
                SpotifyPlaylist(id = "p1", name = "Road", owner = "Amit", imageUrl = null),
                SpotifyPlaylist(id = "t1", name = "Today's Top Hits", owner = "Spotify", imageUrl = null),
            ),
        )
        assertEquals(1, shelves.size)
        assertEquals("Made for you", shelves[0].title)
        // Daily Mix + Discover Weekly; Road (non-Spotify) and Today's Top
        // Hits (editorial, not algorithmic) stay out.
        assertEquals(listOf("Daily Mix 1", "Discover Weekly"), shelves[0].items.map { it.title })
    }

    @Test
    fun `no made for you without mixes`() {
        assertTrue(
            CatalogMerge.madeForYouShelves(
                listOf(SpotifyPlaylist(id = "p1", name = "Road", owner = "Amit", imageUrl = null)),
            ).isEmpty(),
        )
    }

    @Test
    fun `track rows carry pending spotify ids`() {
        val shelves = CatalogMerge.trackShelves(
            "Your top songs",
            listOf(
                com.music.bitchord.data.spotify.SpotifyTrack(
                    id = "t1", title = "Song", artist = "Artist",
                    album = null, durationMs = 200000, imageUrl = "https://img",
                ),
            ),
        )
        assertEquals(1, shelves.size)
        assertEquals("Your top songs", shelves[0].title)
        assertEquals("Spotify", shelves[0].subtitle)
        val row = shelves[0].items.single()
        assertEquals("sp:t1", row.videoId)
        assertEquals("Song", row.title)
    }
}
