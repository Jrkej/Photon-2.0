package com.photon.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.photon.app.sync.BootReceiver
import com.photon.app.sync.WorkScheduler
import com.photon.proto.TokenPair
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 7.13: a reboot during a session runs the reconcile scan (and the catch-up). */
@RunWith(AndroidJUnit4::class)
class BootReconcileTest {
    private val context = TestSupport.context
    private val graph get() = context.graph

    @Before
    fun setUp() = runBlocking {
        TestSupport.testWorkManager()
        graph.tokens.save(TokenPair(access_token = "a", refresh_token = "r", user_id = "alice"))
        graph.db.sessions().clear()
        graph.db.sessions().insert(TestSupport.session())
    }

    @After
    fun tearDown() = runBlocking {
        graph.db.sessions().clear()
        graph.tokens.clear()
        context.stopService(Intent(context, com.photon.app.sync.SyncService::class.java))
    }

    @Test
    fun bootCompletedEnqueuesReconcileAndCatchUp() = runBlocking {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val wm = WorkManager.getInstance(context)
        withTimeout(10_000) {
            while (wm.getWorkInfosForUniqueWork(WorkScheduler.RECONCILE).get().isEmpty()) delay(50)
        }
        val reconcile = wm.getWorkInfosForUniqueWork(WorkScheduler.RECONCILE).get()
        assertTrue(reconcile.isNotEmpty())
        assertTrue(wm.getWorkInfosForUniqueWork(WorkScheduler.CATCH_UP).get().any { it.state != WorkInfo.State.CANCELLED })
    }

    @Test
    fun withoutASessionBootDoesNothing() = runBlocking {
        graph.db.sessions().clear()
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        delay(1_000)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkScheduler.RECONCILE).get().isEmpty())
    }
}
