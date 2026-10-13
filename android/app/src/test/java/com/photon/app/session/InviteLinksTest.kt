package com.photon.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLinksTest {
    private val host = "photon.example"

    @Test
    fun inviteLinkGivesItsToken() {
        assertEquals(JoinTarget.Invite("AbC_-123xyz09876"), InviteLinks.parse("https://photon.example/j/AbC_-123xyz09876", host))
        assertEquals(JoinTarget.Invite("AbC_-123xyz09876"), InviteLinks.parse("  https://PHOTON.example/j/AbC_-123xyz09876/ ", host))
    }

    @Test
    fun foreignOrMalformedLinksAreRefused() {
        assertNull(InviteLinks.parse("https://evil.example/j/AbC_-123xyz09876", host))
        assertNull(InviteLinks.parse("https://photon.example/x/AbC_-123xyz09876", host))
        assertNull(InviteLinks.parse("https://photon.example/j/", host))
        assertNull(InviteLinks.parse("https://photon.example/j/bad%20token", host))
    }

    @Test
    fun codesAreNormalised() {
        assertEquals(JoinTarget.Code("7K3M9QXZ"), InviteLinks.parse("7k3m-9qxz", host))
        assertEquals(JoinTarget.Code("01234567"), InviteLinks.parse("OI234567", host))
        assertEquals(JoinTarget.Code("1ABCDEFG"), InviteLinks.parse("L abcdefg", host))
    }

    @Test
    fun wrongLengthOrAlphabetIsNotACode() {
        assertNull(InviteLinks.parse("ABC", host))
        assertNull(InviteLinks.parse("ABCDEFGHJ", host))
        assertNull(InviteLinks.parse("ABCDEFGU", host)) // U is not in Crockford base32
    }
}
