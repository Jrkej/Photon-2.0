package com.photon.app.e2ee

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.GeneralSecurityException

/** The four sections of a photo file, in file order. The ordinal is the section's AES-GCM nonce. */
enum class Section { META, THUMB, PREVIEW, ORIGINAL }

/**
 * The file's plaintext header (e2ee.md 6.1), 85 bytes, which is also every section's AAD:
 *
 * ```
 * 0  magic "PHE1" · 4  version 1 · 5  epoch · 9  wrap nonce (12) · 21  locked photo key (48)
 * 69 meta length · 73 thumbnail length · 77 preview length · 81 original length   (ciphertext, with tags)
 * ```
 */
class Header private constructor(
    private val bytes: ByteArray,
    val epoch: Int,
    /** Ciphertext lengths of meta, thumbnail, preview and original. */
    val lengths: List<Long>,
) {
    val raw: ByteArray get() = bytes.copyOf()
    val size: Long get() = E2ee.HEADER_LEN + lengths.sum()

    fun offset(section: Section): Long = E2ee.HEADER_LEN + lengths.take(section.ordinal).sum()

    fun end(section: Section): Long = offset(section) + lengths[section.ordinal]

    /** Bytes a receiver needs for a served size (`thumb`, `preview`, anything else is the whole file). */
    fun prefixLen(size: String): Long = when (size) {
        "thumb" -> end(Section.THUMB)
        "preview" -> end(Section.PREVIEW)
        else -> this.size
    }

    /** True when the uploader sent no preview (the original is small enough to be its own). */
    val previewIsEmpty: Boolean get() = lengths[Section.PREVIEW.ordinal] == E2ee.TAG_LEN.toLong()

    internal val wrapNonce: ByteArray get() = bytes.copyOfRange(9, 21)
    internal val lockedKey: ByteArray get() = bytes.copyOfRange(21, 69)
    internal val prefix: ByteArray get() = bytes.copyOf(E2ee.PREFIX_LEN)
    internal val aad: ByteArray get() = bytes

    companion object {
        /** Parses and checks the header, as Python's `parse_header` does. Needs only the first 85 bytes, never a key. */
        fun parse(data: ByteArray): Header {
            if (data.size < E2ee.HEADER_LEN) throw E2eeException("too short for a PHOTON file header")
            if (!E2ee.MAGIC.indices.all { data[it] == E2ee.MAGIC[it] } || data[4].toInt() != E2ee.FILE_VERSION) {
                throw E2eeException("not a PHOTON version-1 encrypted file")
            }
            val epoch = Crypto.readU32(data, 5)
            val lengths = List(4) { Crypto.readU32(data, 69 + 4 * it) }
            if (epoch < 1) throw E2eeException("epoch must be at least 1")
            if (lengths.any { it < E2ee.TAG_LEN } || lengths[0] > E2ee.MAX_META_LEN) {
                throw E2eeException("section lengths are invalid")
            }
            if (lengths[1] == E2ee.TAG_LEN.toLong() || lengths[3] == E2ee.TAG_LEN.toLong()) {
                throw E2eeException("the thumbnail and the original must not be empty")
            }
            return Header(data.copyOf(E2ee.HEADER_LEN), epoch.toInt(), lengths)
        }
    }
}

/** A decrypted file or prefix. Sections that weren't in the bytes are null; an empty preview is null too. */
class Photo(
    val epoch: Int,
    val meta: Map<String, Any?>,
    val thumb: ByteArray?,
    val preview: ByteArray?,
    val original: ByteArray?,
) {
    val mime: String get() = meta["mime"] as String
    val width: Int get() = (meta["width"] as? Number)?.toInt() ?: 0
    val height: Int get() = (meta["height"] as? Number)?.toInt() ?: 0
    val takenAtMs: Long get() = (meta["taken_at_ms"] as? Number)?.toLong() ?: 0
}

