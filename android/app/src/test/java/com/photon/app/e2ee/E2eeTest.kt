package com.photon.app.e2ee

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Round trips, tampering and the validation rules of `e2ee.py`, with random keys. */
class E2eeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sid = "S1"
    private val bob = Identity.newPrivateKey()
    private val bobPub = Identity.publicKey(bob)
    private val bundle = Bundle.newSession()
    private val original = Random(1).nextBytes(5000)
    private val thumb = "thumb".repeat(30).toByteArray()
    private val preview = "preview".repeat(100).toByteArray()

    private fun file(b: Bundle = bundle, preview: ByteArray = this.preview) =
        PhotoFile.encrypt(b, original, "image/png", 640, 480, 1_700_000_000_000, thumb, preview)

    private fun keys(vararg bs: Bundle) = bs.associate { it.epoch to it.key }

    // ------------------------------------------------------------------ sealing

    @Test
    fun sealOpenRoundTripWithAFreshEphemeralKeyEachTime() {
        val a = Seal.seal(bundle, bobPub, sid, "bob")
        val b = Seal.seal(bundle, bobPub, sid, "bob")
        assertEquals(E2ee.BOX_LEN, a.size)
        assertFalse("each box has its own one-time key", a.contentEquals(b))
        assertEquals(bundle, Seal.open(a, bob, sid, "bob", 1))
        assertEquals(bundle, Seal.open(b, bob, sid, "bob", 1))
    }

    @Test
    fun aBoxIsBoundToItsRecipientSessionAndEpoch() {
        val box = Seal.seal(bundle, bobPub, sid, "bob")
        assertThrows(E2eeException::class.java) { Seal.open(box, Identity.newPrivateKey(), sid, "bob", 1) }
        assertThrows(E2eeException::class.java) { Seal.open(box, bob, "S2", "bob", 1) }
        assertThrows(E2eeException::class.java) { Seal.open(box, bob, sid, "carol", 1) }
        assertThrows(E2eeException::class.java) { Seal.open(box, bob, sid, "bob", 2) }
    }

    @Test
    fun aTamperedBoxDoesNotOpen() {
        val box = Seal.seal(bundle, bobPub, sid, "bob")
        for (i in listOf(0, 31, 32, 60, 100, 116)) {
            val bad = box.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows("byte $i", E2eeException::class.java) { Seal.open(bad, bob, sid, "bob", 1) }
        }
        assertThrows(E2eeException::class.java) { Seal.open(box.copyOf(116), bob, sid, "bob", 1) }
    }

    @Test
    fun anInvalidMemberKeyIsRefused() {
        assertThrows(E2eeException::class.java) { Seal.seal(bundle, ByteArray(31), sid, "bob") }
        // The all-zero point: small order, so the shared secret would be zero.
        assertThrows(E2eeException::class.java) { Seal.seal(bundle, ByteArray(32), sid, "bob") }
    }

    @Test
    fun aBoxOpensOnlyAtItsOwnEpoch() {
        val two = Bundle(2, bundle.key, bundle.tagKey)
        // The AAD carries the epoch: a box for epoch 2 can't be passed off as epoch 1's.
        val box = Seal.seal(two, bobPub, sid, "bob")
        assertEquals(two, Seal.open(box, bob, sid, "bob", 2))
        assertThrows(E2eeException::class.java) { Seal.open(box, bob, sid, "bob", 1) }
    }

    @Test
    fun bundleValidationAndRotationKeepTheTagKey() {
        assertThrows(E2eeException::class.java) { Bundle.decode(ByteArray(68)) }
        assertThrows(E2eeException::class.java) { Bundle.decode(bundle.encode().also { it[0] = 2 }) }
        val next = bundle.next()
        assertEquals(2, next.epoch)
        assertTrue(next.sameTagKey(bundle))
        assertFalse(next.key.contentEquals(bundle.key))
        assertEquals("Bundle(epoch=1)", bundle.toString())
    }

    @Test
    fun securityCodeIsFiveGroupsOfFour() {
        val code = Identity.securityCode(bobPub)
        assertTrue(code, Regex("[0-9a-f]{4}( [0-9a-f]{4}){4}").matches(code))
    }

    // ------------------------------------------------------------------ the photo file

    @Test
    fun photoRoundTripAndSizes() {
        val f = file()
        val header = Header.parse(f)
        assertEquals(f.size.toLong(), header.size)
        assertEquals(PhotoFile.encryptedSize(PhotoFile.metaJson("image/png", 640, 480, 1_700_000_000_000).size, thumb.size, preview.size, original.size.toLong()), f.size.toLong())
        val p = PhotoFile.decrypt(f, keys(bundle))
        assertArrayEquals(original, p.original)
        assertArrayEquals(thumb, p.thumb)
        assertArrayEquals(preview, p.preview)
        assertEquals("image/png", p.mime)
        assertEquals(640, p.width)
        assertEquals(480, p.height)
        assertEquals(1_700_000_000_000, p.takenAtMs)
    }

    @Test
    fun theSamePhotoTwiceIsTwoFilesWithOneTag() {
        assertFalse(file().contentEquals(file()))
        assertArrayEquals(PhotoTag.of(bundle.tagKey, original), PhotoTag.of(bundle.next().tagKey, original))
        assertFalse(PhotoTag.of(bundle.tagKey, original).contentEquals(PhotoTag.of(Bundle.newSession().tagKey, original)))
    }

    @Test
    fun anEmptyPreviewDecryptsAsNone() {
        val f = file(preview = ByteArray(0))
        val header = Header.parse(f)
        assertTrue(header.previewIsEmpty)
        val p = PhotoFile.decrypt(f.copyOf(header.prefixLen("preview").toInt()), keys(bundle))
        assertNull(p.preview)
        assertArrayEquals(thumb, p.thumb)
    }

    @Test
    fun everyByteOfTheFileIsProtected() {
        val f = file()
        val header = Header.parse(f)
        // Magic and version are format checks; every other byte is covered by an AEAD tag.
        for (i in listOf(5, 8, 9, 20, 21, 68, 70, 74, 78, 84, 85, header.offset(Section.THUMB).toInt() + 3, header.offset(Section.PREVIEW).toInt(), f.size - 1)) {
            val bad = f.copyOf().also { it[i] = (it[i].toInt() xor 0x10).toByte() }
            assertThrows("byte $i", E2eeException::class.java) { PhotoFile.decrypt(bad, keys(bundle, Bundle(2, bundle.key, bundle.tagKey))) }
        }
    }

    @Test
    fun wrongOrMissingEpochKey() {
        val f = file()
        assertThrows(E2eeException::class.java) { PhotoFile.decrypt(f, mapOf(1 to Bundle.newSession().key)) }
        val missing = assertThrows(MissingEpochKeyException::class.java) { PhotoFile.decrypt(f, mapOf(2 to bundle.key)) }
        assertEquals(1, missing.epoch)
    }

    @Test
    fun truncatedBeforeTheMetadata() {
        val f = file()
        val e = assertThrows(E2eeException::class.java) { PhotoFile.decrypt(f.copyOf(E2ee.HEADER_LEN + 10), keys(bundle)) }
        assertEquals("file is truncated before its metadata", e.message)
        // A cut inside the thumbnail leaves only the metadata.
        val p = PhotoFile.decrypt(f.copyOf(Header.parse(f).end(Section.THUMB).toInt() - 1), keys(bundle))
        assertNull(p.thumb)
    }

    @Test
    fun headerValidationMatchesPython() {
        val f = file()
        fun withHeader(edit: (ByteArray) -> Unit) = f.copyOf().also(edit)
        fun u32(b: ByteArray, at: Int, v: Long) { for (k in 0..3) b[at + k] = (v shr (24 - 8 * k)).toByte() }

        assertEquals("too short for a PHOTON file header", assertThrows(E2eeException::class.java) { Header.parse(f.copyOf(84)) }.message)
        assertThrows(E2eeException::class.java) { Header.parse(withHeader { it[0] = 'X'.code.toByte() }) }
        assertThrows(E2eeException::class.java) { Header.parse(withHeader { it[4] = 2 }) }
        assertEquals("epoch must be at least 1", assertThrows(E2eeException::class.java) { Header.parse(withHeader { u32(it, 5, 0) }) }.message)
        assertEquals("section lengths are invalid", assertThrows(E2eeException::class.java) { Header.parse(withHeader { u32(it, 77, 15) }) }.message)
        assertEquals("section lengths are invalid", assertThrows(E2eeException::class.java) { Header.parse(withHeader { u32(it, 69, 4096L + 17) }) }.message)
        assertThrows(E2eeException::class.java) { Header.parse(withHeader { u32(it, 73, 16) }) }
        assertThrows(E2eeException::class.java) { Header.parse(withHeader { u32(it, 81, 16) }) }
        // An empty preview is fine, and a huge (uint32) section length is read unsigned.
        assertTrue(Header.parse(withHeader { u32(it, 77, 16) }).previewIsEmpty)
        assertEquals(0xFFFF_FFF0L, Header.parse(withHeader { u32(it, 81, 0xFFFF_FFF0L) }).lengths[3])
        // A uint32 epoch above Int.MAX_VALUE keeps its bit pattern, as Wire's uint32 does.
        assertEquals(0x8000_0001L.toInt(), Header.parse(withHeader { u32(it, 5, 0x8000_0001L) }).epoch)
    }

    @Test
    fun buildRefusesWhatPythonRefuses() {
        assertThrows(E2eeException::class.java) { PhotoFile.encrypt(bundle, original, "image/gif", 1, 1, 0, thumb) }
        assertThrows(E2eeException::class.java) { PhotoFile.encrypt(bundle, original, "image/png", 1, 1, 0, ByteArray(0)) }
        assertThrows(E2eeException::class.java) { PhotoFile.encrypt(bundle, ByteArray(0), "image/png", 1, 1, 0, thumb) }
    }

    @Test
    fun metadataMustNameASupportedType() {
        // Hand-build a file whose (authentic) metadata names image/gif.
        val photoKey = Random(3).nextBytes(32)
        val nonce = Random(4).nextBytes(12)
        val meta = "{\"mime\":\"image/gif\",\"v\":1}".toByteArray()
        val prefix = E2ee.MAGIC + byteArrayOf(1) + Crypto.u32(1) + nonce
        var header = prefix + Crypto.seal(bundle.key, nonce, photoKey, prefix)
        listOf(meta, thumb, ByteArray(0), original).forEach { header += Crypto.u32(it.size + 16) }
        val body = listOf(meta, thumb, ByteArray(0), original).mapIndexed { i, p ->
            Crypto.seal(photoKey, ByteArray(12).also { it[11] = i.toByte() }, p, header)
        }
        val f = body.fold(header) { acc, b -> acc + b }
        val e = assertThrows(E2eeException::class.java) { PhotoFile.decrypt(f, keys(bundle)) }
        assertEquals("metadata names an unsupported image type", e.message)
    }

    @Test
    fun reencryptForTheNextEpoch() {
        val next = bundle.next()
        val out = ByteArrayOutputStream()
        val header = PhotoFile.reencryptTo(out, file(), keys(bundle), next)
        assertEquals(2, header.epoch)
        val p = PhotoFile.decrypt(out.toByteArray(), keys(next))
        assertArrayEquals(original, p.original)
        assertArrayEquals(thumb, p.thumb)
        assertArrayEquals(preview, p.preview)
        assertEquals(640, p.width)
        assertThrows(MissingEpochKeyException::class.java) { PhotoFile.decrypt(out.toByteArray(), keys(bundle)) }
    }

    @Test
    fun decryptFromDiskReadsOnlyWhatIsAsked() {
        val f = tmp.newFile("photo.phe").apply { writeBytes(file()) }
        val p = PhotoFile.decrypt(f, keys(bundle), setOf(Section.ORIGINAL))
        assertArrayEquals(original, p.original)
        assertNull(p.thumb)
        assertNull(p.preview)
        assertEquals("image/png", p.mime)
        // A tampered original section is caught on disk too.
        val bad = file().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }
        val g = tmp.newFile("bad.phe").apply { writeBytes(bad) }
        assertThrows(E2eeException::class.java) { PhotoFile.decrypt(g, keys(bundle), setOf(Section.ORIGINAL)) }
    }

    // ------------------------------------------------------------------ JSON

    @Test
    fun jsonReadsWhatPythonWrites() {
        @Suppress("UNCHECKED_CAST")
        val v = Json.parse(" {\"a\": [1, -2.5, true, null, \"x\\u00e9\\n\"], \"b\": {}, \"a2\": 12345678901234} ") as Map<String, Any?>
        assertEquals(listOf(1L, -2.5, true, null, "xé\n"), v["a"])
        assertEquals(emptyMap<String, Any?>(), v["b"])
        assertEquals(12345678901234L, v["a2"])
        assertEquals("\"caf\\u00e9 \\\"q\\\"\"", Json.quote("café \"q\""))
        for (bad in listOf("", "{", "{\"a\":}", "[1,]", "01", "\"\\x\"", "{} x", "nul")) {
            assertThrows(bad, Json.JsonException::class.java) { Json.parse(bad) }
        }
        assertNotEquals(null, Json.parse("[[[[1]]]]"))
    }
}
