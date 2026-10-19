package com.music.bitchord.playback

/**
 * Decides whether a failed provider rendition may be tried again.
 *
 * A normal retry is deliberately free to resolve again, which is exactly
 * wrong for an addon/JioSaavn failure: the same deterministic catalogue
 * lookup wins again and hands the player the same broken stream. A failed
 * alternative is therefore recorded as refused for the rest of this
 * playback instead of being rebuilt — there is no fallback source to
 * rebuild it as, since YouTube Music is disabled in this build.
 */
internal object PlaybackFallback {

    /** Whether [uri] is a provider rendition whose failure should be recorded, not retried blindly. */
    fun isAlternative(uri: String, substitutedYouTube: Boolean): Boolean {
        if (parameter(uri, DIRECT_YOUTUBE_PARAMETER) == "1") return false
        if (uri.substringBefore('?').substringBefore('#') == "bitchord://source") return true
        val rendition = parameter(uri, QualityUpgrade.MARKER)
        return substitutedYouTube || rendition == "hifi" || rendition?.startsWith("hifi-") == true
    }

    private fun parameter(uri: String, wanted: String?): String? {
        val query = uri.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
        return query.split('&')
            .firstOrNull { it.substringBefore('=') == wanted }
            ?.substringAfter('=', missingDelimiterValue = "")
    }
}
