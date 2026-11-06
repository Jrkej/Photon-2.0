package com.photon.app.e2ee

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The other direction of the test vectors: a box sealed and a photo file built by this Kotlin code, written to
 * `app/build/e2ee-crosscheck.json` with every input the Python reference needs to open and decrypt them
 * (`e2ee.open_box`, `e2ee.decrypt_photo`, `e2ee.photo_tag`).
 *
 * Inputs are fixed (SHA-256 of labels, like `tools/e2ee_vectors.py`), so `box` and `file` are the same on every
 * run. `box_random` and `file_random` take the production path instead (a fresh one-time key, a fresh photo key
 * and wrap nonce) and differ per run; `file_random` has an empty preview.
 */
class E2eeCrossCheckTest {
    private fun h(label: String) = MessageDigest.getInstance("SHA-256").digest(label.toByteArray())

    @Test
    fun writeCrossCheckFile() {
        val recipientPrivate = h("kotlin crosscheck recipient")
        val recipientPublic = Identity.publicKey(recipientPrivate)
        val ephemeral = h("kotlin crosscheck ephemeral")
        val sid = "KOTLINcrosscheck0000001"
        val memberId = "33333333-3333-4333-8333-333333333333"
        val bundle = Bundle(3, h("kotlin crosscheck epoch 3 key"), h("kotlin crosscheck tag key"))

        val box = Seal.seal(bundle, recipientPublic, sid, memberId, ephemeralPrivate = ephemeral)
        val boxRandom = Seal.seal(bundle, recipientPublic, sid, memberId)

        val original = ByteArray(3000) { (it * 7 + 3).toByte() }
        val thumb = "kotlin thumbnail ".repeat(10).toByteArray()
        val preview = "kotlin preview ".repeat(40).toByteArray()
        val mime = "image/webp"
        val width = 1600
        val height = 1200
        val takenAtMs = 1_793_712_345_678L
        val photoKey = h("kotlin crosscheck photo key")
        val wrapNonce = h("kotlin crosscheck wrap nonce").copyOf(12)
        val file = PhotoFile.encrypt(bundle, original, mime, width, height, takenAtMs, thumb, preview, photoKey, wrapNonce)
        val fileRandom = PhotoFile.encrypt(bundle, original, mime, width, height, takenAtMs, thumb)

        // Kotlin opens its own output before Python is asked to.
        assertEquals(bundle, Seal.open(box, recipientPrivate, sid, memberId, 3))
        assertEquals(bundle, Seal.open(boxRandom, recipientPrivate, sid, memberId, 3))
        assertArrayEquals(original, PhotoFile.decrypt(file, mapOf(3 to bundle.key)).original)
        assertArrayEquals(original, PhotoFile.decrypt(fileRandom, mapOf(3 to bundle.key)).original)

        val sha = { b: ByteArray -> Hex.encode(MessageDigest.getInstance("SHA-256").digest(b)) }
        val fields = linkedMapOf<String, Any>(
            "description" to "Made by the Android app's E2eeCrossCheckTest (Kotlin). Open box and box_random with " +
                "recipient_private, session_id, member_id and epoch; decrypt file and file_random with {epoch: epoch_key}. " +
                "All bytes are lowercase hex.",
            "recipient_private" to Hex.encode(recipientPrivate),
            "recipient_public" to Hex.encode(recipientPublic),
            "recipient_security_code" to Identity.securityCode(recipientPublic),
            "session_id" to sid,
            "member_id" to memberId,
            "epoch" to 3,
            "epoch_key" to Hex.encode(bundle.key),
            "tag_key" to Hex.encode(bundle.tagKey),
            "bundle" to Hex.encode(bundle.encode()),
            "ephemeral_private" to Hex.encode(ephemeral),
            "box" to Hex.encode(box),
            "box_random" to Hex.encode(boxRandom),
            "mime" to mime,
            "width" to width,
            "height" to height,
            "taken_at_ms" to takenAtMs,
            "original" to Hex.encode(original),
            "thumb" to Hex.encode(thumb),
            "preview" to Hex.encode(preview),
            "photo_key" to Hex.encode(photoKey),
            "wrap_nonce" to Hex.encode(wrapNonce),
            "file" to Hex.encode(file),
            "file_sha256" to sha(file),
            "file_random" to Hex.encode(fileRandom),
            "file_random_sha256" to sha(fileRandom),
            "photo_tag" to Hex.encode(PhotoTag.of(bundle.tagKey, original)),
        )
        val json = fields.entries.joinToString(",\n", "{\n", "\n}\n") { (k, v) ->
            "  ${Json.quote(k)}: " + if (v is String) Json.quote(v) else v.toString()
        }
        val out = File("build/e2ee-crosscheck.json").absoluteFile
        out.parentFile?.mkdirs()
        out.writeText(json)

        // It parses back as JSON.
        @Suppress("UNCHECKED_CAST")
        val back = Json.parse(out.readText()) as Map<String, Any?>
        assertEquals(Hex.encode(box), back["box"])
        println("wrote ${out.path}")
    }
}
