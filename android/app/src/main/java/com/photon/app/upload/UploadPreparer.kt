package com.photon.app.upload

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.photon.app.core.Hashes
import com.photon.app.core.MediaCache
import com.photon.app.core.MediaPermissions
import com.photon.app.data.db.PendingPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

sealed interface Prepared {
    data class Ready(val copy: File, val sha256: String, val size: Long) : Prepared
    data class Skip(val reason: String) : Prepared
}

/**
 * Step 1 of the upload (7.7): copy the photo into the app cache and hash exactly the bytes that
 * will be uploaded.
 *
 * Location: without ACCESS_MEDIA_LOCATION, Android 10+ redacts GPS from the stream, so the copy
 * has none. Android 8 and 9 don't redact, and an app that was granted the permission (GPS
 * stripping turned off, then on again) gets the original, so the copy is checked and GPS tags
 * are removed from JPEG, PNG and WebP. HEIC can't be rewritten without re-encoding, so a HEIC
 * that still has GPS while stripping is on is skipped rather than shared with its location.
 */
class UploadPreparer(context: Context, private val cache: MediaCache) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    suspend fun prepare(row: PendingPhoto, stripGps: Boolean): Prepared = withContext(Dispatchers.IO) {
        val dest = cache.uploadCopy(row.mediaId)
        val part = File(dest.path + ".part")
        val source = sourceUri(Uri.parse(row.uri), stripGps)
        try {
            val input = resolver.openInputStream(source) ?: return@withContext Prepared.Skip("unreadable")
            input.use { inp -> part.outputStream().use { out -> inp.copyTo(out, 1 shl 16) } }
        } catch (e: FileNotFoundException) {
            part.delete()
            return@withContext Prepared.Skip("deleted")
        } catch (e: SecurityException) {
            part.delete()
            return@withContext Prepared.Skip("no access")
        }
        if (part.length() == 0L) {
            part.delete()
            return@withContext Prepared.Skip("empty")
        }

        if (stripGps && hasGps(part)) {
            if (!canRewriteExif(row.mime)) {
                part.delete()
                return@withContext Prepared.Skip("location could not be removed")
            }
            try {
                stripGpsTags(part)
            } catch (e: IOException) {
                part.delete()
                return@withContext Prepared.Skip("location could not be removed")
            }
            if (hasGps(part)) {
                part.delete()
                return@withContext Prepared.Skip("location could not be removed")
            }
        }

        val sha = Hashes.sha256(part)
        if (!part.renameTo(dest)) throw IOException("cannot move upload copy")
        Prepared.Ready(dest, sha, dest.length())
    }

    private fun sourceUri(uri: Uri, stripGps: Boolean): Uri =
        if (!stripGps && Build.VERSION.SDK_INT >= 29 && MediaPermissions.canReadLocation(app)) {
            MediaStore.setRequireOriginal(uri)
        } else {
            uri
        }

    private fun hasGps(file: File): Boolean = try {
        val exif = ExifInterface(file)
        val latLong = exif.latLong
        when {
            latLong != null -> !(latLong[0] == 0.0 && latLong[1] == 0.0) && !latLong[0].isNaN() && !latLong[1].isNaN()
            else -> listOf(ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE).any { tag ->
                exif.getAttribute(tag)?.let { v -> v.isNotBlank() && !isZeroRational(v) } == true
            }
        }
    } catch (e: Exception) {
        false
    }

    /** "0/1,0/1,0/1": every rational's numerator is zero (what a redacted location looks like). */
    private fun isZeroRational(value: String) =
        value.split(',').all { it.substringBefore('/').trim().toDoubleOrNull() == 0.0 }

    private fun canRewriteExif(mime: String) = mime.lowercase() in REWRITABLE

    private fun stripGpsTags(file: File) {
        val exif = ExifInterface(file)
        GPS_TAGS.forEach { exif.setAttribute(it, null) }
        exif.saveAttributes()
    }

    private companion object {
        val REWRITABLE = setOf("image/jpeg", "image/png", "image/webp")
        val GPS_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_TRACK,
            ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        )
    }
}
