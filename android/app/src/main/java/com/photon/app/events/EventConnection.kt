package com.photon.app.events

import android.os.SystemClock
import android.util.Log
import com.photon.app.core.Backoff
import com.photon.app.core.NetworkMonitor
import com.photon.app.data.db.PhotonDatabase
import com.photon.app.net.ApiException
import com.photon.app.net.TokenRefresher
import com.photon.app.net.TokenStore
import com.photon.app.receive.ReceivePipeline
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
        val closes = CloseHandler(
            refresh = { refresher.refresh(it) },
            stillMember = { sessions().isStillMember() },
            resync = { eventSync.resync() },
            endSession = { sessions().endLocally(it) },
            loginRequired = onLoginRequired,
        )
        try {
            while (true) {
                if (db.sessions().get() == null) return
                if (!tokens.signedIn.value) return stopForLogin()
                stateFlow.value = ConnectionState.CONNECTING

                // Catch up first: covers a cold start or a dropped push before the socket is up.
                // Any failure here is transient for the connection: only the refresher may
                // decide that the login is over.
                try {
                    eventSync.catchUp()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "catch-up failed, retrying: ${e.message}")
                    waitBeforeRetry(attempt++, (e as? ApiException)?.retryAfterMs)
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
                }
                if (token == null) {
                    onLoginRequired()
                    return stopForLogin()
                }

                val openedAt = SystemClock.elapsedRealtime()
                val outcome = WsSession(
                    client = client,
                    url = wsUrl,
                    hello = helloFrame(token, s.sessionId, s.lastEventId),
                    onFrame = { frame ->
                        if (frame.ready_cursor != null) stateFlow.value = ConnectionState.LIVE
                        frame.event?.let { pipeline.handle(s.sessionId, it) }
                    },
                ).run()
                // Backoff resets only after a connection that stayed up, so a server closing every
                // socket after a few seconds (1011) gets backoff, not a reconnect storm.
                val stable = outcome.wasReady && SystemClock.elapsedRealtime() - openedAt >= STABLE_CONNECTION_MS
                if (stable) attempt = 0
                Log.i(TAG, "socket closed: code=${outcome.closeCode} error=${outcome.lastErrorCode} stable=$stable ${outcome.failure?.message.orEmpty()}")

                when (closes.onClose(outcome.closeCode, token, stable)) {
                    NextStep.RECONNECT_NOW -> Unit
                    NextStep.RECONNECT_AFTER_BACKOFF -> waitBeforeRetry(attempt++)
                    NextStep.STOP -> return
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

    /** Tokens are gone (the refresh endpoint refused them); the UI shows the login screen. */
    private fun stopForLogin() {
        Log.w(TAG, "event connection stopped: login required")
    }

    private companion object {
        const val TAG = "PHOTON"
        const val STABLE_CONNECTION_MS = 30_000L
    }
}
