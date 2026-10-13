package com.photon.app.events

import com.photon.app.session.EndReason
import kotlinx.coroutines.CancellationException

enum class NextStep { RECONNECT_NOW, RECONNECT_AFTER_BACKOFF, STOP }

/**
 * What the event connection does after a socket closes. Only definitive answers end anything:
 * - The login ends only when the refresh endpoint refuses the refresh token ([refresh] returns
 *   null). A 4401 first tries a refresh, like a 4001.
 * - The session ends locally on 4403 or 4410 only once `GET /v1/sessions/current` confirms this
 *   user is no longer in it ([stillMember] returns false).
 * - Everything else (1011, 1012, drops, timeouts, refresh or check failures) reconnects with
 *   backoff and keeps tokens, session and service.
 */
class CloseHandler(
    /** The single-flight refresher: a new token, null when refused, or throws when it couldn't tell. */
    private val refresh: suspend (staleToken: String) -> String?,
    /** True or false from the server; null when it couldn't be asked. */
    private val stillMember: suspend () -> Boolean?,
    private val resync: suspend () -> Unit,
    private val endSession: suspend (EndReason) -> Unit,
    private val loginRequired: () -> Unit,
) {
    private var consecutiveAuthCloses = 0

    /**
     * [stable] is true when the connection was accepted and stayed up for a while; it resets the
     * guard against a server that rejects every fresh token.
     */
    suspend fun onClose(closeCode: Int?, token: String, stable: Boolean): NextStep {
        if (stable) consecutiveAuthCloses = 0
        return when (CloseCodes.actionFor(closeCode)) {
            WsAction.REFRESH_AND_RECONNECT, WsAction.RELOGIN -> refreshThenReconnect(token)
            WsAction.STOP_NOT_MEMBER -> endIfConfirmed(EndReason.KICKED)
            WsAction.SESSION_ENDED -> endIfConfirmed(EndReason.ENDED)
            WsAction.RESYNC_AND_RECONNECT -> attempt { resync() }?.let { NextStep.RECONNECT_NOW } ?: NextStep.RECONNECT_AFTER_BACKOFF
            WsAction.RECONNECT_WITH_BACKOFF -> NextStep.RECONNECT_AFTER_BACKOFF
        }
    }

    private suspend fun refreshThenReconnect(token: String): NextStep {
        val outcome = attempt { refresh(token) } ?: return NextStep.RECONNECT_AFTER_BACKOFF
        val fresh = outcome.value
        if (fresh == null) {
            loginRequired()
            return NextStep.STOP
        }
        // A server that rejects every new token must not get a tight refresh loop.
        return if (++consecutiveAuthCloses <= 1) NextStep.RECONNECT_NOW else NextStep.RECONNECT_AFTER_BACKOFF
    }

    private suspend fun endIfConfirmed(reason: EndReason): NextStep {
        val member = attempt { stillMember() }?.value
        if (member == false) {
            endSession(reason)
            return NextStep.STOP
        }
        return NextStep.RECONNECT_AFTER_BACKOFF
    }

    private class Box<T>(val value: T)

    /** Runs [block]; any failure other than cancellation counts as "couldn't tell" (null). */
    private suspend fun <T> attempt(block: suspend () -> T): Box<T>? = try {
        Box(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