/** Building and reading the encrypted photo file (e2ee.md 6). */
object PhotoFile {
    /** `{"height":…,"mime":…,"taken_at_ms":…,"v":1,"width":…}`: Python's `json.dumps(sort_keys=True)`, compact. */
    fun metaJson(mime: String, width: Int, height: Int, takenAtMs: Long): ByteArray =
        "{\"height\":$height,\"mime\":${Json.quote(mime)},\"taken_at_ms\":$takenAtMs,\"v\":1,\"width\":$width}"
            .toByteArray(Charsets.UTF_8)

    /** The file's size for these plaintext section sizes: the header plus each section and its tag. */
    fun encryptedSize(metaLen: Int, thumbLen: Int, previewLen: Int, originalLen: Long): Long =
        E2ee.HEADER_LEN + metaLen + thumbLen + previewLen + originalLen + 4L * E2ee.TAG_LEN

    /**
     * Builds the encrypted file. Every file gets a fresh random photo key, so the same photo encrypted twice,
     * even on two phones, never reuses an AES-GCM key and nonce. [photoKey] and [wrapNonce] exist only for
     * test vectors.
     */
    fun encrypt(
        bundle: Bundle,
        original: ByteArray,
        mime: String,
        width: Int,
        height: Int,
        takenAtMs: Long,
        thumb: ByteArray,
        preview: ByteArray = ByteArray(0),
        photoKey: ByteArray? = null,
        wrapNonce: ByteArray? = null,
    ): ByteArray = ByteArrayOutputStream().also {
        encryptTo(it, bundle, original, mime, width, height, takenAtMs, thumb, preview, photoKey, wrapNonce)
    }.toByteArray()

    /** [encrypt], written to [out] section by section, so a large original is never copied into one array. */
    fun encryptTo(
        out: OutputStream,
        bundle: Bundle,
        original: ByteArray,
        mime: String,
        width: Int,
        height: Int,
        takenAtMs: Long,
        thumb: ByteArray,
        preview: ByteArray = ByteArray(0),
        photoKey: ByteArray? = null,
        wrapNonce: ByteArray? = null,
    ): Header {
        if (mime !in E2ee.INNER_MIMES) throw E2eeException("unsupported image type $mime")
        if (thumb.isEmpty() || original.isEmpty()) throw E2eeException("the thumbnail and the original must not be empty")
        val key = photoKey ?: Crypto.randomBytes(E2ee.KEY_LEN)
        val nonce = wrapNonce ?: Crypto.randomBytes(E2ee.NONCE_LEN)
        require(key.size == E2ee.KEY_LEN && nonce.size == E2ee.NONCE_LEN) { "a photo key is 32 bytes, a wrap nonce 12" }
        val meta = metaJson(mime, width, height, takenAtMs)
        val plaintexts = listOf(meta, thumb, preview, original)
        val prefix = E2ee.MAGIC + byteArrayOf(E2ee.FILE_VERSION.toByte()) + Crypto.u32(bundle.epoch) + nonce
        val locked = Crypto.seal(bundle.key, nonce, key, prefix)
        var header = prefix + locked
        plaintexts.forEach { header += Crypto.u32(it.size + E2ee.TAG_LEN) }
        out.write(header)
        plaintexts.forEachIndexed { i, p -> out.write(Crypto.seal(key, sectionNonce(i), p, header)) }
        return Header.parse(header)
    }

    /** Unlocks the file's photo key with the epoch key the header names. */
    fun unlockPhotoKey(header: Header, keys: Map<Int, ByteArray>): ByteArray {
        val key = keys[header.epoch] ?: throw MissingEpochKeyException(header.epoch)
        return try {
            Crypto.open(key, header.wrapNonce, header.lockedKey, 0, E2ee.KEY_LEN + E2ee.TAG_LEN, header.prefix)
        } catch (e: GeneralSecurityException) {
            throw E2eeException("photo key does not unlock: wrong session key or tampered header", e)
        }
    }

    /**
     * Decrypts a whole file or a prefix (as served for `size=thumb` or `preview`), like Python's `decrypt_photo`.
     * [keys] maps epoch to epoch key.
     */
    fun decrypt(data: ByteArray, keys: Map<Int, ByteArray>): Photo {
        val header = Header.parse(data)
        val photoKey = unlockPhotoKey(header, keys)
        val out = arrayOfNulls<ByteArray>(4)
        for (section in Section.entries) {
            val start = header.offset(section)
            val end = header.end(section)
            if (end > data.size) {
                if (section == Section.META) throw E2eeException("file is truncated before its metadata")
                break
            }
            out[section.ordinal] = openSection(photoKey, header, section, data, start.toInt(), (end - start).toInt())
        }
        return photo(header, out)
    }

