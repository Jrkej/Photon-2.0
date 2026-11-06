package com.photon.app.session

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.photon.app.core.Clock
import com.photon.app.core.MediaCache
import com.photon.app.data.db.PauseWindow
import com.photon.app.data.db.PhotonDatabase
import com.photon.app.data.db.SessionState
import com.photon.app.events.EventConnection
import com.photon.app.e2ee.Bundle
import com.photon.app.events.EventSync
import com.photon.app.keys.KeyManager
import com.photon.app.keys.KeySync
import com.photon.app.net.ApiException
import com.photon.app.net.ErrorCodes
import com.photon.app.net.PhotonApi
import com.photon.app.net.TokenStore
import com.photon.app.receive.ReceivePipeline
import com.photon.app.sync.SyncService
import com.photon.app.sync.WorkScheduler
import com.photon.proto.CreateSessionRequest
import com.photon.proto.JoinRequest
import com.photon.proto.SessionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import java.io.IOException

enum class EndReason { LEFT, KICKED, ENDED, SIGNED_OUT }

/**
 * Session lifecycle (7.4, 7.12): create, join, leave, host actions, pause, and the local
 * teardown that runs however a session ends.
 */
class SessionManager(
    private val context: Context,
    private val db: PhotonDatabase,
    private val api: PhotonApi,
    private val tokens: TokenStore,
    private val scheduler: WorkScheduler,
    private val cache: MediaCache,
    private val eventSync: () -> EventSync,
    private val connection: () -> EventConnection,
    private val pipeline: () -> ReceivePipeline,
    private val clock: Clock,
    private val keys: KeyManager,
    private val keySync: () -> KeySync,
) {
    private val mutex = Mutex()
    private val endedFlow = MutableSharedFlow<EndReason>(extraBufferCapacity = 8)

    val session: Flow<SessionState?> = db.sessions().observe()
    val ended: SharedFlow<EndReason> = endedFlow.asSharedFlow()

    suspend fun current(): SessionState? = db.sessions().get()

    /**
     * Creates a session. This phone makes epoch 1 (`K_1` and the tag key `D`) before asking, and keeps it once
     * the server answers; only the member key goes to the server (e2ee.md 5.1).
     */
    suspend fun create(maxMembers: Int = 0): SessionState {
        val epochOne = keys.newSessionBundle()
        val info = api.createSession(CreateSessionRequest(max_members = maxMembers, member_key = memberKey()))
        return enter(info, epochOne)
    }

    /** Joins with this install's member key; the joiner holds no epoch until a member shares it (e2ee.md 5.3). */
    suspend fun join(target: JoinTarget): SessionState {
        val request = when (target) {
            is JoinTarget.Code -> JoinRequest(code = target.code, member_key = memberKey())
            is JoinTarget.Invite -> JoinRequest(invite_token = target.token, member_key = memberKey())
        }
        return enter(api.join(request))
    }

    /** Off the main thread: the first call makes the key and writes it to encrypted storage. */
    private suspend fun memberKey() = withContext(Dispatchers.IO) { keys.memberKey().toByteString() }

    /**
     * The server holds another member key for this user in the local session (the session was joined from
     * another install, or this install's key was lost): joining again with the code registers this one, and
     * members seal the key for it (the server answers `REKEYED`). Best effort.
     */
    suspend fun rekey(): Boolean {
        val s = db.sessions().get() ?: return false
        if (s.joinCode.isEmpty()) return false
        return try {
            api.join(JoinRequest(code = s.joinCode, member_key = memberKey()))
            true
        } catch (e: IOException) {
            false
        } catch (e: ApiException) {
            Log.w("PHOTON", "joining again with this install's key failed: ${e.code}")
            false
        }
    }

    private suspend fun enter(info: SessionInfo, created: Bundle? = null): SessionState {
        val now = clock.nowMs()
        mutex.withLock {
            val previous = db.sessions().get()
            if (previous != null && previous.sessionId == info.session_id) {
                // Joined again (an invite opened twice): keep the join time and the cursor.
                storeInfo(info)
                if (info.member_ids.isNotEmpty()) db.sessions().replaceMembers(info.session_id, info.member_ids)
                return@withLock
            }
            if (previous != null) clearLocal("replaced by ${info.session_id}")
            // After clearLocal, which drops every session's keys.
            created?.let { withContext(Dispatchers.IO) { keys.adoptCreatedSession(info.session_id, it) } }
            val state = SessionState(
                sessionId = info.session_id,
                userId = tokens.userId.orEmpty(),
                role = roleOf(info),
                hostId = info.host_id,
                joinTimeMs = now,
                lastEventId = "",
                scanCursorMs = now,
                paused = false,
                pausedAtMs = null,
                joinCode = info.join_code,
                inviteUrl = info.invite_url,
                qrPayload = info.qr_payload,
                expiresAtMs = info.expires_at_ms,
            )
            db.withTransaction {
                db.sessions().insert(state)
                db.sessions().replaceMembers(info.session_id, info.member_ids)
            }
        }
        // Load the snapshot before connecting: the WebSocket refuses an empty cursor. If the
        // network drops here, the connection loop loads it when it can.
        try {
            eventSync().resync()
        } catch (e: IOException) {
            // retried by the connection loop
        } catch (e: ApiException) {
            if (!onSessionError(e)) throw e
        }
        // Key maintenance: a joiner may find a key already shared, a creator seals for whoever joined meanwhile.
        // Offline, the catch-up and the event stream run it again.
        try {
            keySync().runNow()
        } catch (e: IOException) {
            keySync().maintenanceNeeded()
        } catch (e: ApiException) {
            Log.w("PHOTON", "key maintenance after entering failed: ${e.code}")
        }
        startSyncing("session entered")
        return db.sessions().get() ?: throw IllegalStateException("session ended while joining")
    }

    /** Starts (or restarts) everything that runs while a session is active. */
    fun startSyncing(reason: String) {
        SyncService.start(context, reason)
        scheduler.ensureBackgroundTriggers()
        scheduler.enqueueReconcile()
        scheduler.enqueueUploads()
    }

    suspend fun leave() {
        val s = db.sessions().get() ?: return
        // Already gone on the server: leaving locally is all that's left.
        JoinConflict.leaveTolerant { api.leave(s.sessionId) }
        endLocally(EndReason.LEFT)
    }

    /**
     * Whether the server still has this user in the local session: true or false, or null when
     * it can't be asked right now. Used to confirm a 4403 or 4410 close before tearing down.
     */
    suspend fun isStillMember(): Boolean? {
        val s = db.sessions().get() ?: return false
        return try {
            api.currentSession()?.session_id == s.sessionId
        } catch (e: IOException) {
            null
        } catch (e: ApiException) {
            null
        }
    }

    /** The session the server has this user in (`GET /v1/sessions/current`), or null. */
    suspend fun serverSession(): SessionInfo? = api.currentSession()

    /**
     * Opens a session the user is already a member of on the server but this device doesn't
     * know about (local state lost, or joined elsewhere). The join time is now: photos taken
     * before this device opened the session are not shared.
     */
    suspend fun open(info: SessionInfo): SessionState = enter(info)

    /** Leaves [sessionId] on the server, whether or not it is the local session (ALREADY_IN_SESSION). */
    suspend fun leaveRemote(sessionId: String) {
        JoinConflict.leaveTolerant { api.leave(sessionId) }
        if (db.sessions().get()?.sessionId == sessionId) endLocally(EndReason.LEFT)
    }

    /**
     * Re-reads role, host and invite from the server. Called after a resync, because a snapshot
     * carries no host and a HOST_CHANGED event older than the snapshot is never replayed. Best
     * effort: the event stream stays the authority on membership and session end.
     */
    suspend fun refreshFromServer() {
        val s = db.sessions().get() ?: return
        val info = try {
            api.currentSession()
        } catch (e: IOException) {
            return
        } catch (e: ApiException) {
            return
        } ?: return
        if (info.session_id == s.sessionId) storeInfo(info)
    }

    private suspend fun storeInfo(info: SessionInfo) {
        db.sessions().updateInfo(
            sessionId = info.session_id,
            role = roleOf(info),
            hostId = info.host_id,
            code = info.join_code,
            inviteUrl = info.invite_url,
            qr = info.qr_payload,
            expiresAtMs = info.expires_at_ms,
        )
    }

    private fun roleOf(info: SessionInfo): String =
        info.role.ifEmpty { SessionRules.roleFor(info.host_id, tokens.userId) }

    suspend fun endForEveryone() {
        val s = db.sessions().get() ?: return
        try {
            api.end(s.sessionId)
        } catch (e: ApiException) {
            if (!e.endsSession) throw e
        }
        endLocally(EndReason.ENDED)
    }

    suspend fun kick(memberId: String) {
        val s = db.sessions().get() ?: return
        api.kick(s.sessionId, memberId)
        db.sessions().removeMember(s.sessionId, memberId)
    }

    suspend fun rotateInvites() {
        val s = db.sessions().get() ?: return
        val info = api.rotateInvites(s.sessionId)
        if (info.session_id.isEmpty() || info.session_id == s.sessionId) storeInfo(info.copy(session_id = s.sessionId))
    }

    /**
     * Pause stops enqueuing and uploading. Photos captured while paused are never shared: the
     * pause becomes a window the eligibility filter excludes. Resuming runs the reconcile scan.
     */
    suspend fun setPaused(paused: Boolean) {
        mutex.withLock {
            val s = db.sessions().get() ?: return
            if (s.paused == paused) return
            val now = clock.nowMs()
            if (paused) {
                db.sessions().setPaused(s.sessionId, true, now)
                scheduler.cancelUploads()
            } else {
                db.withTransaction {
                    db.sessions().insertPauseWindow(PauseWindow(sessionId = s.sessionId, startMs = s.pausedAtMs ?: now, endMs = now))
                    db.sessions().setPaused(s.sessionId, false, null)
                }
            }
        }
        if (!paused) {
            scheduler.enqueueReconcile()
            scheduler.enqueueUploads()
        }
    }

    suspend fun recall(sha256: String) {
        val s = db.sessions().get() ?: return
        try {
            api.recall(s.sessionId, sha256)
        } catch (e: ApiException) {
            if (!e.isCode(ErrorCodes.PHOTO_RECALLED)) {
                if (onSessionError(e)) return
                throw e
            }
        }
        pipeline().recallLocally(s.sessionId, sha256)
    }

    suspend fun report(sha256: String, reason: String) {
        val s = db.sessions().get() ?: return
        api.report(s.sessionId, sha256, reason)
    }

    /**
     * Handles an error from a call scoped to the current session. Returns true when it ended
     * the local session (the session is over, or this user is no longer a member).
     */
    suspend fun onSessionError(e: ApiException): Boolean {
        val reason = when (e.code) {
            ErrorCodes.SESSION_ENDED, ErrorCodes.SESSION_NOT_FOUND -> EndReason.ENDED
            ErrorCodes.NOT_A_MEMBER -> EndReason.KICKED
            else -> return false
        }
        endLocally(reason)
        return true
    }

    /** After a login: resume the stored session if it belongs to this user, otherwise drop it. */
    suspend fun onSignedIn() {
        val s = db.sessions().get()
        if (s != null) {
            val uid = tokens.userId
            if (s.userId.isNotEmpty() && uid != null && s.userId != uid) {
                endLocally(EndReason.SIGNED_OUT)
            } else {
                startSyncing("signed in")
                scheduler.enqueueCatchUp()
            }
        }
        scheduler.enqueueDeviceRegistration()
    }

    suspend fun signOut() {
        val s = db.sessions().get()
        if (s != null) {
            try {
                api.leave(s.sessionId)
            } catch (e: Exception) {
                // best effort: the server's membership expires with the session anyway
            }
            endLocally(EndReason.SIGNED_OUT)
        }
        tokens.clear()
    }

    /**
     * 7.12: stop the service, cancel the session's work, delete cached copies and thumbnails,
     * clear the session tables and the session's keys. `hashes` and `received_assets` stay for
     * 24 hours; the member key stays for the next session.
     */
    suspend fun endLocally(reason: EndReason) {
        val ended = withContext(NonCancellable) {
            mutex.withLock {
                if (db.sessions().get() == null) return@withLock false
                Log.i("PHOTON", "ending the local session: $reason")
                clearLocal(reason.name)
                true
            }
        }
        if (ended) endedFlow.tryEmit(reason)
    }

    private suspend fun clearLocal(reason: String) {
        scheduler.cancelSessionWork()
        connection().stopAll()
        SyncService.stop(context, "session over: $reason")
        db.withTransaction {
            db.sessions().clear()
            db.sessions().clearAllMembers()
            db.sessions().clearPauseWindows()
            db.pending().clear()
            db.received().clear()
        }
        cache.clearSessionFiles()
        withContext(Dispatchers.IO) { keys.clearAll() }
        keySync().forgetSession()
        scheduler.schedulePurge(PURGE_AFTER_MS)
    }

    companion object {
        const val PURGE_AFTER_MS = 24L * 60 * 60 * 1000
    }
}
