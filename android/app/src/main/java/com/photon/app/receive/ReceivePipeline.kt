package com.photon.app.receive

import com.photon.app.core.Clock
import com.photon.app.core.EventIds
import com.photon.app.core.Hashes
import com.photon.app.core.MediaCache
import com.photon.app.data.db.KnownHash
import com.photon.app.data.db.PhotonDatabase
import com.photon.app.data.db.ReceiveState
import com.photon.app.data.db.ReceivedPhoto
import com.photon.app.data.db.SessionMember
import com.photon.app.data.db.SessionState
import com.photon.app.events.knownType
import com.photon.app.session.EndReason
import com.photon.app.session.SessionManager
import com.photon.app.session.SessionRules
import com.photon.app.sync.WorkScheduler
import com.photon.proto.PhotoEvent
import com.photon.proto.Snapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Applies events and snapshots (7.9). Events are processed one at a time under [mutex], whether
 * they come from the WebSocket or from `/events` catch-up, and `last_event_id` is written only
 * after an event's effects are in Room. Downloads run as WorkManager jobs, so an event counts as
 * handled once its row exists and its job is enqueued.
 */
class ReceivePipeline(
    private val db: PhotonDatabase,
    private val scheduler: WorkScheduler,
    private val cache: MediaCache,
    private val sessions: () -> SessionManager,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    suspend fun handleAll(sessionId: String, events: List<PhotoEvent>) {
        for (event in events) {
            if (!handle(sessionId, event)) return
        }
    }

    /** Returns false when the session is gone (ended, left or replaced) and processing should stop. */
    suspend fun handle(sessionId: String, event: PhotoEvent): Boolean {
        var endReason: EndReason? = null
        val stillActive = mutex.withLock {
            val s = db.sessions().get()
            if (s == null || s.sessionId != sessionId) return@withLock false
            if (event.session_id.isNotEmpty() && event.session_id != s.sessionId) return@withLock true
            if (!ReceiveDedupe.isNewEvent(event.event_id, s.lastEventId)) return@withLock true

            endReason = apply(s, event)
            if (endReason == null && event.event_id.isNotEmpty()) {
                db.sessions().setLastEventId(s.sessionId, EventIds.max(s.lastEventId, event.event_id))
            }
            endReason == null
        }
        endReason?.let { sessions().endLocally(it) }
        return stillActive
    }

    /**
     * Applies a snapshot: members, recalled hashes, live photos, then its cursor. The cursor is
     * taken as is, even when it is behind ours (a resync after a server failover); events
     * between are dropped by hash.
     */
    suspend fun applySnapshot(sessionId: String, snapshot: Snapshot) {
        mutex.withLock {
            val s = db.sessions().get() ?: return
            if (s.sessionId != sessionId) return
            db.sessions().replaceMembers(sessionId, snapshot.member_ids)
            snapshot.recalled_sha256.forEach { if (it.size == Hashes.SHA256_BYTES) onRecalled(sessionId, it.hex()) }
            snapshot.live_photos.forEach { onStored(s, it) }
            db.sessions().setLastEventId(sessionId, snapshot.cursor.ifEmpty { EMPTY_STREAM_CURSOR })
        }
    }

    /** Moves the cursor forward after a catch-up page whose events have all been handled. */
    suspend fun advanceCursor(sessionId: String, cursor: String) {
        if (cursor.isEmpty()) return
        mutex.withLock {
            val s = db.sessions().get() ?: return
            if (s.sessionId != sessionId) return
            val next = EventIds.max(s.lastEventId, cursor)
            if (next != s.lastEventId) db.sessions().setLastEventId(sessionId, next)
        }
    }

    /** The user recalled a photo; apply it now rather than waiting for the event. */
    suspend fun recallLocally(sessionId: String, sha256: String) {
        mutex.withLock { onRecalled(sessionId, sha256) }
    }

    /** 7.9 step 7: drop cached copies and pending downloads of photos past `expires_at`. */
    suspend fun sweepExpired() {
        mutex.withLock {
            for (row in db.received().expiredBy(clock.nowMs())) {
                scheduler.cancelReceive(row.sha256)
                cache.deleteCachedCopies(row.sha256)
                if (row.state == ReceiveState.SAVED) {
                    db.received().clearCachedPaths(row.sha256)
                } else {
                    db.received().markTerminal(row.sha256, ReceiveState.EXPIRED)
                }
            }
        }
    }

    /** The server answered 410 for a download. */
    suspend fun markGone(sessionId: String, sha256: String, recalled: Boolean) {
        mutex.withLock {
            if (recalled) {
                onRecalled(sessionId, sha256)
            } else {
                scheduler.cancelReceive(sha256)
                cache.deleteCachedCopies(sha256)
                val row = db.received().get(sha256)
                if (row != null && row.state != ReceiveState.SAVED) db.received().markTerminal(sha256, ReceiveState.EXPIRED)
            }
        }
    }

    private suspend fun apply(s: SessionState, event: PhotoEvent): EndReason? {
        return when (event.knownType()) {
            PhotoEvent.Type.PHOTO_STORED -> {
                onStored(s, event)
                null
            }
            PhotoEvent.Type.PHOTO_RECALLED -> {
                if (event.sha256.size == Hashes.SHA256_BYTES) onRecalled(s.sessionId, event.sha256.hex())
                null
            }
            PhotoEvent.Type.MEMBER_JOINED -> {
                if (event.member_id.isNotEmpty()) db.sessions().addMembers(listOf(SessionMember(s.sessionId, event.member_id)))
                null
            }
            PhotoEvent.Type.MEMBER_LEFT -> {
                if (event.member_id.isNotEmpty() && event.member_id == s.userId) {
                    EndReason.KICKED
                } else {
                    db.sessions().removeMember(s.sessionId, event.member_id)
                    null
                }
            }
            PhotoEvent.Type.HOST_CHANGED -> {
                // The host left and the role passed to the longest-present member. Host-only
                // actions (kick, end, rotate, recalling others' photos) follow the stored role.
                if (event.member_id.isNotEmpty()) {
                    db.sessions().setHost(s.sessionId, event.member_id, SessionRules.roleFor(event.member_id, s.userId))
                }
                null
            }
            PhotoEvent.Type.SESSION_ENDED -> EndReason.ENDED
            // A type this build doesn't know: skip it, but still advance past it.
            null -> null
        }
    }

    private suspend fun onStored(s: SessionState, event: PhotoEvent) {
        if (event.sha256.size != Hashes.SHA256_BYTES) return
        val sha = event.sha256.hex()
        val now = clock.nowMs()
        val local = LocalHashState(
            inHashes = db.hashes().contains(sha),
            inReceivedPhotos = db.received().get(sha) != null,
            inOwnQueue = db.pending().countByHash(sha) > 0,
        )
        val decision = ReceiveDedupe.decideStored(
            eventId = "",
            lastEventId = s.lastEventId,
            uploaderId = event.uploader_id,
            selfUserId = s.userId.ifEmpty { null },
            expiresAtMs = event.expires_at_ms,
            nowMs = now,
            local = local,
        )
        when (decision) {
            StoredDecision.OWN_PHOTO -> {
                // This also settles WAITING rows: the hash is now stored in the session, so the
                // other member's upload of these bytes succeeded and ours is not needed.
                db.pending().markSyncedByHash(sha)
                db.hashes().insertIgnore(KnownHash(sha, KnownHash.UPLOADED, s.sessionId, now))
            }
            StoredDecision.FETCH -> {
                val row = ReceivedPhoto(
                    sha256 = sha,
                    sessionId = s.sessionId,
                    uploader = event.uploader_id,
                    takenAtMs = event.taken_at_ms,
                    expiresAtMs = event.expires_at_ms,
                    sizeBytes = event.size,
                    state = ReceiveState.THUMB,
                    receivedAtMs = now,
                )
                if (db.received().insertIgnore(row) != -1L) scheduler.enqueueReceive(sha)
            }
            StoredDecision.DROP_SEEN_EVENT, StoredDecision.DROP_KNOWN_HASH, StoredDecision.DROP_EXPIRED -> Unit
        }
    }

    private suspend fun onRecalled(sessionId: String, sha: String) {
        scheduler.cancelReceive(sha)
        cache.deleteCachedCopies(sha)
        val row = db.received().get(sha)
        if (row == null) {
            // Tombstone, so a late PHOTO_STORED for this hash is dropped.
            db.received().insertIgnore(
                ReceivedPhoto(
                    sha256 = sha, sessionId = sessionId, uploader = "", takenAtMs = 0, expiresAtMs = 0,
                    sizeBytes = 0, state = ReceiveState.RECALLED, receivedAtMs = clock.nowMs(),
                ),
            )
        } else {
            // Saved originals stay in the gallery: the app has no right to delete the user's photos.
            db.received().markTerminal(sha, ReceiveState.RECALLED)
        }
        db.pending().markSkippedByHash(sha, "recalled")
    }

    companion object {
        /** The cursor of a stream with no entries yet. */
        const val EMPTY_STREAM_CURSOR = "0-0"
    }
}
