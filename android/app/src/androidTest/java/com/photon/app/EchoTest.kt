package com.photon.app

import android.content.ContentUris
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.photon.app.core.Clock
import com.photon.app.core.MediaPermissions
import com.photon.app.data.db.KnownHash
import com.photon.app.data.db.ReceivedAsset
import com.photon.app.receive.GallerySaver
import com.photon.app.sync.MediaScanner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 7.13 "the echo test": a received photo saved to the gallery is never enqueued for upload,
 * and its id is recorded before the gallery row becomes visible.
 */
@RunWith(AndroidJUnit4::class)
class EchoTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*MediaPermissions.toRequest())

    private val context = TestSupport.context
    private val db = TestSupport.inMemoryDb()
    private var savedId: Long? = null

    @After
    fun tearDown() {
        savedId?.let {
            context.contentResolver.delete(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it), null, null)
        }
        db.close()
    }

    @Test
    fun receivedOriginalIsRecordedBeforeVisibleAndNeverQueued() = runBlocking {
        val session = TestSupport.session(joinTimeMs = System.currentTimeMillis() - 60_000)
        db.sessions().insert(session)

        val jpeg = File(context.cacheDir, "echo.jpg").apply {
            outputStream().use { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        val sha = "e".repeat(64)
        var visibleWhileRecording = true

        savedId = GallerySaver(context).save(jpeg, "PHOTON_echo_test.jpg", "image/jpeg", System.currentTimeMillis()) { mediaId ->
            // Still IS_PENDING: an ordinary query (what the observer runs) must not see it yet.
            if (Build.VERSION.SDK_INT >= 30) {
                visibleWhileRecording = context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media._ID} = ?", arrayOf(mediaId.toString()), null,
                )?.use { it.count > 0 } ?: false
            } else {
                visibleWhileRecording = false
            }
            db.withTransaction {
                db.receivedAssets().insert(ReceivedAsset(mediaId, sha, session.sessionId, System.currentTimeMillis()))
                db.hashes().insertIgnore(KnownHash(sha, KnownHash.RECEIVED, session.sessionId, System.currentTimeMillis()))
            }
        }

        assertFalse("row was visible before its id was recorded", visibleWhileRecording)
        assertEquals(listOf(savedId!!), db.receivedAssets().existingIds(listOf(savedId!!)))

        // Both the observer's scan and the reconcile scan skip it.
        val scanner = MediaScanner(context, db, Clock.SYSTEM)
        scanner.scanSinceCursor()
        scanner.reconcile()
        assertTrue(db.pending().existingIds(listOf(savedId!!)).isEmpty())
    }
}
