package com.photon.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {

    @Test
    fun rfc7636AppendixBVector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifierIs43UrlSafeCharsWithoutPadding() {
        repeat(50) {
            val v = Pkce.newVerifier()
            assertEquals(43, v.length)
            assertTrue(v, v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        }
    }

    @Test
    fun challengeIs43CharsBase64UrlWithoutPadding() {
        val c = Pkce.challenge(Pkce.newVerifier())
        assertEquals(43, c.length)
        assertFalse(c.contains('='))
        assertFalse(c.contains('+'))
        assertFalse(c.contains('/'))
    }

    @Test
    fun verifiersAreRandom() {
        assertNotEquals(Pkce.newVerifier(), Pkce.newVerifier())
    }
}
