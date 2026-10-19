package com.music.bitchord.data.innertube

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * YouTube Music traffic gates: catalog vs playback.
 *
 * The Android app disables playback at startup (audio must resolve through
 * provider sources) while leaving catalog reads on. Every playback entry
 * point must fail closed without touching the network; catalog entry points
 * stay callable. The desktop app leaves both defaults on and is unaffected.
 */
class YtmNetworkTest {

    @AfterTest
    fun restore() {
        YtmNetwork.catalogEnabled = true
        YtmNetwork.playbackEnabled = true
    }

    @Test
    fun streamResolutionThrowsWhenPlaybackDisabled() {
        YtmNetwork.playbackEnabled = false
        assertFailsWith<YtmNetwork.DisabledException> {
            runBlocking { StreamResolver.resolve("dQw4w9WgXcQ") }
        }
    }

    @Test
    fun downloadResolutionThrowsWhenPlaybackDisabled() {
        YtmNetwork.playbackEnabled = false
        assertFailsWith<YtmNetwork.DisabledException> {
            runBlocking { StreamResolver.resolveForDownload("dQw4w9WgXcQ", 128, false) }
        }
    }

    @Test
    fun signatureTimestampIsNullWhenPlaybackDisabled() {
        YtmNetwork.playbackEnabled = false
        assertNull(runBlocking { StreamResolver.signatureTimestamp("dQw4w9WgXcQ") })
    }

    @Test
    fun extractionIsNullWhenPlaybackDisabled() {
        YtmNetwork.playbackEnabled = false
        assertNull(runBlocking { InnerTubeXResolver.extract("dQw4w9WgXcQ", 128) })
    }

    @Test
    fun historyPingsAreInertWhenPlaybackDisabled() {
        YtmNetwork.playbackEnabled = false
        runBlocking {
            assertNull(Innertube.playbackTracking("dQw4w9WgXcQ", null))
            assertTrue(Innertube.pingAtr("https://example.invalid", "cpn") < 0)
        }
    }

    @Test
    fun catalogReadsRejectWhenCatalogDisabled() {
        YtmNetwork.catalogEnabled = false
        val result = runBlocking {
            com.music.bitchord.data.YtMusicRepository.search(
                "hello",
                com.music.bitchord.data.model.SearchFilter.SONGS,
            )
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is YtmNetwork.DisabledException)
    }

    @Test
    fun gatesDefaultToEnabled() {
        assertFalse(YtmNetwork.isCatalogDisabled())
        assertFalse(YtmNetwork.isPlaybackDisabled())
        assertFalse(YtmNetwork.isDisabled())
    }
}
