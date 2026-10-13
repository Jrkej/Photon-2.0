package com.photon.app.events

import android.util.Log
import com.photon.app.core.Backoff
import com.photon.app.core.NetworkMonitor
import com.photon.app.data.db.PhotonDatabase
import com.photon.app.net.ApiException
import com.photon.app.net.ErrorKind
import com.photon.app.net.TokenRefresher
import com.photon.app.net.TokenStore
import com.photon.app.receive.ReceivePipeline
import com.photon.app.session.EndReason
import com.photon.app.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException

enum class ConnectionState { IDLE, CONNECTING, LIVE, OFFLINE }

/**
 * The live event connection (7.8). It runs while anyone holds it: [com.photon.app.sync.SyncService]
 * and the app while in the foreground. Each round: catch up over HTTP, connect with the cursor,
 * process frames in order, then act on the close code.
 */
class EventConnection(
    private val scope: CoroutineScope,
    private val db: PhotonDatabase,
    private val client: OkHttpClient,
    private val wsUrl: String,
    private val eventSync: EventSync,
    private val pipeline: ReceivePipeline,
    private val refresher: TokenRefresher,
    private val tokens: TokenStore,
    private val sessions: () -> SessionManager,
    private val network: NetworkMonitor,
    /** Called when the server rejects the token outright (4401): the user must log in again. */
    private val onLoginRequired: () -> Unit,
    private val backoff: Backoff = Backoff(baseMs = 1_000, capMs = 60_000),
) {
    private val holders = mutableSetOf<String>()
    private var job: Job? = null
    private val stateFlow = MutableStateFlow(ConnectionState.IDLE)
    val state: StateFlow<ConnectionState> = stateFlow.asStateFlow()

    @Synchronized
    fun acquire(holder: String) {
        holders += holder
        if (job?.isActive != true) {
            job = scope.launch { runLoop() }
        }
    }

    @Synchronized
    fun release(holder: String) {
        holders -= holder
        if (holders.isEmpty()) stopJob()
    }

    @Synchronized
    fun stopAll() {
        holders.clear()
        stopJob()
    }

    private fun stopJob() {
        job?.cancel()
        job = null
        stateFlow.value = ConnectionState.IDLE
    }

    private suspend fun runLoop() {
        var attempt = 0
        try {
            while (true) {
                if (db.sessions().get() == null) return
                stateFlow.value = ConnectionState.CONNECTING

                // Catch up first: covers a cold start or a dropped push before the socket is up.
                try {
                    eventSync.catchUp()
                } catch (e: IOException) {
                    waitBeforeRetry(attempt++)
                    continue
                } catch (e: ApiException) {
                    if (e.kind == ErrorKind.AUTH) return stopForLogin()
                    waitBeforeRetry(attempt++, e.retryAfterMs)
                    continue
                }

                val s = db.sessions().get() ?: return
                if (s.lastEventId.isEmpty()) {
                    waitBeforeRetry(attempt++)
                    continue
                }
                val token = try {
                    refresher.validAccessToken()
                } catch (e: IOException) {
                    waitBeforeRetry(attempt++)
                    continue
                } ?: return stopForLogin()

                val outcome = WsSession(
                    client = client,
                    url = wsUrl,
                    hello = helloFrame(token, s.sessionId, s.lastEventId),
                    onFrame = { frame ->
                        if (frame.ready_cursor != null) stateFlow.value = ConnectionState.LIVE
                        frame.event?.let { pipeline.handle(s.sessionId, it) }
                    },
                ).run()
                if (outcome.wasReady) attempt = 0
                Log.i(TAG, "socket closed: code=${outcome.closeCode} error=${outcome.lastErrorCode} ${outcome.failure?.message.orEmpty()}")

                when (CloseCodes.actionFor(outcome.closeCode)) {
                    WsAction.REFRESH_AND_RECONNECT -> {
                        val fresh = try {
                            refresher.refresh(token)
                        } catch (e: IOException) {
                            waitBeforeRetry(attempt++)
                            continue
                        }
                        if (fresh == null) return stopForLogin()
                    }
                    WsAction.RELOGIN -> {
                        tokens.clear()
                        onLoginRequired()
                        return stopForLogin()
                    }
                    WsAction.STOP_NOT_MEMBER -> {
                        sessions().endLocally(EndReason.KICKED)
                        return
                    }
                    WsAction.RESYNC_AND_RECONNECT -> {
                        try {
                            eventSync.resync()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            waitBeforeRetry(attempt++)
                        }
                    }
                    WsAction.SESSION_ENDED -> {
                        sessions().endLocally(EndReason.ENDED)
                        return
                    }
                    WsAction.RECONNECT_WITH_BACKOFF -> waitBeforeRetry(attempt++)
                }
            }
        } finally {
            stateFlow.value = ConnectionState.IDLE
        }
    }

    /** Backoff with jitter, cut short when a network comes back. */
    private suspend fun waitBeforeRetry(attempt: Int, atLeastMs: Long? = null) {
        stateFlow.value = ConnectionState.OFFLINE
        val wait = maxOf(backoff.delayMs(attempt), atLeastMs ?: 0)
        withTimeoutOrNull(wait) {
            // The callback fires once at registration when a network is already up; only a
            // network that appears later should cut the wait short.
            var skip = network.quality().connected
            network.availability().first { if (skip) { skip = false; false } else true }
        }
    }

    /** Tokens are gone (the refresher or a 4401 cleared them); the UI shows the login screen. */
    private fun stopForLogin() {
        Log.w(TAG, "event connection stopped: login required")
    }

    private companion object {
        const val TAG = "PHOTON"
    }
}
