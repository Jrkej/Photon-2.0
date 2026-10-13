package com.photon.app.events

import org.junit.Assert.assertEquals
import org.junit.Test

class CloseCodesTest {

    @Test
    fun everyCodeInThePlanHasItsAction() {
        assertEquals(WsAction.REFRESH_AND_RECONNECT, CloseCodes.actionFor(4001))
        assertEquals(WsAction.RECONNECT_WITH_BACKOFF, CloseCodes.actionFor(4400))
        assertEquals(WsAction.RELOGIN, CloseCodes.actionFor(4401))
        assertEquals(WsAction.STOP_NOT_MEMBER, CloseCodes.actionFor(4403))
        assertEquals(WsAction.RESYNC_AND_RECONNECT, CloseCodes.actionFor(4409))
        assertEquals(WsAction.SESSION_ENDED, CloseCodes.actionFor(4410))
        assertEquals(WsAction.RECONNECT_WITH_BACKOFF, CloseCodes.actionFor(1012))
    }

    @Test
    fun dropsAndUnknownCodesReconnectWithBackoff() {
        assertEquals(WsAction.RECONNECT_WITH_BACKOFF, CloseCodes.actionFor(null))
        assertEquals(WsAction.RECONNECT_WITH_BACKOFF, CloseCodes.actionFor(1000))
        assertEquals(WsAction.RECONNECT_WITH_BACKOFF, CloseCodes.actionFor(1006))
        assertEquals(WsAction.RECONNECT_WITH_BACKOFF, CloseCodes.actionFor(1011))
        assertEquals(WsAction.RECONNECT_WITH_BACKOFF, CloseCodes.actionFor(4999))
    }
}
