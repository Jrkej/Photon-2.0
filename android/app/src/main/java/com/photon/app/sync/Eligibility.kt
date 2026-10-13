package com.photon.app.sync

/** One MediaStore image row, with the columns the filter needs. Null means "not available". */
data class MediaRow(
    val mediaId: Long,
    val dateTakenMs: Long?,
    val dateAddedSec: Long?,
    val mimeType: String?,
    val sizeBytes: Long?,
    /** `RELATIVE_PATH` (Android 10+), e.g. "DCIM/Camera/". */
    val relativePath: String?,
    /** The legacy `DATA` path, used on Android 8 and 9 where RELATIVE_PATH doesn't exist. */
    val dataPath: String?,
    val isPending: Boolean,
    /** `OWNER_PACKAGE_NAME` (Android 10+). */
    val ownerPackage: String?,
)

/** The part of a session that decides which photos are shared. */
data class EligibilityWindow(
    val joinTimeMs: Long,
    /** Exclusive end; null while the session is open. */
    val endMs: Long? = null,
    /** Capture times while sharing was paused. Photos taken then are never shared. */
    val pausedRanges: List<LongRange> = emptyList(),
)

enum class Verdict {
    ELIGIBLE,
    BEFORE_JOIN,
    AFTER_END,
    DURING_PAUSE,
    PENDING,
    NOT_CAMERA_FOLDER,
    PHOTON_FOLDER,
    SCREENSHOT,
    OWN_APP,
    UNSUPPORTED_TYPE,
    NO_TIMESTAMP,
}

/**
 * The reconcile scan's and the observer's filter (implementation plan 7.6). It covers echo
 * layers 2 (the PHOTON folder) and 3 (rows the app itself owns); layer 1, the received-asset
 * ids, is checked against Room by the caller. All three run before hashing, because the system
 * may re-encode a saved copy and change its bytes.
 */
object Eligibility {
    const val PHOTON_FOLDER = "Pictures/PHOTON/"

    /** What the image server accepts (Design.md "Accepted images"). */
    val SUPPORTED_MIME = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")

    fun captureTimeMs(row: MediaRow): Long? =
        row.dateTakenMs?.takeIf { it > 0 } ?: row.dateAddedSec?.takeIf { it > 0 }?.times(1000)

    /**
     * "DCIM/Camera/" from RELATIVE_PATH, or derived from the legacy absolute path
     * ("/storage/emulated/0/DCIM/Camera/x.jpg" gives "DCIM/Camera/").
     */
    fun relativeDir(row: MediaRow): String? {
        row.relativePath?.takeIf { it.isNotEmpty() }?.let { return if (it.endsWith("/")) it else "$it/" }
        val data = row.dataPath ?: return null
        val dir = data.substringBeforeLast('/', "") + "/"
        for (root in listOf("/DCIM/", "/Pictures/")) {
            val at = dir.indexOf(root)
            if (at >= 0) return dir.substring(at + 1)
        }
        return null
    }

    fun evaluate(row: MediaRow, window: EligibilityWindow, selfPackage: String): Verdict {
        if (row.isPending) return Verdict.PENDING
        if (row.ownerPackage != null && row.ownerPackage == selfPackage) return Verdict.OWN_APP

        val dir = relativeDir(row) ?: return Verdict.NOT_CAMERA_FOLDER
        if (dir.startsWith(PHOTON_FOLDER, ignoreCase = true)) return Verdict.PHOTON_FOLDER
        if (dir.split('/').any { it.equals("Screenshots", ignoreCase = true) }) return Verdict.SCREENSHOT
        if (!dir.startsWith("DCIM/", ignoreCase = true)) return Verdict.NOT_CAMERA_FOLDER

        val mime = row.mimeType?.lowercase()
        if (mime == null || mime !in SUPPORTED_MIME) return Verdict.UNSUPPORTED_TYPE

        val taken = captureTimeMs(row) ?: return Verdict.NO_TIMESTAMP
        if (taken < window.joinTimeMs) return Verdict.BEFORE_JOIN
        if (window.endMs != null && taken >= window.endMs) return Verdict.AFTER_END
        if (window.pausedRanges.any { taken in it }) return Verdict.DURING_PAUSE
        return Verdict.ELIGIBLE
    }
}