    /**
     * Decrypts the metadata and the [wanted] sections of an encrypted file on disk, reading only those
     * sections (an original can be large, and its thumbnail and preview aren't needed again).
     */
    fun decrypt(file: File, keys: Map<Int, ByteArray>, wanted: Set<Section>): Photo =
        RandomAccessFile(file, "r").use { raf ->
            val headBytes = ByteArray(minOf(raf.length(), E2ee.HEADER_LEN.toLong()).toInt())
            raf.readFully(headBytes)
            val header = Header.parse(headBytes)
            val photoKey = unlockPhotoKey(header, keys)
            val out = arrayOfNulls<ByteArray>(4)
            for (section in Section.entries) {
                if (section != Section.META && section !in wanted) continue
                val start = header.offset(section)
                val length = header.lengths[section.ordinal]
                if (start + length > raf.length()) {
                    if (section == Section.META) throw E2eeException("file is truncated before its metadata")
                    continue
                }
                if (length > Int.MAX_VALUE) throw E2eeException("section too large")
                val bytes = ByteArray(length.toInt())
                raf.seek(start)
                try {
                    raf.readFully(bytes)
                } catch (e: EOFException) {
                    throw IOException("file shrank while reading", e)
                }
                out[section.ordinal] = openSection(photoKey, header, section, bytes, 0, bytes.size)
            }
            photo(header, out)
        }

    /**
     * The same photo for another epoch: decrypts [data] with [keys] and encrypts it again for [bundle] with a
     * fresh photo key (a rotation, or a file built for an epoch that is no longer current).
     */
    fun reencryptTo(out: OutputStream, data: ByteArray, keys: Map<Int, ByteArray>, bundle: Bundle): Header {
        val p = decrypt(data, keys)
        val original = p.original ?: throw E2eeException("the original is missing")
        val thumb = p.thumb ?: throw E2eeException("the thumbnail is missing")
        return encryptTo(out, bundle, original, p.mime, p.width, p.height, p.takenAtMs, thumb, p.preview ?: ByteArray(0))
    }

    private fun openSection(photoKey: ByteArray, header: Header, section: Section, data: ByteArray, offset: Int, length: Int): ByteArray =
        try {
            Crypto.open(photoKey, sectionNonce(section.ordinal), data, offset, length, header.aad)
        } catch (e: GeneralSecurityException) {
            throw E2eeException("${section.name.lowercase()} section does not decrypt: tampered or corrupt", e)
        }

    private fun photo(header: Header, sections: Array<ByteArray?>): Photo {
        val meta = try {
            // Strict UTF-8, as Python's json.loads: malformed bytes are an error, not a replacement character.
            val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(sections[Section.META.ordinal]!!)).toString()
            Json.parse(text)
        } catch (e: Json.JsonException) {
            throw E2eeException("metadata is not JSON", e)
        } catch (e: CharacterCodingException) {
            throw E2eeException("metadata is not JSON", e)
        }
        val mime = (meta as? Map<*, *>)?.get("mime") as? String
        if (meta !is Map<*, *> || mime == null || mime !in E2ee.INNER_MIMES) {
            throw E2eeException("metadata names an unsupported image type")
        }
        @Suppress("UNCHECKED_CAST")
        return Photo(
            epoch = header.epoch,
            meta = meta as Map<String, Any?>,
            thumb = sections[Section.THUMB.ordinal],
            preview = sections[Section.PREVIEW.ordinal]?.takeIf { it.isNotEmpty() },
            original = sections[Section.ORIGINAL.ordinal],
        )
    }

    /** Section `i`'s nonce: `i` as a 12-byte big-endian integer. */
    private fun sectionNonce(i: Int): ByteArray = ByteArray(E2ee.NONCE_LEN).also { it[E2ee.NONCE_LEN - 1] = i.toByte() }
}
