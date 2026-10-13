package com.photon.app.upload

import android.content.Context
import androidx.work.WorkerParameters
import com.photon.app.graph
import com.photon.app.sync.PhotonWorker

/** Runs the upload queue (7.7). Network-constrained, expedited when allowed, exponential backoff. */
class UploadWorker(context: Context, params: WorkerParameters) : PhotonWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        return when (val result = graph.uploader.runQueue()) {
            QueueResult.Retry -> Result.retry()
            is QueueResult.Done -> {
                result.wakeupAtMs?.let { graph.scheduler.scheduleUploadWakeup(it - graph.clock.nowMs()) }
                Result.success()
            }
        }
    }
}
