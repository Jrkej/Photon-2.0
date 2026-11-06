package com.photon.app.receive

import androidx.room.withTransaction
import com.photon.app.core.Clock
import com.photon.app.core.Hashes
import com.photon.app.core.ImageTypes
import com.photon.app.core.KeyedMutex
import com.photon.app.core.MediaCache
import com.photon.app.data.db.KnownHash
import com.photon.app.data.db.PhotonDatabase
import com.photon.app.data.db.ReceiveState
import com.photon.app.data.db.ReceivedAsset
import com.photon.app.data.db.ReceivedPhoto
import com.photon.app.e2ee.E2eeException
import com.photon.app.e2ee.Hex
import com.photon.app.e2ee.MissingEpochKeyException
import com.photon.app.e2ee.Photo
import com.photon.app.e2ee.PhotoFile
import com.photon.app.e2ee.PhotoTag
import com.photon.app.e2ee.Section
import com.photon.app.keys.KeyManager
import com.photon.app.net.ApiException
import com.photon.app.net.ErrorCodes
import com.photon.app.net.ImageApi
import com.photon.app.net.ImageSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A photo that is not the image it claims to be: not saved (e2ee.md 6.2, 8). */
class PhotoRejectedException(message: String) : E2eeException(message)

/**
 * Downloads and decrypts thumbnails, previews and originals of received photos (7.9 steps 2–5, e2ee.md 6.3).
 * Every size is a byte prefix of the encrypted file, decrypted with the epoch key its header names. A key this
 * phone doesn't hold yet raises [MissingEpochKeyException] (the download waits for key maintenance); a file
 * that doesn't decrypt or doesn't match its tag raises [E2eeException] (the photo fails).
 */
class PhotoFetcher(
    private val db: PhotonDatabase,
    private val imageApi: ImageApi,
    private val cache: MediaCache,
    private val gallery: GallerySaver,
    private val keys: KeyManager,
    private val clock: Clock,
) {
    /** One lock per photo, so the receive worker and both deferred-original workers never save twice. */
    private val locks = KeyedMutex()

    suspend fun fetchThumb(row: ReceivedPhoto) {
        val photo = downloadPrefix(row, ImageSize.THUMB)
        val thumb = photo.thumb ?: throw IOException("the thumbnail prefix was cut short")
        val file = cache.thumb(row.sha256)
        writeAtomically(file, thumb)
        db.received().setThumb(row.sha256, file.absolutePath)
    }

    /**
     * Returns false when there is no preview to show: the uploader sent none (a small original, which is
     * saved whole), or the server couldn't serve it (503). The thumbnail stays.
     */
    suspend fun fetchPreview(row: ReceivedPhoto): Boolean {
        val photo = try {
            downloadPrefix(row, ImageSize.PREVIEW)
        } catch (e: ApiException) {
            if (e.httpStatus == 503) return false else throw e
        }
        val preview = photo.preview ?: return false
        val file = cache.preview(row.sha256)
        writeAtomically(file, preview)
        db.received().setPreview(row.sha256, file.absolutePath)
        return true
    }

    /**
     * Downloads the encrypted file, checks its hash (the event's, which is the encrypted file's), decrypts the
     * original, checks it against its photo tag and type, and saves it to the gallery with the type from its
     * metadata. Safe to repeat: a photo already saved is left alone, and a save interrupted after the id was
     * recorded is finished rather than duplicated.
     */
    suspend fun fetchAndSaveOriginal(sha256: String) = locks.withLock(sha256) {
        val row = db.received().get(sha256) ?: return@withLock
        if (row.state == ReceiveState.SAVED || row.isTerminal) return@withLock
        if (row.mediaId != null && gallery.publishPending(row.mediaId)) {
            db.received().markSaved(sha256)
            return@withLock
        }
        val keyring = keyring(row)

        val file = cache.original(sha256)
        imageApi.download(row.sessionId, sha256, ImageSize.FULL, file)
        if (Hashes.sha256(file) != sha256) {
            file.delete()
            throw IOException("download does not match its hash")
        }
        val photo = try {
            withContext(Dispatchers.IO) { PhotoFile.decrypt(file, keyring, setOf(Section.ORIGINAL)) }
        } catch (e: MissingEpochKeyException) {
            throw e
        } catch (e: E2eeException) {
            file.delete()
            throw e
        }
        file.delete()
        val original = photo.original ?: throw IOException("the original is missing from the download")

        // The uploader's tag must be this photo's: a false one would block someone else's photo from the session.
        val digest = MessageDigest.getInstance("SHA-256").digest(original)
        val tagKey = keys.bundles(row.sessionId)[photo.epoch]?.tagKey ?: throw MissingEpochKeyException(photo.epoch)
        val tag = Hex.encode(PhotoTag.fromDigest(tagKey, digest))
        if (row.photoTag == null || !MessageDigest.isEqual(tag.toByteArray(), row.photoTag.toByteArray())) {
            throw PhotoRejectedException("the original does not match its photo tag")
        }
        // The server can't screen images any more; at least the bytes must be the type the metadata names.
        val detected = ImageTypes.detect(original.copyOf(minOf(original.size, 32)))
        if (detected == null || !ImageTypes.sameType(photo.mime, detected)) {
            throw PhotoRejectedException("the original is not a ${photo.mime} image")
        }

        val plain = cache.decrypted(sha256)
        writeAtomically(plain, original)
        val name = "PHOTON_${sha256.take(16)}.${GallerySaver.extensionFor(photo.mime)}"

        // Recall may have arrived during the download.
        if (db.received().get(sha256)?.isTerminal != false) {
            plain.delete()
            return@withLock
        }
        try {
            gallery.save(plain, name, photo.mime, row.takenAtMs) { mediaId ->
                val now = clock.nowMs()
                db.withTransaction {
                    db.receivedAssets().insert(ReceivedAsset(mediaId, sha256, row.sessionId, now))
                    // The file's hash for receive dedupe; the original's for echo layer 4.
                    db.hashes().insertIgnore(KnownHash(sha256, KnownHash.RECEIVED, row.sessionId, now))
                    db.hashes().insertIgnore(KnownHash(Hex.encode(digest), KnownHash.RECEIVED, row.sessionId, now))
                    db.received().setMediaId(sha256, mediaId)
                }
            }
            db.received().markSaved(sha256)
        } finally {
            plain.delete()
        }
    }

    private suspend fun downloadPrefix(row: ReceivedPhoto, size: ImageSize): Photo {
        val keyring = keyring(row)
        val file = cache.prefix(row.sha256, size.query)
        try {
            imageApi.download(row.sessionId, row.sha256, size, file)
            val data = withContext(Dispatchers.IO) { file.readBytes() }
            return PhotoFile.decrypt(data, keyring)
        } finally {
            file.delete()
        }
    }

    /** The session's epoch keys; the event says the file's epoch, so a missing key is spotted before downloading. */
    private fun keyring(row: ReceivedPhoto): Map<Int, ByteArray> {
        val held = keys.epochKeys(row.sessionId)
        if (row.keyEpoch != 0 && row.keyEpoch !in held) throw MissingEpochKeyException(row.keyEpoch)
        return held
    }

    private suspend fun writeAtomically(dest: File, bytes: ByteArray) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IOException("could not move ${tmp.name}")
        }
    }

    companion object {
        fun isGone(e: ApiException) = e.httpStatus == 410 || e.isCode(ErrorCodes.PHOTO_NOT_FOUND)
    }
}
