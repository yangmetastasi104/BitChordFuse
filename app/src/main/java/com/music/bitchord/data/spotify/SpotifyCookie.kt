package com.music.bitchord.data.spotify

/**
 * Accepts what the user pastes from browser devtools — either the bare
 * `sp_dc` value, a whole cookie string (`sp_dc=...; other=...`), or a
 * `Cookie:` header line — and returns the bare value, or null when there is
 * no `sp_dc` in it. Mirrors MetroFuse's `normalizeSpotifyCookieInput`.
 */
object SpotifyCookie {
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .trim()
            .trim(';')
            .trim('"')
        if (text.isBlank()) return null
        if (!text.contains("=") && !text.contains(";") && !text.contains(" ")) {
            return text
        }
        return text.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("sp_dc=") }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}
