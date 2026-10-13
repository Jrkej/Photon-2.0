package com.photon.app.session

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.photon.app.graph

/** Deletes `hashes` and `received_assets` rows older than 24 hours (7.12). */
class PurgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val before = graph.clock.nowMs() - SessionManager.PURGE_AFTER_MS
        graph.db.hashes().purgeOlderThan(before)
        graph.db.receivedAssets().purgeOlderThan(before)
        return Result.success()
    }
}
