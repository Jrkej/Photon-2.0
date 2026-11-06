package com.photon.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The uploader's own image checks (e2ee.md 7 step 2), as in the reference client's `images.py`. */
class ImageTypesTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 16)
    private val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private fun webp(chunk: String, flags: Int = 0) = ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP") + ascii(chunk) + bytes(10, 0, 0, 0, flags, 0, 0, 0)
    private fun ftyp(brand: String) = bytes(0, 0, 0, 24) + ascii("ftyp") + ascii(brand) + bytes(0, 0, 0, 0)

    private fun pngChunk(type: String, data: ByteArray = ByteArray(0)) =
        bytes(0, 0, 0, data.size) + ascii(type) + data + bytes(0, 0, 0, 0)

    @Test
    fun detectsTheFiveTypesByTheirMagicBytes() {
        assertEquals(ImageTypes.JPEG, ImageTypes.detect(jpeg))
        assertEquals(ImageTypes.PNG, ImageTypes.detect(png))
        assertEquals(ImageTypes.WEBP, ImageTypes.detect(webp("VP8 ")))
        assertEquals(ImageTypes.HEIC, ImageTypes.detect(ftyp("heic")))
        assertEquals(ImageTypes.HEIC, ImageTypes.detect(ftyp("hevx")))
        assertEquals(ImageTypes.HEIF, ImageTypes.detect(ftyp("mif1")))
        assertNull(ImageTypes.detect(ftyp("avif")))
        assertNull(ImageTypes.detect(ascii("GIF89a")))
        assertNull(ImageTypes.detect(ByteArray(0)))
        assertNull(ImageTypes.detect(bytes(0xFF, 0xD8)))
    }

    @Test
    fun heicAndHeifAreTheSameType() {
        assertTrue(ImageTypes.sameType(ImageTypes.HEIC, ImageTypes.HEIF))
        assertTrue(ImageTypes.sameType(ImageTypes.HEIF, ImageTypes.HEIC))
        assertTrue(ImageTypes.sameType(ImageTypes.JPEG, ImageTypes.JPEG))
        assertFalse(ImageTypes.sameType(ImageTypes.JPEG, ImageTypes.PNG))
        assertFalse(ImageTypes.sameType(ImageTypes.HEIC, ImageTypes.JPEG))
    }

    @Test
    fun animatedWebpAndPngAreMultiFrame() {
        assertTrue(ImageTypes.isMultiFrame(ImageTypes.WEBP, webp("VP8X", flags = 0x02)))
        assertFalse(ImageTypes.isMultiFrame(ImageTypes.WEBP, webp("VP8X", flags = 0x10)))
        assertFalse(ImageTypes.isMultiFrame(ImageTypes.WEBP, webp("VP8L")))
        val ihdr = pngChunk("IHDR", ByteArray(13))
        assertTrue(ImageTypes.isMultiFrame(ImageTypes.PNG, png + ihdr + pngChunk("acTL", ByteArray(8)) + pngChunk("IDAT", ByteArray(4))))
        assertFalse(ImageTypes.isMultiFrame(ImageTypes.PNG, png + ihdr + pngChunk("IDAT", ByteArray(4)) + pngChunk("acTL", ByteArray(8))))
        assertFalse(ImageTypes.isMultiFrame(ImageTypes.PNG, png + ihdr))
        assertFalse(ImageTypes.isMultiFrame(ImageTypes.JPEG, jpeg))
    }

    @Test
    fun mediaStoreSpellingsAreNormalised() {
        assertEquals(ImageTypes.JPEG, ImageTypes.normalize("image/JPG"))
        assertEquals(ImageTypes.HEIF, ImageTypes.normalize("image/heif"))
        assertNull(ImageTypes.normalize("image/gif"))
        assertNull(ImageTypes.normalize(null))
    }
}
