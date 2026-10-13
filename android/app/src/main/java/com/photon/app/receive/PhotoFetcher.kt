package com.photon.app.receive

import androidx.room.withTransaction
import com.photon.app.core.Clock
import com.photon.app.core.Hashes
import com.photon.app.core.MediaCache
import com.photon.app.data.db.KnownHash
import com.photon.app.data.db.PhotonDatabase
import com.photon.app.data.db.ReceiveState
import com.photon.app.data.db.ReceivedAsset
import com.photon.app.data.db.ReceivedPhoto
import com.photon.app.net.ApiException
import com.photon.app.net.ErrorCodes
import com.photon.app.net.ImageApi
import com.photon.app.net.ImageSize
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** One lock per photo, so the receive worker and both deferred-original workers never save twice. */
class KeyedMutex {
    private val locks = ConcurrentHashMap<String, Mutex>()
    suspend fun <T> withLock(key: String, block: suspend () -> T): T =
        locks.getOrPut(key) { Mutex() }.withLock { block() }
}

/** Downloads thumbnails, previews and originals of received photos (7.9 steps 2–5). */
class PhotoFetcher(
    private val db: PhotonDatabase,
    private val imageApi: ImageApi,
    private val cache: MediaCache,
    private val gallery: GallerySaver,
    private val clock: Clock,
) {
    private val locks = KeyedMutex()

    suspend fun fetchThumb(row: ReceivedPhoto) {
        val file = cache.thumb(row.sha256)
        imageApi.download(row.sessionId, row.sha256, ImageSize.THUMB, file)
        db.received().setThumb(row.sha256, file.absolutePath)
    }

    /** Returns false when the server couldn't make the preview (503); the thumbnail stays. */
    suspend fun fetchPreview(row: ReceivedPhoto): Boolean {
        val file = cache.preview(row.sha256)
        return try {
            imageApi.download(row.sessionId, row.sha256, ImageSize.PREVIEW, file)
            db.received().setPreview(row.sha256, file.absolutePath)
            true
        } catch (e: ApiException) {
            if (e.httpStatus == 503) false else throw e
        }
    }

    /**
     * Downloads the original, checks its hash, and saves it to the gallery. Safe to repeat:
     * a photo already saved is left alone, and a save interrupted after the id was recorded is
     * finished rather than duplicated.
     */
    suspend fun fetchAndSaveOriginal(sha256: String) = locks.withLock(sha256) {
        val row = db.received().get(sha256) ?: return@withLock
        if (row.state == ReceiveState.SAVED || row.isTerminal) return@withLock
        if (row.mediaId != null && gallery.publishPending(row.mediaId)) {
            db.received().markSaved(sha256)
            return@withLock
        }

        val file = cache.original(sha256)
        val result = imageApi.download(row.sessionId, sha256, ImageSize.FULL, file)
        if (Hashes.sha256(file) != sha256) {
            file.delete()
            throw IOException("original does not match its hash")
        }
        val mime = result.contentType?.substringBefore(';')?.trim()?.lowercase()
            ?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val name = "PHOTON_${sha256.take(16)}.${GallerySaver.extensionFor(mime)}"

        // Recall may have arrived during the download.
        if (db.received().get(sha256)?.isTerminal != false) {
            file.delete()
            return@withLock
        }
        gallery.save(file, name, mime, row.takenAtMs) { mediaId ->
            val now = clock.nowMs()
            db.withTransaction {
                db.receivedAssets().insert(ReceivedAsset(mediaId, sha256, row.sessionId, now))
                db.hashes().insertIgnore(KnownHash(sha256, KnownHash.RECEIVED, row.sessionId, now))
                db.received().setMediaId(sha256, mediaId)
            }
        }
        db.received().markSaved(sha256)
        file.delete()
    }

    companion object {
        fun isGone(e: ApiException) = e.httpStatus == 410 || e.isCode(ErrorCodes.PHOTO_NOT_FOUND)
    }
}
