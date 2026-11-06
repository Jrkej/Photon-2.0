package com.photon.app.upload

import android.util.Log
import com.photon.app.core.Hashes
import com.photon.app.core.ImageTypes
import com.photon.app.core.MediaCache
import com.photon.app.data.db.PendingPhoto
import com.photon.app.data.db.UploadState
import com.photon.app.e2ee.Bundle
import com.photon.app.e2ee.E2ee
import com.photon.app.e2ee.Hex
import com.photon.app.e2ee.PhotoFile
import com.photon.app.e2ee.PhotoTag
import com.photon.app.net.ErrorCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import java.io.File
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest

sealed interface Packaged {
    /** The encrypted file, ready to announce. [sha256] is the file's (its id on the server), [photoTag] hex. */
    data class Ready(val file: File, val sha256: String, val size: Long, val photoTag: String, val epoch: Int) : Packaged

    /** Not shared: [state] is SKIPPED or FAILED, [reason] what the row shows. */
    data class Refused(val state: String, val reason: String) : Packaged
}

/**
 * Steps 2–5 of e2ee.md 7 after the GPS-stripped copy exists: check the image (the server no longer can), compute
 * the photo tag, make the thumbnail and preview, and build the encrypted file for the current epoch.
 */
class PhotoPackager(
    private val cache: MediaCache,
    private val thumbnails: (File) -> Thumbnails.Outcome = Thumbnails::make,
) {
    /** [original] is the stripped copy and [originalSha256] its hash (hex), from [UploadPreparer]. */
    suspend fun build(row: PendingPhoto, original: File, originalSha256: String, bundle: Bundle): Packaged =
        withContext(Dispatchers.IO) {
            if (original.length() + E2ee.HEADER_LEN + 4 * E2ee.TAG_LEN > E2ee.MAX_FILE_BYTES) {
                return@withContext Packaged.Refused(UploadState.FAILED, ErrorCodes.TOO_LARGE)
            }
            val bytes = original.readBytes()

            // A known type whose magic bytes match the declared one, one frame, decodable, at most 100 MP.
            val type = ImageTypes.detect(bytes.copyOf(minOf(bytes.size, 32)))
                ?: return@withContext invalid("not a JPEG, PNG, WebP, HEIC or HEIF image")
            val declared = ImageTypes.normalize(row.mime)
            if (declared != null && !ImageTypes.sameType(declared, type)) return@withContext invalid("declared $declared but the file is $type")
            if (ImageTypes.isMultiFrame(type, bytes)) return@withContext invalid("animated or multi-frame")
            val images = when (val made = thumbnails(original)) {
                is Thumbnails.Outcome.Ready -> made.images
                is Thumbnails.Outcome.Refused -> return@withContext invalid(made.reason)
            }

            val metaLen = PhotoFile.metaJson(type, images.width, images.height, row.takenAtMs).size
            val total = PhotoFile.encryptedSize(metaLen, images.thumb.size, images.preview.size, bytes.size.toLong())
            if (total > E2ee.MAX_FILE_BYTES) return@withContext Packaged.Refused(UploadState.FAILED, ErrorCodes.TOO_LARGE)

            val tag = PhotoTag.fromDigest(bundle.tagKey, Hashes.bytes(originalSha256).toByteArray())
            val (file, sha) = write(cache.encryptedUpload(row.mediaId)) { out ->
                PhotoFile.encryptTo(out, bundle, bytes, type, images.width, images.height, row.takenAtMs, images.thumb, images.preview)
            }
            Packaged.Ready(file, sha, file.length(), Hex.encode(tag), bundle.epoch)
        }

    /**
     * The same photo for the current epoch ([bundle]): a rotation, or a file built for an epoch that is no
     * longer current. A fresh photo key, so a new hash; the tag stays.
     */
    suspend fun rebuild(file: File, keys: Map<Int, ByteArray>, bundle: Bundle, mediaId: Long): Pair<File, String> =
        withContext(Dispatchers.IO) {
            val data = file.readBytes()
            write(cache.encryptedUpload(mediaId)) { out -> PhotoFile.reencryptTo(out, data, keys, bundle) }
        }

    private fun invalid(why: String): Packaged.Refused {
        Log.w("PHOTON", "not sharing a photo: $why")
        return Packaged.Refused(UploadState.FAILED, ErrorCodes.INVALID_IMAGE)
    }

    /** Writes through a SHA-256 digest into `dest.part`, then moves it over [dest]. Returns the file and its hash. */
    private inline fun write(dest: File, body: (DigestOutputStream) -> Unit): Pair<File, String> {
        val part = File(dest.path + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            DigestOutputStream(part.outputStream().buffered(1 shl 16), digest).use(body)
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        if (!part.renameTo(dest)) {
            part.delete()
            throw IOException("cannot move ${part.name}")
        }
        return dest to digest.digest().toByteString().hex()
    }
}
