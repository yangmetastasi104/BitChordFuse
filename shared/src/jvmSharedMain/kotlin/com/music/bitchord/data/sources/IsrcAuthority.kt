package com.music.bitchord.data.sources

/**
 * ISRC normalization and comparison, ported from MetroFuse's
 * `providers/ProviderIsrc.kt` (GPL-3.0, MetroFuse/Metrolist authors).
 *
 * An ISRC (`CCXXXYYNNNNN`: 2-letter country, 3-char registrant, 2-digit year,
 * 5-digit designation) identifies a *recording*, not a catalogue row, which
 * makes it the strongest cross-source match signal — stronger than any
 * title/artist fuzzy score. Used by the Spotify → local/YouTube resolution
 * path: Spotify exposes ISRCs via the Web API, so an exact ISRC hit outranks
 * the title match.
 */
object IsrcAuthority {
    private val VALID = Regex("^[A-Z]{2}[A-Z0-9]{3}\\d{2}\\d{5}$")

    /** Uppercase, strip non-alphanumerics; null when nothing usable remains. */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.uppercase().filter { it.isLetterOrDigit() }
        return cleaned.ifEmpty { null }
    }

    fun isValid(isrc: String?): Boolean = isrc != null && VALID.matches(isrc)

    /** First valid ISRC among candidates, or null. */
    fun firstOf(vararg candidates: String?): String? =
        candidates.firstNotNullOfOrNull { normalize(it)?.takeIf(::isValid) }

    fun firstOf(candidates: Iterable<String?>): String? =
        candidates.firstNotNullOfOrNull { normalize(it)?.takeIf(::isValid) }

    fun equal(a: String?, b: String?): Boolean {
        val na = normalize(a) ?: return false
        val nb = normalize(b) ?: return false
        return na == nb && isValid(na)
    }
}
