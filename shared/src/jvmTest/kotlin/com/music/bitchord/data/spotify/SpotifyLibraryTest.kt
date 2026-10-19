package com.music.bitchord.data.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyLibraryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun playlistsIncludeFolderLeaves() {
        val root = json.parseToJsonElement(
            """
            {"data":{"me":{"libraryV3":{"totalCount":1,"items":[
              {"item":{"__typename":"PlaylistResponseWrapper","_uri":"spotify:playlist:abc","data":{
                "__typename":"Playlist","name":"Road","ownerV2":{"data":{"name":"Amit"}},
                "images":{"items":[{"sources":[{"url":"https://i.scdn.co/x"}]}]}
              }}}
            ]}}}}
            """.trimIndent(),
        ).jsonObject
        val (items, total) = parsePlaylistPage(root)
        assertEquals(1, total)
        assertEquals("abc", items.single().id)
        assertEquals("Road", items.single().name)
        assertEquals("Amit", items.single().owner)
        assertEquals("https://i.scdn.co/x", items.single().imageUrl)
    }

    @Test
    fun tracksReadTitleArtistAndDuration() {
        val root = json.parseToJsonElement(
            """
            {"data":{"playlistV2":{"content":{"totalCount":1,"items":[
              {"itemV2":{"data":{
                "name":"Song","uri":"spotify:track:t1",
                "artists":{"items":[{"uri":"spotify:artist:a","profile":{"name":"Artist"}}]},
                "albumOfTrack":{"name":"Album","coverArt":{"sources":[{"url":"https://img"}]}},
                "duration":{"totalMilliseconds":180000}
              }}}
            ]}}}}
            """.trimIndent(),
        ).jsonObject
        val track = parseTrackPage(root).first.single()
        assertEquals("t1", track.id)
        assertEquals("Song", track.title)
        assertEquals("Artist", track.artist)
        assertEquals("Album", track.album)
        assertEquals(180000, track.durationMs)
        assertEquals("https://img", track.imageUrl)
    }

    @Test
    fun playlistIdsComeFromLinksAndUris() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        assertEquals(id, SpotifyImporter.extractPlaylistId("https://open.spotify.com/playlist/$id"))
        assertEquals(id, SpotifyImporter.extractPlaylistId("  https://open.spotify.com/playlist/$id?si=abc123  "))
        assertEquals(id, SpotifyImporter.extractPlaylistId("https://open.spotify.com/intl-id/playlist/$id"))
        assertEquals(id, SpotifyImporter.extractPlaylistId("spotify:playlist:$id"))
        assertNull(SpotifyImporter.extractPlaylistId("https://open.spotify.com/album/$id"))
        assertNull(SpotifyImporter.extractPlaylistId("https://notspotify.com/playlist/$id"))
        assertNull(SpotifyImporter.extractPlaylistId("ftp://open.spotify.com/playlist/$id"))
        assertNull(SpotifyImporter.extractPlaylistId("https://open.spotify.com/playlist/short"))
        assertNull(SpotifyImporter.extractPlaylistId(""))
    }

    @Test
    fun featuredPlaylistsParse() {
        val root = json.parseToJsonElement(
            """
            {"playlists":{"items":[
              {"id":"37i9dQZF1DXcBWIGoYBM5M","name":"Today's Top Hits",
               "owner":{"display_name":"Spotify"},
               "images":[{"url":"https://i.scdn.co/big"},{"url":"https://i.scdn.co/small"}]},
              {"id":"37i9dQZF1DWXRqz3211m","name":"RapCaviar",
               "owner":{"display_name":"Spotify"},"images":[]},
              {"name":"Nameless"}
            ]}}
            """.trimIndent(),
        ).jsonObject
        val items = parseFeaturedPlaylists(root)
        assertEquals(2, items.size)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", items[0].id)
        assertEquals("Today's Top Hits", items[0].name)
        assertEquals("Spotify", items[0].owner)
        assertEquals("https://i.scdn.co/big", items[0].imageUrl)
        assertEquals("RapCaviar", items[1].name)
    }

    @Test
    fun topTracksParse() {        val root = json.parseToJsonElement(
            """
            {"items":[
              {"id":"t1","name":"Song","artists":[{"name":"Artist"}],
               "album":{"name":"Album","images":[{"url":"https://img"}]},
               "duration_ms":200000},
              {"name":"Nameless"}
            ]}
            """.trimIndent(),
        ).jsonObject
        val items = parseTopTracks(root)
        assertEquals(1, items.size)
        assertEquals("t1", items[0].id)
        assertEquals("Song", items[0].title)
        assertEquals("Artist", items[0].artist)
        assertEquals("Album", items[0].album)
        assertEquals(200000, items[0].durationMs)
        assertEquals("https://img", items[0].imageUrl)
    }

    @Test
    fun rateLimitDelayHonorsServerValue() {
        assertEquals(0L, rateLimitDelayMs(0L, 0))
        assertEquals(5000L, rateLimitDelayMs(5L, 0))
        assertEquals(30000L, rateLimitDelayMs(120L, 0))
    }

    @Test
    fun rateLimitDelayBacksOffWithoutHeader() {
        assertEquals(1000L, rateLimitDelayMs(null, 0))
        assertEquals(2000L, rateLimitDelayMs(null, 1))
        assertEquals(30000L, rateLimitDelayMs(null, 99))
    }

    @Test
    fun retrySucceedsAfterRateLimits() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val result = withRateLimitRetry {
            calls++
            if (calls < 3) throw SpotifyRateLimitedException(0L)
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun retryGivesUpAfterMaxAttempts() = kotlinx.coroutines.runBlocking {
        var calls = 0
        val failure = runCatching {
            withRateLimitRetry(maxAttempts = 2) {
                calls++
                throw SpotifyRateLimitedException(0L)
            }
        }.exceptionOrNull()
        assertTrue(failure is SpotifyRateLimitedException)
        assertEquals(2, calls)
    }

    @Test
    fun retryPassesOtherErrorsThrough() = kotlinx.coroutines.runBlocking {
        val failure = runCatching {
            withRateLimitRetry { throw IllegalStateException("nope") }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }
}
