package com.photon.app.events

/** What the event connection does after the socket closes (implementation plan 5.5). */
enum class WsAction {
    /** 4001: refresh the access token and reconnect from the last event id at once. */
    REFRESH_AND_RECONNECT,

    /** 4401: the token is invalid; the user must log in again. */
    RELOGIN,

    /** 4403: no longer a member (left or kicked); stop syncing. */
    STOP_NOT_MEMBER,

    /** 4409: load the snapshot, apply it, reconnect from its cursor. */
    RESYNC_AND_RECONNECT,

    /** 4410: the session ended; stop syncing and clear session state. */
    SESSION_ENDED,

    /** 1012, network drops, 4400 and anything unknown: reconnect with backoff and jitter. */
    RECONNECT_WITH_BACKOFF,
}

object CloseCodes {
    const val NORMAL = 1000
    const val SERVICE_RESTART = 1012
    const val TOKEN_EXPIRED = 4001
    const val BAD_HELLO = 4400
    const val INVALID_TOKEN = 4401
    const val NOT_A_MEMBER = 4403
    const val RESYNC_REQUIRED = 4409
    const val SESSION_ENDED = 4410

    /** [code] is null when the connection failed without a close frame. */
    fun actionFor(code: Int?): WsAction = when (code) {
        TOKEN_EXPIRED -> WsAction.REFRESH_AND_RECONNECT
        INVALID_TOKEN -> WsAction.RELOGIN
        NOT_A_MEMBER -> WsAction.STOP_NOT_MEMBER
        RESYNC_REQUIRED -> WsAction.RESYNC_AND_RECONNECT
        SESSION_ENDED -> WsAction.SESSION_ENDED
        else -> WsAction.RECONNECT_WITH_BACKOFF
    }
}
