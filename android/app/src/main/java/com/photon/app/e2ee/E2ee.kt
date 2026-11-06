package com.photon.app.e2ee

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PHOTON end-to-end encryption, version 1 (`../e2ee.md`). This package is plain Kotlin on the JVM
 * (JCA and Tink's X25519 and HKDF), with no Android types, so it runs in JVM unit tests against the
 * shared test vectors. It is byte-compatible with the Python reference, `src/photon/common/e2ee.py`.
 *
 * Primitives: X25519, HKDF-SHA256, HMAC-SHA256, AES-256-GCM (12-byte nonces, 16-byte tags), SHA-256.
 * Integers on the wire are unsigned big-endian. Epochs are uint32 on the wire and [Int] here, with the
 * same bit pattern as Wire's generated code; they are printed unsigned.
 */
object E2ee {
    /** The only announce type the server accepts; the real image type is inside, encrypted. */
    const val MIME = "application/vnd.photon.e2ee"
    val INNER_MIMES: List<String> = listOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")

    const val KEY_LEN = 32
    const val TAG_LEN = 16
    const val NONCE_LEN = 12

    const val BUNDLE_VERSION = 1
    /** Version, epoch, epoch key, tag key. */
    const val BUNDLE_LEN = 1 + 4 + KEY_LEN + KEY_LEN
    /** Ephemeral public key, then the sealed bundle with its tag. */
    const val BOX_LEN = KEY_LEN + BUNDLE_LEN + TAG_LEN

    const val FILE_VERSION = 1
    /** Magic, version, epoch, wrap nonce: the locked photo key's AAD. */
    const val PREFIX_LEN = 4 + 1 + 4 + NONCE_LEN
    /** The prefix, the locked photo key and four section lengths. Every section's AAD. */
    const val HEADER_LEN = PREFIX_LEN + KEY_LEN + TAG_LEN + 4 * 4
    const val MAX_META_LEN = 4096 + TAG_LEN

    /** The server's limit for one photo file (`max_photo_bytes`), header and all four sections included. */
    const val MAX_FILE_BYTES = 25L * 1024 * 1024

    internal val MAGIC = "PHE1".toByteArray(Charsets.US_ASCII)
    internal val SEAL_INFO = "photon/seal/v1".toByteArray(Charsets.US_ASCII)
    internal val TAG_INFO = "photon/tag/v1".toByteArray(Charsets.US_ASCII)
    internal val FP_INFO = "photon/fp/v1".toByteArray(Charsets.US_ASCII)
}

/** A malformed, tampered or undecryptable sealed box or photo file. */
open class E2eeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The file was encrypted for an epoch whose key this phone doesn't hold (yet). Not tampering: wait for the key. */
class MissingEpochKeyException(val epoch: Int) : E2eeException("no key for epoch ${epoch.toUInt()}")

/** Lowercase hex, as in the test vectors, Room and URLs. */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            out[2 * i] = DIGITS[(b.toInt() shr 4) and 0xF]
            out[2 * i + 1] = DIGITS[b.toInt() and 0xF]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd-length hex" }
        return ByteArray(hex.length / 2) { i ->
            ((digit(hex[2 * i]) shl 4) or digit(hex[2 * i + 1])).toByte()
        }
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not a hex digit: $c")
    }
}

/** JCA wrappers. A new [Cipher] per call: the JDK refuses to reuse a GCM key and nonce on one instance. */
internal object Crypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

    /** Decrypts `data[offset, offset + length)`; throws [GeneralSecurityException] when the tag doesn't verify. */
    fun open(key: ByteArray, nonce: ByteArray, data: ByteArray, offset: Int, length: Int, aad: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(data, offset, length)

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        require(key.size == E2ee.KEY_LEN) { "AES-256 needs a 32-byte key" }
        require(nonce.size == E2ee.NONCE_LEN) { "AES-GCM needs a 12-byte nonce" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(E2ee.TAG_LEN * 8, nonce))
            updateAAD(aad)
        }
    }

    fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    fun readU32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or (data[offset + 3].toLong() and 0xFF)
}
