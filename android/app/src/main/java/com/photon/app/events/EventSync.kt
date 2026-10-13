package com.photon.app.events

import com.photon.app.data.db.PhotonDatabase
import com.photon.app.data.db.SessionState
import com.photon.app.net.ApiException
import com.photon.app.net.ErrorCodes
import com.photon.app.net.PhotonApi
import com.photon.app.receive.ReceivePipeline
import com.photon.app.session.SessionManager
import java.io.IOException

/**
 * Catch-up over HTTP (7.8, 8.5): `/events?since=` page by page, or the snapshot when the server
 * says the cursor can't be continued. Runs before every WebSocket connect, on app start and on
 * every push hint. Throws [IOException] on network trouble so callers can retry.
 */
class EventSync(
    private val db: PhotonDatabase,
    private val api: PhotonApi,
    private val pipeline: ReceivePipeline,
    private val sessions: () -> SessionManager,
) {
    suspend fun catchUp() {
        var resyncs = 0
        while (true) {
            val s = db.sessions().get() ?: return
            if (s.lastEventId.isEmpty()) {
                if (resyncs++ >= MAX_RESYNCS) throw IOException("snapshot did not give a cursor")
                if (!resync(s)) return
                continue
            }
            val batch = try {
                api.events(s.sessionId, s.lastEventId, PhotonApi.EVENTS_PAGE)
            } catch (e: ApiException) {
                if (e.isCode(ErrorCodes.RESYNC_REQUIRED)) {
                    if (resyncs++ >= MAX_RESYNCS) throw e
                    if (!resync(s)) return
                    continue
                }
                if (sessions().onSessionError(e)) return
                throw e
            }
            pipeline.handleAll(s.sessionId, batch.events)
            pipeline.advanceCursor(s.sessionId, batch.cursor)
            if (batch.events.size < PhotonApi.EVENTS_PAGE) return
            val after = db.sessions().get() ?: return
            if (after.lastEventId == s.lastEventId) return // no progress; avoid spinning
        }
    }

    /** Loads and applies the snapshot. Returns false when the session ended meanwhile. */
    suspend fun resync(): Boolean {
        val s = db.sessions().get() ?: return false
        return resync(s)
    }

    private suspend fun resync(s: SessionState): Boolean {
        val snapshot = try {
            api.snapshot(s.sessionId)
        } catch (e: ApiException) {
            if (sessions().onSessionError(e)) return false
            throw e
        }
        pipeline.applySnapshot(s.sessionId, snapshot)
        if (db.sessions().get()?.sessionId != s.sessionId) return false
        sessions().refreshFromServer()
        return true
    }

    private companion object {
        const val MAX_RESYNCS = 3
    }
}
