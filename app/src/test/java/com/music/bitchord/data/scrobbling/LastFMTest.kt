package com.music.bitchord.data.scrobbling

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the Last.fm wire protocol that fail silently.
 *
 * A signature that is wrong, or a success read as a failure, produces no error
 * anywhere: the app simply never scrobbles. Both are pinned here, against a
 * loopback server, so no credentials and no internet are needed.
 */
class LastFMTest {

    @After
    fun restoreDefaults() {
        LastFM.configure(
            endpoint = LastFM.DEFAULT_API_ENDPOINT,
            apiKey = "",
            secret = "",
            sessionKey = null,
        )
    }

    @Test
    fun `the signature follows the documented construction`() {
        // Sorted by parameter name, name and value concatenated, secret appended,
        // then MD5 as lowercase hex — api_sig for auth.getSession in the docs.
        val params = mapOf("api_key" to "abc", "method" to "auth.getSession", "token" to "xyz")
        assertEquals("ff9f69e127d6564969031326083d5428", apiSignature(params, "sec"))
    }

    @Test
    fun `a different secret signs differently`() {
        val params = mapOf("api_key" to "abc", "method" to "auth.getSession", "token" to "xyz")
        assertNotEquals(apiSignature(params, "sec"), apiSignature(params, "other"))
    }

    @Test
    fun `a body whose track title holds the word error is still a success`() {
        MockWebServer().use { server ->
            // The old check was a substring search for `"error"`, which this
            // body matches — so an accepted scrobble was thrown away.
            server.enqueue(
                MockResponse().setBody(
                    """{"scrobbles":{"@attr":{"accepted":1,"ignored":0},""" +
                        """"scrobble":{"artist":{"#text":"A"},"track":{"#text":"error"}}}}""",
                ),
            )
            signedIn(server)
            val result = runBlocking { LastFM.scrobble(artist = "A", track = "error", timestamp = 1_700_000_000L) }
            assertTrue(result.isSuccess)
        }
    }

    @Test
    fun `an error document is reported with its own code`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"error":11,"message":"Service Offline - This service is temporarily offline, try again later"}""",
                ),
            )
            signedIn(server)
            val failure = runBlocking { LastFM.scrobble(artist = "A", track = "B", timestamp = 1L) }.exceptionOrNull()
            assertTrue(failure is LastFM.LastFmException)
            assertEquals(11, (failure as LastFM.LastFmException).code)
        }
    }

    @Test
    fun `a server error with no document is reported with its status`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody(""))
            signedIn(server)
            val failure = runBlocking { LastFM.scrobble(artist = "A", track = "B", timestamp = 1L) }.exceptionOrNull()
            assertTrue(failure is LastFM.LastFmException)
            assertEquals(500, (failure as LastFM.LastFmException).code)
        }
    }

    @Test
    fun `a now playing update is a signed POST to the endpoint`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"nowplaying":{}}"""))
            signedIn(server)
            val result =
                runBlocking {
                    LastFM.updateNowPlaying(
                        artist = "Kendrick Lamar, SZA",
                        track = "All The Stars",
                        album = "Black Panther",
                        duration = 208,
                    )
                }
            assertTrue(result.isSuccess)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path.orEmpty().startsWith("/2.0/"))
        }
    }

    private fun signedIn(server: MockWebServer) {
        LastFM.configure(
            endpoint = server.url("/2.0/").toString(),
            apiKey = "api-key",
            secret = "shared-secret",
            sessionKey = "a-session-key",
        )
    }
}
