package com.photon.app.events

import com.photon.app.net.RefreshFailedException
import com.photon.app.session.EndReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CloseHandlerTest {
    private var refreshCalls = 0
    private var refreshResult: () -> String? = { "fresh" }
    private var member: () -> Boolean? = { true }
    private var resyncs = 0
    private var resyncFails = false
    private val ended = mutableListOf<EndReason>()
    private var loginRequired = 0

    private val handler = CloseHandler(
        refresh = {
            refreshCalls++
            refreshResult()
        },
        stillMember = { member() },
        resync = {
            resyncs++
            if (resyncFails) throw IOException("offline")
        },
        endSession = { ended += it },
        loginRequired = { loginRequired++ },
    )

    private suspend fun close(code: Int?, stable: Boolean = true) = handler.onClose(code, "old-token", stable)

    @Test
    fun invalidTokenCloseRefreshesAndReconnectsInsteadOfSigningOut() = runTest {
        assertEquals(NextStep.RECONNECT_NOW, close(4401))
        assertEquals(1, refreshCalls)
        assertEquals(0, loginRequired)
    }

    @Test
    fun invalidTokenSignsOutOnlyWhenTheRefreshIsRefused() = runTest {
        refreshResult = { null }
        assertEquals(NextStep.STOP, close(4401))
        assertEquals(1, loginRequired)
    }

    @Test
    fun refreshThatCannotReachTheServerKeepsTheLogin() = runTest {
        refreshResult = { throw RefreshFailedException(IOException("timeout")) }
        assertEquals(NextStep.RECONNECT_AFTER_BACKOFF, close(4401))
        assertEquals(NextStep.RECONNECT_AFTER_BACKOFF, close(4001))
        assertEquals(0, loginRequired)
    }

    @Test
    fun expiredTokenRefreshesAndReconnects() = runTest {
        assertEquals(NextStep.RECONNECT_NOW, close(4001))
        assertEquals(1, refreshCalls)
    }

    @Test
    fun repeatedAuthClosesBackOffInsteadOfLooping() = runTest {
        assertEquals(NextStep.RECONNECT_NOW, close(4401, stable = false))
        assertEquals(NextStep.RECONNECT_AFTER_BACKOFF, close(4401, stable = false))
        assertEquals(NextStep.RECONNECT_AFTER_BACKOFF, close(4001, stable = false))
        // A connection that stayed up resets the guard.
        assertEquals(NextStep.RECONNECT_NOW, close(4001, stable = true))
        assertEquals(0, loginRequired)
    }

    @Test
    fun transientClosesNeverTouchLoginOrSession() = runTest {
        for (code in listOf(null, 1000, 1006, 1011, 1012, 4400, 4999)) {
            assertEquals("$code", NextStep.RECONNECT_AFTER_BACKOFF, close(code))
        }
        assertEquals(0, refreshCalls)
        assertEquals(0, loginRequired)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun notAMemberEndsTheSessionOnlyWhenTheServerConfirms() = runTest {
        member = { true }
        assertEquals(NextStep.RECONNECT_AFTER_BACKOFF, close(4403))
        member = { null }
        assertEquals(NextStep.RECONNECT_AFTER_BACKOFF, close(4403))
        member = { throw IOException("offline") }
        assertEquals(NextStep.RECONNECT_AFTER_BACKOFF, close(4403))
        assertTrue(ended.isEmpty())

        member = { false }
        assertEquals(NextStep.STOP, close(4403))
        assertEquals(listOf(EndReason.KICKED), ended)
    }

    @Test
    fun sessionEndedIsConfirmedToo() = runTest {
        member = { false }
        assertEquals(NextStep.STOP, close(4410))
        assertEquals(listOf(EndReason.ENDED), ended)
        assertFalse(loginRequired > 0)
    }

    @Test
    fun resyncThenReconnect() = runTest {
        assertEquals(NextStep.RECONNECT_NOW, close(4409))
        resyncFails = true
        assertEquals(NextStep.RECONNECT_AFTER_BACKOFF, close(4409))
        assertEquals(2, resyncs)
    }
}
