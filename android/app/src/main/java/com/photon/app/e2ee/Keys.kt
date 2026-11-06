package com.photon.app.e2ee

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.nio.ByteBuffer
import java.security.GeneralSecurityException

/** The member key pair (e2ee.md 3): X25519, made once per install. Only the public half leaves the phone. */
object Identity {
    fun newPrivateKey(): ByteArray = X25519.generatePrivateKey()

    fun publicKey(privateKey: ByteArray): ByteArray {
        if (privateKey.size != E2ee.KEY_LEN) throw E2eeException("an identity key is 32 bytes")
        return try {
            X25519.publicFromPrivate(privateKey)
        } catch (e: GeneralSecurityException) {
            throw E2eeException("invalid identity key", e)
        }
    }

    /**
     * e2ee.md 9: the first 20 hex digits of `SHA-256("photon/fp/v1" ‖ member_key)` in groups of four,
     * for two people to compare out of band.
     */
    fun securityCode(memberKey: ByteArray): String =
        Hex.encode(Crypto.sha256(E2ee.FP_INFO, memberKey)).substring(0, 20).chunked(4).joinToString(" ")
}

/** What a sealed box carries (e2ee.md 3): one epoch's key `K_e` and the session's tag key `D`. 69 bytes encoded. */
class Bundle(val epoch: Int, key: ByteArray, tagKey: ByteArray) {
    init {
        require(key.size == E2ee.KEY_LEN && tagKey.size == E2ee.KEY_LEN) { "bundle keys are 32 bytes" }
    }

    private val k = key.copyOf()
    private val d = tagKey.copyOf()

    val key: ByteArray get() = k.copyOf()
    val tagKey: ByteArray get() = d.copyOf()

    fun encode(): ByteArray = ByteBuffer.allocate(E2ee.BUNDLE_LEN)
        .put(E2ee.BUNDLE_VERSION.toByte()).putInt(epoch).put(k).put(d).array()

    /** A fresh key for the next epoch, keeping the tag key so dedupe works across epochs. */
    fun next(): Bundle = withNewKey(epoch + 1, d)

    fun sameTagKey(other: Bundle): Boolean = d.contentEquals(other.d)

    override fun equals(other: Any?): Boolean =
        other is Bundle && other.epoch == epoch && other.k.contentEquals(k) && other.d.contentEquals(d)

    override fun hashCode(): Int = 31 * (31 * epoch + k.contentHashCode()) + d.contentHashCode()

    /** Never prints key material. */
    override fun toString(): String = "Bundle(epoch=${epoch.toUInt()})"

    companion object {
        fun decode(raw: ByteArray): Bundle {
            if (raw.size != E2ee.BUNDLE_LEN || raw[0].toInt() != E2ee.BUNDLE_VERSION) {
                throw E2eeException("not a version-1 key bundle")
            }
            val epoch = ByteBuffer.wrap(raw, 1, 4).int
            return Bundle(epoch, raw.copyOfRange(5, 37), raw.copyOfRange(37, 69))
        }

        /** Epoch 1 of a new session: made by the creator's phone, never sent anywhere unsealed. */
        fun newSession(): Bundle = Bundle(1, Crypto.randomBytes(E2ee.KEY_LEN), Crypto.randomBytes(E2ee.KEY_LEN))

        /** A fresh random key for [epoch], with the session's tag key (rotation, e2ee.md 5.4). */
        fun withNewKey(epoch: Int, tagKey: ByteArray): Bundle = Bundle(epoch, Crypto.randomBytes(E2ee.KEY_LEN), tagKey)
    }
}

/**
 * Sealing a bundle for one member (e2ee.md 4). The box is `E ‖ AES-256-GCM(key, 0^12, bundle, aad)`, 117
 * bytes, where `key = HKDF-SHA256(X25519(e, R), salt = E ‖ R, info = "photon/seal/v1")` and the AAD binds
 * the box to one session, one recipient and one epoch.
 */
