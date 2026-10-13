package com.photon.app.receive

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.photon.app.sync.Eligibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Saves received originals into Pictures/PHOTON/ (echo layer 2). Only originals go to the
 * gallery; thumbnails and previews stay in the app cache.
 */
class GallerySaver(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    /**
     * Inserts [file] into MediaStore and returns its media id. [recordBeforeVisible] receives the
     * id while the row is still IS_PENDING = 1 and must persist it (received_assets, hashes).
     *
     * Echo prevention depends on this order: the gallery observer (ours included) only sees the
     * row once IS_PENDING is cleared, and by then its id is already in received_assets, so the
     * scan skips it even if the system re-encoded the file and changed its hash.
     */
    suspend fun save(
        file: File,
        displayName: String,
        mime: String,
        takenAtMs: Long,
        recordBeforeVisible: suspend (mediaId: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 29) saveScoped(file, displayName, mime, takenAtMs, recordBeforeVisible)
        else saveLegacy(file, displayName, mime, takenAtMs, recordBeforeVisible)
    }

    /** Finishes a save interrupted after the id was recorded: make the row visible. */
    suspend fun publishPending(mediaId: Long): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            try {
                val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(ContentUris.withAppendedId(collection, mediaId), values, null, null) > 0
            } catch (e: Exception) {
                false
            }
        } else {
            true
        }
    }

    @androidx.annotation.RequiresApi(29)
    private suspend fun saveScoped(
        file: File,
        displayName: String,
        mime: String,
        takenAtMs: Long,
        recordBeforeVisible: suspend (Long) -> Unit,
    ): Long {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Eligibility.PHOTON_FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            if (takenAtMs > 0) put(MediaStore.Images.Media.DATE_TAKEN, takenAtMs)
        }
        val uri: Uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
                ?: throw IOException("cannot open $uri")
            val mediaId = ContentUris.parseId(uri)
            recordBeforeVisible(mediaId)
            val done = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
                if (takenAtMs > 0) put(MediaStore.Images.Media.DATE_TAKEN, takenAtMs)
            }
            resolver.update(uri, done, null, null)
            return mediaId
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /**
     * Android 8 and 9 have no IS_PENDING. The file is written under Pictures/PHOTON/ first,
     * which the observer and the scan skip (layer 2), then indexed and recorded.
     */
    @Suppress("DEPRECATION")
    private suspend fun saveLegacy(
        file: File,
        displayName: String,
        mime: String,
        takenAtMs: Long,
        recordBeforeVisible: suspend (Long) -> Unit,
    ): Long {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PHOTON")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("cannot create $dir")
        val target = File(dir, displayName)
        file.copyTo(target, overwrite = true)
        if (takenAtMs > 0) target.setLastModified(takenAtMs)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DATA, target.absolutePath)
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (takenAtMs > 0) put(MediaStore.Images.Media.DATE_TAKEN, takenAtMs)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed")
        val mediaId = ContentUris.parseId(uri)
        recordBeforeVisible(mediaId)
        return mediaId
    }

    companion object {
        fun extensionFor(mime: String): String = when (mime.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            else -> "jpg"
        }
    }
}
