package com.photon.app

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.photon.app.data.db.PhotonDatabase
import com.photon.app.data.db.SessionState

object TestSupport {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun inMemoryDb(): PhotonDatabase = PhotonDatabase.create(context, inMemory = true)

    /** Replaces WorkManager with a synchronous test instance, so enqueued work can be inspected. */
    fun testWorkManager() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    fun session(
        id: String = "sess-1",
        userId: String = "alice",
        joinTimeMs: Long = System.currentTimeMillis() - 1_000,
        lastEventId: String = "0-0",
    ) = SessionState(
        sessionId = id,
        userId = userId,
        role = SessionState.ROLE_MEMBER,
        hostId = "host",
        joinTimeMs = joinTimeMs,
        lastEventId = lastEventId,
        scanCursorMs = joinTimeMs,
        paused = false,
        pausedAtMs = null,
        joinCode = "ABCD2345",
        inviteUrl = "https://photon.example/j/token",
        qrPayload = "",
        expiresAtMs = 0,
    )

    /** Runs a shell command as the shell user and returns its output. */
    fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }
}
