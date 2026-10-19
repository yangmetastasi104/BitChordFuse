package com.music.bitchord.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyCookieTest {

    @Test
    fun `bare value passes through`() {
        assertEquals("abc123", SpotifyCookie.normalize("abc123"))
    }

    @Test
    fun `full cookie string yields sp_dc`() {
        assertEquals(
            "abc123",
            SpotifyCookie.normalize("sp_dc=abc123; sp_t=xyz; Path=/"),
        )
    }

    @Test
    fun `sp_dc found among other cookies`() {
        assertEquals(
            "abc123",
            SpotifyCookie.normalize("other=1; sp_dc=abc123; Path=/; Secure"),
        )
    }

    @Test
    fun `missing sp_dc is rejected`() {
        assertNull(SpotifyCookie.normalize("sp_t=xyz; Path=/"))
        assertNull(SpotifyCookie.normalize(""))
        assertNull(SpotifyCookie.normalize(null))
        assertNull(SpotifyCookie.normalize("   "))
    }

    @Test
    fun `quoted value is unquoted`() {
        assertEquals("abc123", SpotifyCookie.normalize("\"abc123\""))
    }

    @Test
    fun `cookie header prefix is stripped`() {
        assertEquals("abc123", SpotifyCookie.normalize("Cookie: sp_dc=abc123; Path=/"))
        assertEquals("abc123", SpotifyCookie.normalize("cookie:sp_dc=abc123;"))
    }

    @Test
    fun `trailing semicolons are trimmed`() {
        assertEquals("abc123", SpotifyCookie.normalize("sp_dc=abc123;"))
    }
}
