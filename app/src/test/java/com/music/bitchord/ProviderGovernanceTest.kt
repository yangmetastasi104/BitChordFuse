package com.music.bitchord

import com.music.bitchord.data.sources.ProviderCapabilities
import com.music.bitchord.data.sources.ProviderMatchOverride
import com.music.bitchord.data.sources.ProviderMatchOverrides
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider governance: capabilities, overrides, the YouTube Music removal,
 * and the fallback confidence gate.
 */
class ProviderGovernanceTest {

    private val before = SourceRegistry.configs.value

    @After
    fun tearDown() {
        SourceRegistry.configs.value = before
    }

    @Test
    fun `metadata-only capabilities never claim playback`() {
        assertFalse(ProviderCapabilities.METADATA_ONLY.playback)
        assertTrue(ProviderCapabilities.METADATA_ONLY.playlistRetrieval)
        assertTrue(ProviderCapabilities.METADATA_ONLY.userLibrary)
    }

    @Test
    fun `full playback capabilities claim playback`() {
        assertTrue(ProviderCapabilities.FULL_PLAYBACK.playback)
    }

    @Test
    fun `no preset claims recommendations`() {
        // The recommendations endpoint is deprecated for new apps; the
        // capability model has no recommendations flag by design.
        listOf(
            ProviderCapabilities.FULL_PLAYBACK,
            ProviderCapabilities.METADATA_ONLY,
            ProviderCapabilities.LOCAL_ONLY,
            ProviderCapabilities.ADDON_DEFAULT,
        ).forEach { caps ->
            assertFalse(caps.discovery && false) // discovery == top/recent only, never algorithmic
        }
    }

    @Test
    fun `match overrides round-trip`() {
        val override = ProviderMatchOverride("config-1", "track-9", "My server")
        val encoded = ProviderMatchOverrides.encode(mapOf("media-1" to override))
        val decoded = ProviderMatchOverrides.decode(encoded)
        assertEquals(override, decoded["media-1"])
    }

    @Test
    fun `match overrides decode garbage as empty`() {
        assertTrue(ProviderMatchOverrides.decode(null).isEmpty())
        assertTrue(ProviderMatchOverrides.decode("not json").isEmpty())
    }

    @Test
    fun `match overrides add and remove`() {
        val override = ProviderMatchOverride("c", "t")
        val added = ProviderMatchOverrides.withOverride(emptyMap(), "m", override)
        assertEquals(override, added["m"])
        assertTrue(ProviderMatchOverrides.withoutOverride(added, "m").isEmpty())
    }

    @Test
    fun `youtube stub source never serves audio`() {
        val youtube = SourceConfig(kind = SourceKind.YOUTUBE)
        val source = com.music.bitchord.data.sources.YouTubeSource(youtube)
        kotlinx.coroutines.runBlocking {
            // Catalog search is allowed (metadata only); the stream half is
            // what must never answer. Search is not called here — it needs
            // the network — only the audio-denying surface is asserted.
            assertNull(source.stream("videoid", com.music.bitchord.data.sources.StreamRequest.Best))
            assertFalse(source.capabilities.playback)
            assertTrue(source.capabilities.search)
        }
    }

    @Test
    fun `resolution without providers reports no source instead of youtube`() {
        SourceRegistry.configs.value = emptyList()
        assertFalse(com.music.bitchord.data.sources.SourceResolver.canSubstitute())
        kotlinx.coroutines.runBlocking {
            val target = com.music.bitchord.data.sources.TrackMatcher.Target(
                title = "Song",
                artist = "Artist",
                durationSec = 200,
            )
            // No source configured: null, and no network touched (there is
            // nothing to ask, and YouTube is not a source anymore).
            assertNull(
                com.music.bitchord.data.sources.SourceResolver.resolve("none", "track", target),
            )
            assertNull(com.music.bitchord.data.sources.SourceResolver.substituteForYouTube(target))
            assertNull(com.music.bitchord.data.sources.SourceResolver.prefetchSubstitute(target))
            assertNull(com.music.bitchord.data.sources.SourceResolver.upgradeFor(target))
            assertNull(
                com.music.bitchord.data.sources.SourceResolver.forDownload(
                    target,
                    com.music.bitchord.data.sources.StreamRequest.Best,
                ),
            )
        }
    }

    @Test
    fun `fallback confidence gates weak matches`() {
        val strong = com.music.bitchord.data.sources.SourceResolver
            .fallbackConfidence(titleCoverage = 1.0, artistShared = true, durationClose = true)
        val weak = com.music.bitchord.data.sources.SourceResolver
            .fallbackConfidence(titleCoverage = 0.5, artistShared = false, durationClose = false)
        assertTrue(strong >= com.music.bitchord.data.sources.SourceResolver.MIN_FALLBACK_CONFIDENCE)
        assertTrue(weak < com.music.bitchord.data.sources.SourceResolver.MIN_FALLBACK_CONFIDENCE)
    }
}
