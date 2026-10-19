package com.music.bitchord.data.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsrcAuthorityTest {

    @Test
    fun normalizesCaseAndSeparators() {
        assertEquals("USRC17607839", IsrcAuthority.normalize("usrc1-7607839"))
        assertEquals("USRC17607839", IsrcAuthority.normalize("USRC17607839"))
    }

    @Test
    fun rejectsBlanks() {
        assertNull(IsrcAuthority.normalize(null))
        assertNull(IsrcAuthority.normalize("  "))
    }

    @Test
    fun validatesShape() {
        assertTrue(IsrcAuthority.isValid("USRC17607839"))
        assertFalse(IsrcAuthority.isValid("USRC1760783"))
        assertFalse(IsrcAuthority.isValid("12RC17607839"))
        assertFalse(IsrcAuthority.isValid(null))
    }

    @Test
    fun firstOfSkipsInvalid() {
        assertEquals("GBAYE0601477", IsrcAuthority.firstOf("nope", "gb-aye-06-01477", "USRC17607839"))
        assertNull(IsrcAuthority.firstOf("nope", null))
    }

    @Test
    fun equalIgnoresFormatting() {
        assertTrue(IsrcAuthority.equal("usrc1-7607839", "USRC17607839"))
        assertFalse(IsrcAuthority.equal("USRC17607839", "USRC17607840"))
        assertFalse(IsrcAuthority.equal(null, "USRC17607839"))
    }
}
