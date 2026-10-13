package com.photon.app.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636) with the S256 method; base64url without padding throughout. */
object Pkce {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** 32 random bytes, which encode to the 43 characters the backend expects. */
    fun newVerifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return encoder.encodeToString(digest)
    }
}
