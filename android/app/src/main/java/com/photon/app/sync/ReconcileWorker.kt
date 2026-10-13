package com.photon.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.photon.app.graph

/**
 * The reconcile scan (7.6): covers whatever the observer missed while the app was dead, the
 * service restarting, the device rebooting or sharing paused. Runs on session start, app start,
 * service restart, boot and resume.
 */
class ReconcileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val session = graph.db.sessions().get() ?: return Result.success()
        if (session.paused) return Result.success()
        graph.scanner.reconcile()
        graph.scheduler.enqueueUploads()
        return Result.success()
    }
}
