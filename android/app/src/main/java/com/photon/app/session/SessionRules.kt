package com.photon.app.session

import com.photon.app.data.db.SessionState
import com.photon.app.net.ApiException
import com.photon.app.net.ErrorCodes
import com.photon.app.net.ErrorKind
import com.photon.proto.SessionInfo
import kotlinx.coroutines.CancellationException

object SessionRules {
    /** This user's role once [hostId] holds the host role (HOST_CHANGED, SessionInfo). */
    fun roleFor(hostId: String, selfUserId: String?): String =
        if (!selfUserId.isNullOrEmpty() && hostId == selfUserId) SessionState.ROLE_HOST else SessionState.ROLE_MEMBER

    /** True when this user was just handed the host role in the same session. */
    fun becameHost(previous: SessionState?, current: SessionState?): Boolean =
        previous != null && current != null && previous.sessionId == current.sessionId &&
            !previous.isHost && current.isHost
}

/**
 * Join or create answered `409 ALREADY_IN_SESSION`: the server still has this user in another
 * session (one this device lost track of, or joined from another device). `GET /v1/sessions/current`
 * says which one, so the user can open it or leave it and retry.
 */
object JoinConflict {
    sealed interface Attempt<out T> {
        data class Done<T>(val value: T) : Attempt<T>

        /** The user is already in [info]; ask whether to open it or leave it. */
        data class InOther(val info: SessionInfo) : Attempt<Nothing>
    }

    fun isConflict(e: Throwable): Boolean = e is ApiException && e.isCode(ErrorCodes.ALREADY_IN_SESSION)

    /**
     * Runs [action]. On ALREADY_IN_SESSION it asks [current] for the other session; if the server
     * no longer reports one (it ended meanwhile), the action is retried once.
     */
    suspend fun <T> attempt(action: suspend () -> T, current: suspend () -> SessionInfo?): Attempt<T> {
        try {
            return Attempt.Done(action())
        } catch (e: ApiException) {
            if (!isConflict(e)) throw e
        }
        val other = current() ?: return Attempt.Done(action())
        return Attempt.InOther(other)
    }

    /** A leave whose target is already gone (ended, left, kicked, expired) counts as done. */
    fun isAlreadyGone(e: ApiException): Boolean =
        e.endsSession || e.kind == ErrorKind.NOT_FOUND || e.kind == ErrorKind.FORBIDDEN

    suspend fun leaveTolerant(leave: suspend () -> Unit) {
        try {
            leave()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (!isAlreadyGone(e)) throw e
        }
    }
}