object Seal {
    /** [ephemeralPrivate] is only for test vectors; a real seal always makes a fresh one-time key. */
    fun seal(
        bundle: Bundle,
        recipientKey: ByteArray,
        sessionId: String,
        memberId: String,
        ephemeralPrivate: ByteArray? = null,
    ): ByteArray {
        if (recipientKey.size != E2ee.KEY_LEN) throw E2eeException("a member key is 32 bytes")
        val eph = ephemeralPrivate ?: Identity.newPrivateKey()
        val ephPub = Identity.publicKey(eph)
        val shared = exchange(eph, recipientKey, "invalid member key")
        val key = sealKey(shared, ephPub, recipientKey)
        val sealed = Crypto.seal(key, ByteArray(E2ee.NONCE_LEN), bundle.encode(), aad(sessionId, memberId, bundle.epoch))
        return ephPub + sealed
    }

    /** Opens a box addressed to this phone ([privateKey]) for [memberId] in [sessionId] at [epoch]. */
    fun open(box: ByteArray, privateKey: ByteArray, sessionId: String, memberId: String, epoch: Int): Bundle {
        if (box.size != E2ee.BOX_LEN) throw E2eeException("a sealed box is 117 bytes")
        val ephPub = box.copyOfRange(0, E2ee.KEY_LEN)
        val shared = exchange(privateKey, ephPub, "invalid ephemeral key")
        val key = sealKey(shared, ephPub, Identity.publicKey(privateKey))
        val raw = try {
            Crypto.open(key, ByteArray(E2ee.NONCE_LEN), box, E2ee.KEY_LEN, box.size - E2ee.KEY_LEN, aad(sessionId, memberId, epoch))
        } catch (e: GeneralSecurityException) {
            throw E2eeException("sealed box does not open: wrong recipient, session or epoch, or tampered", e)
        }
        val bundle = Bundle.decode(raw)
        if (bundle.epoch != epoch) throw E2eeException("sealed box carries another epoch")
        return bundle
    }

    /** `"photon/seal/v1|" ‖ sid ‖ "|" ‖ uid ‖ "|" ‖ decimal(epoch)`, UTF-8. */
    internal fun aad(sessionId: String, memberId: String, epoch: Int): ByteArray =
        "photon/seal/v1|$sessionId|$memberId|${epoch.toUInt()}".toByteArray(Charsets.UTF_8)

    private fun exchange(privateKey: ByteArray, publicKey: ByteArray, problem: String): ByteArray = try {
        X25519.computeSharedSecret(privateKey, publicKey)
    } catch (e: GeneralSecurityException) {
        // Tink refuses the small-order points for which OpenSSL (the Python reference) yields zero.
        throw E2eeException(problem, e)
    } catch (e: IllegalStateException) {
        throw E2eeException(problem, e)
    }

    private fun sealKey(shared: ByteArray, ephPub: ByteArray, recipientPub: ByteArray): ByteArray {
        if (shared.all { it.toInt() == 0 }) throw E2eeException("degenerate X25519 shared secret")
        return Hkdf.computeHkdf("HMACSHA256", shared, ephPub + recipientPub, E2ee.SEAL_INFO, E2ee.KEY_LEN)
    }
}

/**
 * The photo tag (e2ee.md 6.2): `HMAC-SHA256(D, "photon/tag/v1" ‖ SHA-256(original))`. The same for the same
 * photo whoever uploads it, and meaningless without `D`.
 */
object PhotoTag {
    fun of(tagKey: ByteArray, original: ByteArray): ByteArray = fromDigest(tagKey, Crypto.sha256(original))

    /** From `SHA-256(original)`, for originals hashed while streaming. */
    fun fromDigest(tagKey: ByteArray, originalSha256: ByteArray): ByteArray {
        require(originalSha256.size == 32) { "a SHA-256 digest is 32 bytes" }
        return Crypto.hmacSha256(tagKey, E2ee.TAG_INFO + originalSha256)
    }
}
