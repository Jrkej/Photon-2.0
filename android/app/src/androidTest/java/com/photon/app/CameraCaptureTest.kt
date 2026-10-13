package com.photon.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.photon.app.core.Clock
import com.photon.app.core.MediaPermissions
import com.photon.app.data.db.UploadState
import com.photon.app.sync.MediaScanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 7.13: a photo taken with the camera reaches `pending_photos`. Drives the device's camera app
 * through the shell (STILL_IMAGE_CAMERA, then the camera key), which works on the stock
 * emulator images. On a device whose camera app ignores KEYCODE_CAMERA, take a photo by hand
 * within the timeout.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class CameraCaptureTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*MediaPermissions.toRequest())

    private val context = TestSupport.context
    private val db = TestSupport.inMemoryDb()

    @After
    fun tearDown() {
        TestSupport.shell("input keyevent KEYCODE_HOME")
        db.close()
    }

    @Test
    fun cameraPhotoIsQueued() = runBlocking {
        db.sessions().insert(TestSupport.session(joinTimeMs = System.currentTimeMillis() - 1_000))
        val scanner = MediaScanner(context, db, Clock.SYSTEM)

        TestSupport.shell("am start -W -a android.media.action.STILL_IMAGE_CAMERA")
        delay(3_000)
        TestSupport.shell("input keyevent KEYCODE_CAMERA")

        var queued = 0
        val deadline = System.currentTimeMillis() + 30_000
        while (queued == 0 && System.currentTimeMillis() < deadline) {
            delay(1_000)
            scanner.scanSinceCursor()
            queued = db.pending().nextDue("sess-1", Long.MAX_VALUE)?.let { 1 } ?: 0
        }
        assertTrue("no camera photo reached pending_photos", queued > 0)
        val row = db.pending().nextDue("sess-1", Long.MAX_VALUE)!!
        assertTrue(row.state == UploadState.PENDING)
    }
}
