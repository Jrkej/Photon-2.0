package com.photon.app.e2ee

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

/**
 * The shared test vectors, `tests/vectors/e2ee_v1.json` (written by `tools/e2ee_vectors.py` from the Python
 * reference), read in place: every value must come out byte for byte.
 */
class E2eeVectorsTest {
    @Suppress("UNCHECKED_CAST")
    private val vectors: Map<String, Any?> = run {
        val text = javaClass.getResourceAsStream("/e2ee_v1.json")?.use { it.readBytes().decodeToString() }
            ?: error("e2ee_v1.json is not on the test classpath (tests/vectors is a test resource directory)")
        Json.parse(text) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun section(name: String) = vectors[name] as Map<String, Any?>
    private val identities = section("identities")
    private val seal = section("seal")
    private val photo = section("photo")

    private fun Map<String, Any?>.hex(key: String): ByteArray = Hex.decode(this[key] as String)
    private fun Map<String, Any?>.str(key: String): String = this[key] as String
    private fun Map<String, Any?>.int(key: String): Int = (this[key] as Long).toInt()
    private fun Map<String, Any?>.long(key: String): Long = this[key] as Long

    private val bundle get() = Bundle(seal.int("epoch"), seal.hex("epoch_key"), seal.hex("tag_key"))

    @Test
    fun publicKeysComeFromThePrivateKeys() {
        assertEquals(identities.str("alice_public"), Hex.encode(Identity.publicKey(identities.hex("alice_private"))))
        assertEquals(identities.str("bob_public"), Hex.encode(Identity.publicKey(identities.hex("bob_private"))))
    }

    @Test
    fun securityCode() {
        assertEquals(identities.str("bob_security_code"), Identity.securityCode(identities.hex("bob_public")))
    }

    @Test
    fun bundleEncodesAndDecodes() {
        assertEquals(seal.str("bundle"), Hex.encode(bundle.encode()))
        assertEquals(bundle, Bundle.decode(seal.hex("bundle")))
    }

    @Test
    fun sealWithTheFixedEphemeralKeyGivesTheBox() {
        val box = Seal.seal(
            bundle,
            recipientKey = identities.hex("bob_public"),
            sessionId = seal.str("session_id"),
            memberId = seal.str("member_id"),
            ephemeralPrivate = seal.hex("ephemeral_private"),
        )
        assertEquals(E2ee.BOX_LEN, box.size)
        assertEquals(seal.str("box"), Hex.encode(box))
    }

    @Test
    fun theBoxOpensWithBobsKey() {
        val opened = Seal.open(seal.hex("box"), identities.hex("bob_private"), seal.str("session_id"), seal.str("member_id"), seal.int("epoch"))
        assertEquals(bundle, opened)
        assertArrayEquals(seal.hex("epoch_key"), opened.key)
        assertArrayEquals(seal.hex("tag_key"), opened.tagKey)
    }

    @Test
    fun photoFileFromTheFixedPhotoKeyAndWrapNonce() {
        val file = PhotoFile.encrypt(
            Bundle(photo.int("epoch"), photo.hex("epoch_key"), photo.hex("tag_key")),
            original = photo.hex("original"),
            mime = photo.str("mime"),
            width = photo.int("width"),
            height = photo.int("height"),
            takenAtMs = photo.long("taken_at_ms"),
            thumb = photo.hex("thumb"),
            preview = photo.hex("preview"),
            photoKey = photo.hex("photo_key"),
            wrapNonce = photo.hex("wrap_nonce"),
        )
        assertEquals(photo.str("file"), Hex.encode(file))
        assertEquals(photo.str("file_sha256"), Hex.encode(MessageDigest.getInstance("SHA-256").digest(file)))
    }

    @Test
    fun metadataIsPythonsSortedCompactJson() {
        val meta = PhotoFile.metaJson(photo.str("mime"), photo.int("width"), photo.int("height"), photo.long("taken_at_ms"))
        assertEquals(photo.str("meta_json"), meta.decodeToString())
    }

    @Test
    fun headerLengthsAndPrefixLengths() {
        val header = Header.parse(photo.hex("file"))
        @Suppress("UNCHECKED_CAST")
        val expected = (photo["header_section_lengths"] as List<Long>)
        assertEquals(expected, header.lengths)
        assertEquals(photo.long("prefix_len_thumb"), header.prefixLen("thumb"))
        assertEquals(photo.long("prefix_len_preview"), header.prefixLen("preview"))
        assertEquals(photo.hex("file").size.toLong(), header.prefixLen("full"))
        assertEquals(photo.hex("file").size.toLong(), header.size)
        assertEquals(photo.int("epoch"), header.epoch)
    }

    @Test
    fun theFileDecrypts() {
        val keys = mapOf(photo.int("epoch") to photo.hex("epoch_key"))
        val p = PhotoFile.decrypt(photo.hex("file"), keys)
        assertArrayEquals(photo.hex("original"), p.original)
        assertArrayEquals(photo.hex("thumb"), p.thumb)
        assertArrayEquals(photo.hex("preview"), p.preview)
        assertEquals(photo.str("mime"), p.mime)
        assertEquals(photo.int("width"), p.width)
        assertEquals(photo.int("height"), p.height)
        assertEquals(photo.long("taken_at_ms"), p.takenAtMs)
        assertEquals(1L, p.meta["v"])
    }

    @Test
    fun servedPrefixesDecrypt() {
        val file = photo.hex("file")
        val keys = mapOf(photo.int("epoch") to photo.hex("epoch_key"))

        val thumb = PhotoFile.decrypt(file.copyOf(photo.int("prefix_len_thumb")), keys)
        assertArrayEquals(photo.hex("thumb"), thumb.thumb)
        assertNull(thumb.preview)
        assertNull(thumb.original)

        val preview = PhotoFile.decrypt(file.copyOf(photo.int("prefix_len_preview")), keys)
        assertArrayEquals(photo.hex("thumb"), preview.thumb)
        assertArrayEquals(photo.hex("preview"), preview.preview)
        assertNull(preview.original)
    }

    @Test
    fun photoTag() {
        assertEquals(photo.str("photo_tag"), Hex.encode(PhotoTag.of(photo.hex("tag_key"), photo.hex("original"))))
        val digest = MessageDigest.getInstance("SHA-256").digest(photo.hex("original"))
        assertEquals(photo.str("photo_tag"), Hex.encode(PhotoTag.fromDigest(photo.hex("tag_key"), digest)))
    }
}
