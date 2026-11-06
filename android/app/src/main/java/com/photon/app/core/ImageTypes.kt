package com.photon.app.core

/**
 * The checks the image server no longer does (e2ee.md 7 step 2), on raw bytes: the type from its magic bytes,
 * whether that matches the declared type, and whether the file holds more than one frame. The same rules as the
 * reference client (`src/photon/client/images.py`); decoding and the pixel limit need Android and live with
 * the thumbnail code.
 */
object ImageTypes {
    const val JPEG = "image/jpeg"
    const val PNG = "image/png"
    const val WEBP = "image/webp"
    const val HEIC = "image/heic"
    const val HEIF = "image/heif"

    /** At most 100 million pixels. */
    const val MAX_PIXELS = 100_000_000L

    private val HEIC_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis")
    private val HEIF_BRANDS = setOf("mif1", "msf1")

    /** The type the bytes say they are, or null for anything but JPEG, PNG, WebP, HEIC and HEIF. */
    fun detect(head: ByteArray): String? = when {
        head.startsWith(0xFF, 0xD8, 0xFF) -> JPEG
        head.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
        head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WEBP" -> WEBP
        head.ascii(4, 4) == "ftyp" -> when (head.ascii(8, 4)) {
            in HEIC_BRANDS -> HEIC
            in HEIF_BRANDS -> HEIF
            else -> null
        }
        else -> null
    }

    /** HEIC and HEIF count as one type: phones label the same files either way. */
    fun sameType(declared: String, detected: String): Boolean =
        declared == detected || (declared in HEIF_FAMILY && detected in HEIF_FAMILY)

    /** An animated WebP (VP8X animation flag) or an animated PNG (an `acTL` chunk before the image data). */
    fun isMultiFrame(type: String, data: ByteArray): Boolean = when (type) {
        WEBP -> data.ascii(12, 4) == "VP8X" && data.size > 20 && (data[20].toInt() and ANIMATION_FLAG) != 0
        PNG -> pngHasAnimationControl(data)
        else -> false
    }

    /** "image/jpg" and other spellings MediaStore reports, mapped to the five types; null when unsupported. */
    fun normalize(mime: String?): String? = when (mime?.lowercase()?.trim()) {
        JPEG, "image/jpg", "image/pjpeg" -> JPEG
        PNG -> PNG
        WEBP -> WEBP
        HEIC -> HEIC
        HEIF -> HEIF
        else -> null
    }

    private val HEIF_FAMILY = setOf(HEIC, HEIF)
    private const val ANIMATION_FLAG = 0x02

    private fun pngHasAnimationControl(data: ByteArray): Boolean {
        var at = 8L
        while (at + 8 <= data.size) {
            val length = ((data[at.toInt()].toLong() and 0xFF) shl 24) or ((data[at.toInt() + 1].toLong() and 0xFF) shl 16) or
                ((data[at.toInt() + 2].toLong() and 0xFF) shl 8) or (data[at.toInt() + 3].toLong() and 0xFF)
            when (data.ascii(at.toInt() + 4, 4)) {
                "acTL" -> return true
                "IDAT", "IEND" -> return false
            }
            at += 12 + length // length, type, data, CRC
        }
        return false
    }

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        size >= bytes.size && bytes.indices.all { (this[it].toInt() and 0xFF) == bytes[it] }

    private fun ByteArray.ascii(offset: Int, length: Int): String? =
        if (offset < 0 || offset + length > size) null else String(this, offset, length, Charsets.ISO_8859_1)
}
