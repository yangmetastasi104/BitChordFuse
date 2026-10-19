package com.music.bitchord

import com.music.bitchord.playback.smart.AutomixAnalysisSource
import com.music.bitchord.playback.QualityUpgrade
import com.music.bitchord.data.sources.StreamFormat
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AutomixAnalysisSourceTest {
    @Test
    fun `YouTube analysis accepts only its canonical Opus cache key`() {
        assertTrue(AutomixAnalysisSource.isCanonicalYouTubeRendition("video", "video"))
        assertFalse(AutomixAnalysisSource.isCanonicalYouTubeRendition("video", "video#alt"))
        assertFalse(AutomixAnalysisSource.isCanonicalYouTubeRendition("video", "video#hifi"))
    }

    @Test
    fun `analysis URI asks the resolver to bypass playback substitutions`() {
        assertTrue(AutomixAnalysisSource.opusUri("video").contains("v=video&automix_opus=1"))
        assertTrue(AutomixAnalysisSource.requestsYouTubeOpus("1"))
        assertFalse(AutomixAnalysisSource.requestsYouTubeOpus(null))
        assertFalse(AutomixAnalysisSource.requestsYouTubeOpus("0"))
    }

    @Test
    fun `second quality upgrade gets a new rendition marker`() {
        val jio = QualityUpgrade.upgradedUri("bitchord://watch?v=video")
        val lossless = QualityUpgrade.upgradedUri(jio)
        assertTrue(jio.endsWith("q=hifi"))
        assertTrue(lossless.endsWith("q=hifi-2"))
    }

    @Test
    fun `provider renditions are measured only once the canonical copy is gone`() {
        val allowedBefore = AutomixAnalysisSource.providerRenditionsAllowed
        try {
            // With YouTube audio available, only the canonical entry is this
            // track's recording; a substitute is a different file.
            AutomixAnalysisSource.providerRenditionsAllowed = false
            assertTrue(AutomixAnalysisSource.isAnalyzableRendition("video", "video"))
            assertFalse(AutomixAnalysisSource.isAnalyzableRendition("video", "video#alt"))
            assertFalse(AutomixAnalysisSource.isAnalyzableRendition("video", "video#hifi"))

            // With it disabled (the phone's setting) the provider copy playback
            // actually caches is the only audio there is to measure.
            AutomixAnalysisSource.providerRenditionsAllowed = true
            assertTrue(AutomixAnalysisSource.isAnalyzableRendition("video", "video"))
            assertTrue(AutomixAnalysisSource.isAnalyzableRendition("video", "video#alt"))
            assertTrue(AutomixAnalysisSource.isAnalyzableRendition("video", "video#hifi-2"))
            // A key scoped to another recording is never this track's, however
            // permissive the setting is.
            assertFalse(AutomixAnalysisSource.isAnalyzableRendition("video", "other#alt"))
            assertFalse(AutomixAnalysisSource.isAnalyzableRendition("video", "other"))
        } finally {
            AutomixAnalysisSource.providerRenditionsAllowed = allowedBefore
        }
    }

    @Test
    fun `a track with no video id has no rendition to compare against`() {
        // Nothing to scope the key to, so the canonical rule answers yes and the
        // provider branch is never reached.
        assertTrue(AutomixAnalysisSource.isAnalyzableRendition(null, "anything"))
        assertTrue(AutomixAnalysisSource.isAnalyzableRendition(null, "anything#alt"))
    }
}
