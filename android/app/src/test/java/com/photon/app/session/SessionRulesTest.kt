package com.photon.app.session

import com.photon.app.data.db.SessionState
import com.photon.app.net.ApiException
import com.photon.app.net.ErrorMapper
import com.photon.proto.Error
import com.photon.proto.SessionInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRulesTest {

    private fun state(id: String = "s1", role: String) = SessionState(
        sessionId = id, userId = "alice", role = role, hostId = "", joinTimeMs = 0, lastEventId = "", scanCursorMs = 0,
        paused = false, pausedAtMs = null, joinCode = "", inviteUrl = "", qrPayload = "", expiresAtMs = 0,
    )

    private fun error(status: Int, code: String) = ErrorMapper.map(status, Error(code = code).encode(), null)

    @Test
    fun hostChangedGivesTheRoleToTheNewHostOnly() {
        assertEquals(SessionState.ROLE_HOST, SessionRules.roleFor("alice", "alice"))
        assertEquals(SessionState.ROLE_MEMBER, SessionRules.roleFor("bob", "alice"))
        assertEquals(SessionState.ROLE_MEMBER, SessionRules.roleFor("", ""))
        assertEquals(SessionState.ROLE_MEMBER, SessionRules.roleFor("alice", null))
    }

    @Test
    fun becameHostOnlyForAPromotionInTheSameSession() {
        assertTrue(SessionRules.becameHost(state(role = "member"), state(role = "host")))
        assertFalse(SessionRules.becameHost(state(role = "host"), state(role = "host")))
        assertFalse(SessionRules.becameHost(state(role = "host"), state(role = "member")))
        assertFalse(SessionRules.becameHost(null, state(role = "host"))) // created or joined as host
        assertFalse(SessionRules.becameHost(state(id = "s0", role = "member"), state(role = "host")))
    }

    @Test
    fun attemptReturnsTheResultWhenThereIsNoConflict() = runTest {
        val r = JoinConflict.attempt(action = { "joined" }, current = { error("not asked") })
        assertEquals(JoinConflict.Attempt.Done("joined"), r)
    }

    @Test
    fun alreadyInSessionReportsTheOtherSession() = runTest {
        val other = SessionInfo(session_id = "s9", join_code = "ABCD2345", role = "member")
        var calls = 0
        val r = JoinConflict.attempt(
            action = {
                calls++
                throw error(409, "ALREADY_IN_SESSION")
            },
            current = { other },
        )
        assertEquals(JoinConflict.Attempt.InOther(other), r)
        assertEquals(1, calls)
    }

    @Test
    fun conflictWithNoCurrentSessionRetriesOnce() = runTest {
        var calls = 0
        val r = JoinConflict.attempt(
            action = {
                if (calls++ == 0) throw error(409, "ALREADY_IN_SESSION")
                "joined"
            },
            current = { null }, // the other session ended in between
        )
        assertEquals(JoinConflict.Attempt.Done("joined"), r)
        assertEquals(2, calls)
    }

    @Test
    fun otherErrorsPassThrough() = runTest {
        val thrown = runCatching {
            JoinConflict.attempt(action = { throw error(404, "SESSION_NOT_FOUND") }, current = { error("not asked") })
        }.exceptionOrNull()
        assertEquals("SESSION_NOT_FOUND", (thrown as ApiException).code)
    }

    @Test
    fun leavingASessionThatIsAlreadyGoneCountsAsDone() = runTest {
        JoinConflict.leaveTolerant { throw error(410, "SESSION_ENDED") }
        JoinConflict.leaveTolerant { throw error(403, "NOT_A_MEMBER") }
        JoinConflict.leaveTolerant { throw error(404, "SESSION_NOT_FOUND") }
        val busy = runCatching { JoinConflict.leaveTolerant { throw error(503, "BUSY") } }.exceptionOrNull()
        assertEquals("BUSY", (busy as ApiException).code)
    }
}
