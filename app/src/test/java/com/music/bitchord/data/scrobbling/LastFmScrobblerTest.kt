package com.music.bitchord.data.scrobbling

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which failures the client repeats and which it accepts as final.
 *
 * Last.fm's own list: error 11 and error 16 ask to be retried, error 9 asks for
 * a re-authentication instead, and every other code means the request was wrong
 * and would be wrong again. Retrying those would only be noise; refusing to
 * retry 11 and 16 would silently lose listens.
 */
class LastFmScrobblerTest {

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
    fun `error 11 service offline is retried`() =
        assertEquals(Submission.RETRY, submitWith("""{"error":11,"message":"offline"}"""))

    @Test
    fun `error 16 temporarily unavailable is retried`() =
        assertEquals(Submission.RETRY, submitWith("""{"error":16,"message":"unavailable"}"""))

    @Test
    fun `error 9 invalid session key is final`() =
        assertEquals(Submission.FAILED, submitWith("""{"error":9,"message":"Invalid session key"}"""))

    @Test
    fun `a missing parameter is final`() =
        assertEquals(
            Submission.FAILED,
            submitWith("""{"error":6,"message":"Missing required parameter artist"}"""),
        )

    @Test
    fun `an accepted scrobble is reported as accepted`() =
        assertEquals(Submission.ACCEPTED, submitWith("""{"scrobbles":{"@attr":{"accepted":1}}}"""))

    @Test
    fun `an http server error is retried`() = assertEquals(Submission.RETRY, submitWith("", code = 500))

    @Test
    fun `an unreachable service is retried`() {
        // No server at all: the exception is not a LastFmException, which is the
        // network-shaped case and the one a listener's flaky connection produces.
        LastFM.configure(
            endpoint = "http://127.0.0.1:1/2.0/",
            apiKey = "api-key",
            secret = "shared-secret",
            sessionKey = "a-session-key",
        )
        val outcome =
            runBlocking {
                LastFmScrobbler.submit(
                    ScrobbleRequest.Scrobble(
                        artist = "A",
                        track = "B",
                        album = null,
                        durationSeconds = 200,
                        timestampSeconds = 1L,
                    ),
                )
            }
        assertEquals(Submission.RETRY, outcome)
    }

    private fun submitWith(
        body: String,
        code: Int = 200,
    ): Submission =
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(code).setBody(body))
            LastFM.configure(
                endpoint = server.url("/2.0/").toString(),
                apiKey = "api-key",
                secret = "shared-secret",
                sessionKey = "a-session-key",
            )
            runBlocking {
                LastFmScrobbler.submit(
                    ScrobbleRequest.Scrobble(
                        artist = "A",
                        track = "B",
                        album = null,
                        durationSeconds = 200,
                        timestampSeconds = 1L,
                    ),
                )
            }
        }
}
