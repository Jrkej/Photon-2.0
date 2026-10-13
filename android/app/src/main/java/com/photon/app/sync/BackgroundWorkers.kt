package com.photon.app.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.photon.app.graph

/**
 * Fires when gallery images change, whether or not the app process is alive (the trigger is
 * held by JobScheduler). It queues eligible photos, enqueues the upload work, tries to bring
 * SyncService back, and re-arms itself, since a content-URI trigger fires only once.
 */
class MediaTriggerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val session = graph.db.sessions().get()
        if (session == null || !graph.tokens.signedIn.value) {
            Log.i(TAG, "media trigger: no active session, not re-arming")
            return Result.success()
        }
        Log.i(TAG, "media trigger fired (${triggeredContentUris.size} uris)")
        try {
            if (!session.paused && graph.scanner.scanSinceCursor() > 0) graph.scheduler.enqueueUploads()
            SyncService.start(applicationContext, "media trigger")
        } finally {
            graph.scheduler.rearmMediaTrigger()
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "PHOTON"
    }
}

/**
 * The safety net: every 15 minutes while a session is active, run the reconcile scan and the
 * upload queue, catch up on events, re-arm the media trigger and try to restart SyncService.
 */
class SafetyNetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val session = graph.db.sessions().get() ?: return Result.success()
        if (!graph.tokens.signedIn.value) return Result.success()
        Log.i(TAG, "safety net run")
        if (!session.paused) graph.scanner.reconcile()
        graph.scheduler.enqueueUploads()
        graph.scheduler.enqueueCatchUp()
        graph.scheduler.ensureBackgroundTriggers()
        SyncService.start(applicationContext, "safety net")
        return Result.success()
    }

    private companion object {
        const val TAG = "PHOTON"
    }
}
