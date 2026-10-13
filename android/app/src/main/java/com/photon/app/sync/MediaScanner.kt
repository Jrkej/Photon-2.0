package com.photon.app.sync

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.photon.app.core.Clock
import com.photon.app.core.MediaAccess
import com.photon.app.core.MediaPermissions
import com.photon.app.data.db.PendingPhoto
import com.photon.app.data.db.PhotonDatabase
import com.photon.app.data.db.SessionState
import com.photon.app.data.db.UploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds eligible gallery rows and adds them to `pending_photos`. Used by the observer (from the
 * scan cursor) and by the reconcile scan (from the join time). It never hashes and never touches
 * the network.
 */
class MediaScanner(
    context: Context,
    private val db: PhotonDatabase,
    private val clock: Clock,
) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver

    /** Rows newer than the scan cursor; what the observer runs on every change. */
    suspend fun scanSinceCursor(): Int {
        val s = db.sessions().get() ?: return 0
        return scan(s, s.scanCursorMs)
    }

    /** Everything since the join time; the reconcile scan (7.6). */
    suspend fun reconcile(): Int {
        val s = db.sessions().get() ?: return 0
        return scan(s, s.joinTimeMs)
    }

    private suspend fun scan(s: SessionState, sinceMs: Long): Int {
        if (s.paused) return 0
        if (MediaPermissions.access(app) != MediaAccess.FULL) return 0
        val scanStart = clock.nowMs()
        val rows = query(sinceMs)
        val window = EligibilityWindow(
            joinTimeMs = s.joinTimeMs,
            // The session's expiry slides with activity, so the local end is the moment the
            // session is torn down; nothing is scanned after that.
            endMs = null,
            pausedRanges = db.sessions().pauseWindows(s.sessionId).map { it.startMs until it.endMs },
        )
        val eligible = rows.filter { Eligibility.evaluate(it, window, app.packageName) == Verdict.ELIGIBLE }

        var inserted = 0
        for (batch in eligible.chunked(500)) {
            val ids = batch.map { it.mediaId }
            // Echo layer 1: rows the app saved itself, whatever their bytes are now.
            val known = (db.receivedAssets().existingIds(ids) + db.pending().existingIds(ids)).toHashSet()
            val fresh = batch.filter { it.mediaId !in known }.map { row ->
                PendingPhoto(
                    mediaId = row.mediaId,
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, row.mediaId).toString(),
                    sessionId = s.sessionId,
                    takenAtMs = Eligibility.captureTimeMs(row) ?: scanStart,
                    mime = normalizeMime(row.mimeType),
                    state = UploadState.PENDING,
                    createdAtMs = scanStart,
                )
            }
            if (fresh.isNotEmpty()) inserted += db.pending().insertIgnore(fresh).count { it != -1L }
        }
        // Rows still pending or finishing (IS_PENDING) are found again through DATE_MODIFIED and
        // the overlap below, so the cursor can move to the start of this scan.
        db.sessions().advanceScanCursor(s.sessionId, scanStart)
        return inserted
    }

    private suspend fun query(sinceMs: Long): List<MediaRow> = withContext(Dispatchers.IO) {
        val sinceSec = (sinceMs / 1000) - OVERLAP_SEC
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.MIME_TYPE)
            add(MediaStore.Images.Media.SIZE)
            @Suppress("DEPRECATION")
            add(MediaStore.Images.Media.DATA)
            if (Build.VERSION.SDK_INT >= 29) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
                add(MediaStore.Images.Media.IS_PENDING)
                add(MediaStore.Images.Media.OWNER_PACKAGE_NAME)
            }
        }.toTypedArray()
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ? OR ${MediaStore.Images.Media.DATE_MODIFIED} >= ?"
        val args = arrayOf(sinceSec.toString(), sinceSec.toString())
        val out = ArrayList<MediaRow>()
        resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val taken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val added = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val mime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val size = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            @Suppress("DEPRECATION")
            val data = c.getColumnIndex(MediaStore.Images.Media.DATA)
            val rel = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) else -1
            val pending = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.Images.Media.IS_PENDING) else -1
            val owner = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.Images.Media.OWNER_PACKAGE_NAME) else -1
            while (c.moveToNext()) {
                out += MediaRow(
                    mediaId = c.getLong(id),
                    dateTakenMs = if (c.isNull(taken)) null else c.getLong(taken),
                    dateAddedSec = if (c.isNull(added)) null else c.getLong(added),
                    mimeType = c.getString(mime),
                    sizeBytes = if (c.isNull(size)) null else c.getLong(size),
                    relativePath = if (rel >= 0) c.getString(rel) else null,
                    dataPath = if (data >= 0) c.getString(data) else null,
                    isPending = pending >= 0 && c.getInt(pending) != 0,
                    ownerPackage = if (owner >= 0) c.getString(owner) else null,
                )
            }
        }
        out
    }

    private fun normalizeMime(mime: String?): String = when (val m = mime?.lowercase()) {
        null -> "image/jpeg"
        "image/jpg" -> "image/jpeg"
        else -> m
    }

    private companion object {
        /** DATE_ADDED has one-second resolution and pending rows finish late; overlap scans. */
        const val OVERLAP_SEC = 120L
    }
}
