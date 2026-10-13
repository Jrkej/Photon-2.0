package com.photon.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photon.app.core.Clock
import com.photon.app.core.MediaCache
import com.photon.app.data.db.KnownHash
import com.photon.app.data.db.PendingPhoto
import com.photon.app.data.db.ReceiveState
import com.photon.app.data.db.SessionState
import com.photon.app.data.db.UploadState
import com.photon.app.receive.ReceivePipeline
import com.photon.app.sync.WorkScheduler
import com.photon.proto.PhotoEvent
import com.photon.proto.Snapshot
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.decodeHex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Receive dedupe, the WAITING rule and recall against a real Room database. */
@RunWith(AndroidJUnit4::class)
class ReceivePipelineTest {
    private val context = TestSupport.context
    private val db = TestSupport.inMemoryDb()
    private lateinit var pipeline: ReceivePipeline
    private val now = System.currentTimeMillis()
    private val sha = "ab".repeat(32)

    @Before
    fun setUp() = runBlocking {
        TestSupport.testWorkManager()
        pipeline = ReceivePipeline(db, WorkScheduler(context), MediaCache(context), { error("session must not end") }, Clock.SYSTEM)
        db.sessions().insert(TestSupport.session(lastEventId = "10-0"))
    }

    @After
    fun tearDown() = db.close()

    private fun stored(id: String, hash: String = sha, uploader: String = "bob") = PhotoEvent(
        event_id = id, type = PhotoEvent.Type.PHOTO_STORED, session_id = "sess-1",
        sha256 = hash.decodeHex(), uploader_id = uploader, size = 100, taken_at_ms = now, expires_at_ms = now + 600_000,
    )

    @Test
    fun newPhotoIsQueuedOnceAndTheCursorMoves() = runBlocking {
        pipeline.handle("sess-1", stored("11-0"))
        pipeline.handle("sess-1", stored("11-0")) // duplicate delivery
        pipeline.handle("sess-1", stored("9-0", hash = "cd".repeat(32))) // at or below the cursor

        assertEquals(ReceiveState.THUMB, db.received().get(sha)!!.state)
        assertNull(db.received().get("cd".repeat(32)))
        assertEquals("11-0", db.sessions().get()!!.lastEventId)
    }

    @Test
    fun waitingRowIsSettledByTheStoredEvent() = runBlocking {
        db.pending().insertIgnore(
            listOf(
                PendingPhoto(
                    mediaId = 7, uri = "content://x/7", sessionId = "sess-1", takenAtMs = now, mime = "image/jpeg",
                    state = UploadState.WAITING, sha256 = sha, nextAttemptMs = now + 120_000, createdAtMs = now,
                ),
            ),
        )
        pipeline.handle("sess-1", stored("12-0", uploader = "bob"))

        assertEquals(UploadState.SYNCED, db.pending().get(7)!!.state)
        assertNull("our own bytes must not be downloaded", db.received().get(sha))
        assertEquals(true, db.hashes().contains(sha))
    }

    @Test
    fun ownUploadsEventIsNotDownloaded() = runBlocking {
        pipeline.handle("sess-1", stored("13-0", uploader = "alice"))
        assertNull(db.received().get(sha))
    }

    @Test
    fun recallLeavesATombstoneThatBlocksALateStoredEvent() = runBlocking {
        pipeline.handle("sess-1", PhotoEvent(event_id = "14-0", type = PhotoEvent.Type.PHOTO_RECALLED, sha256 = sha.decodeHex()))
        pipeline.applySnapshot("sess-1", Snapshot(cursor = "14-0", live_photos = listOf(stored("", uploader = "bob"))))

        assertEquals(ReceiveState.RECALLED, db.received().get(sha)!!.state)
    }

    @Test
    fun hostChangedPromotesThisUserAndMovesTheHostBadge() = runBlocking {
        assertEquals(SessionState.ROLE_MEMBER, db.sessions().get()!!.role)
        pipeline.handle("sess-1", PhotoEvent(event_id = "15-0", type = PhotoEvent.Type.HOST_CHANGED, member_id = "alice"))
        db.sessions().get()!!.let {
            assertEquals(SessionState.ROLE_HOST, it.role)
            assertEquals("alice", it.hostId)
            assertEquals("15-0", it.lastEventId)
        }
        pipeline.handle("sess-1", PhotoEvent(event_id = "16-0", type = PhotoEvent.Type.HOST_CHANGED, member_id = "bob"))
        db.sessions().get()!!.let {
            assertEquals(SessionState.ROLE_MEMBER, it.role)
            assertEquals("bob", it.hostId)
        }
    }

    @Test
    fun snapshotSetsMembersAndCursorEvenBackwards() = runBlocking {
        db.hashes().insertIgnore(KnownHash("ef".repeat(32), KnownHash.RECEIVED, "sess-1", now))
        pipeline.applySnapshot(
            "sess-1",
            Snapshot(
                cursor = "5-0",
                member_ids = listOf("alice", "bob"),
                live_photos = listOf(stored("", hash = "ef".repeat(32)), stored("", hash = sha)),
                recalled_sha256 = listOf("01".repeat(32).decodeHex()),
            ),
        )
        assertEquals("5-0", db.sessions().get()!!.lastEventId)
        assertNull(db.received().get("ef".repeat(32))) // already held
        assertNotNull(db.received().get(sha))
        assertEquals(ReceiveState.RECALLED, db.received().get("01".repeat(32))!!.state)
    }
}
