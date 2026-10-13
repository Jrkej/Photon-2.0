package com.photon.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class EligibilityTest {
    private val self = "com.photon.app"
    private val join = 1_700_000_000_000L
    private val window = EligibilityWindow(joinTimeMs = join)

    private fun row(
        taken: Long? = join + 60_000,
        addedSec: Long? = (join + 61_000) / 1000,
        mime: String? = "image/jpeg",
        rel: String? = "DCIM/Camera/",
        data: String? = null,
        pending: Boolean = false,
        owner: String? = "com.android.camera",
    ) = MediaRow(1, taken, addedSec, mime, 1234, rel, data, pending, owner)

    private fun verdict(r: MediaRow, w: EligibilityWindow = window) = Eligibility.evaluate(r, w, self)

    @Test
    fun cameraPhotoAfterJoinIsEligible() {
        assertEquals(Verdict.ELIGIBLE, verdict(row()))
        assertEquals(Verdict.ELIGIBLE, verdict(row(rel = "DCIM/100ANDRO/")))
        assertEquals(Verdict.ELIGIBLE, verdict(row(rel = "DCIM/Camera", mime = "image/heic")))
    }

    @Test
    fun captureTimeMustBeAfterJoin() {
        assertEquals(Verdict.BEFORE_JOIN, verdict(row(taken = join - 1)))
        assertEquals(Verdict.ELIGIBLE, verdict(row(taken = join)))
    }

    @Test
    fun dateAddedIsTheFallbackForCaptureTime() {
        assertEquals(Verdict.ELIGIBLE, verdict(row(taken = null)))
        assertEquals(Verdict.ELIGIBLE, verdict(row(taken = 0)))
        assertEquals(Verdict.BEFORE_JOIN, verdict(row(taken = null, addedSec = join / 1000 - 1)))
        assertEquals(Verdict.NO_TIMESTAMP, verdict(row(taken = null, addedSec = null)))
    }

    @Test
    fun captureMustBeBeforeTheSessionEnd() {
        val ended = window.copy(endMs = join + 30_000)
        assertEquals(Verdict.AFTER_END, verdict(row(taken = join + 30_000), ended))
        assertEquals(Verdict.ELIGIBLE, verdict(row(taken = join + 29_999), ended))
    }

    @Test
    fun photosTakenWhilePausedAreNeverShared() {
        val paused = window.copy(pausedRanges = listOf((join + 50_000) until (join + 70_000)))
        assertEquals(Verdict.DURING_PAUSE, verdict(row(taken = join + 60_000), paused))
        assertEquals(Verdict.ELIGIBLE, verdict(row(taken = join + 70_000), paused))
    }

    @Test
    fun pendingRowsWait() {
        assertEquals(Verdict.PENDING, verdict(row(pending = true)))
    }

    @Test
    fun echoLayer2ThePhotonFolderIsSkipped() {
        assertEquals(Verdict.PHOTON_FOLDER, verdict(row(rel = "Pictures/PHOTON/", owner = null)))
        assertEquals(Verdict.PHOTON_FOLDER, verdict(row(rel = "pictures/photon/", owner = null)))
        assertEquals(Verdict.PHOTON_FOLDER, verdict(row(rel = null, data = "/storage/emulated/0/Pictures/PHOTON/PHOTON_ab.jpg", owner = null)))
    }

    @Test
    fun echoLayer3RowsOwnedByThisAppAreSkipped() {
        assertEquals(Verdict.OWN_APP, verdict(row(owner = self)))
    }

    @Test
    fun screenshotsAndDownloadsAreSkipped() {
        assertEquals(Verdict.SCREENSHOT, verdict(row(rel = "DCIM/Screenshots/")))
        assertEquals(Verdict.SCREENSHOT, verdict(row(rel = "Pictures/Screenshots/")))
        assertEquals(Verdict.NOT_CAMERA_FOLDER, verdict(row(rel = "Download/")))
        assertEquals(Verdict.NOT_CAMERA_FOLDER, verdict(row(rel = "Pictures/WhatsApp/")))
        assertEquals(Verdict.NOT_CAMERA_FOLDER, verdict(row(rel = null, data = null)))
    }

    @Test
    fun legacyDataPathIsUsedBeforeAndroid10() {
        val legacy = row(rel = null, data = "/storage/emulated/0/DCIM/Camera/IMG_1.jpg", owner = null)
        assertEquals("DCIM/Camera/", Eligibility.relativeDir(legacy))
        assertEquals(Verdict.ELIGIBLE, verdict(legacy))
        assertEquals(Verdict.NOT_CAMERA_FOLDER, verdict(row(rel = null, data = "/storage/emulated/0/Download/x.jpg")))
    }

    @Test
    fun onlyFormatsTheServerAcceptsAreQueued() {
        assertEquals(Verdict.UNSUPPORTED_TYPE, verdict(row(mime = "image/gif")))
        assertEquals(Verdict.UNSUPPORTED_TYPE, verdict(row(mime = "image/x-adobe-dng")))
        assertEquals(Verdict.UNSUPPORTED_TYPE, verdict(row(mime = null)))
        assertEquals(Verdict.ELIGIBLE, verdict(row(mime = "IMAGE/PNG")))
    }
}
