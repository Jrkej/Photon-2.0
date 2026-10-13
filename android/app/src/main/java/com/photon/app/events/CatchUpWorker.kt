package com.photon.app.events

import android.content.Context
import androidx.work.WorkerParameters
import com.photon.app.graph
import com.photon.app.net.ApiException
import com.photon.app.net.ErrorKind
import com.photon.app.sync.PhotonWorker
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Push hints, counted so a hint that lands while a catch-up runs triggers another pass. */
object PushSignals {
    private val counter = AtomicLong()
    fun bump() = counter.incrementAndGet()
    fun current() = counter.get()
}

/**
 * 8.5: runs on every push hint and on app start. Push is only a hint (it may be dropped,
 * delayed or merged), so this always pulls `/events?since=` (or the snapshot), feeds the
 * receive pipeline, and runs the reconcile scan.
 */
class CatchUpWorker(context: Context, params: WorkerParameters) : PhotonWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        if (graph.db.sessions().get() == null) return Result.success()
        return try {
            graph.pipeline.sweepExpired()
            var passes = 0
            do {
                val seen = PushSignals.current()
                graph.eventSync.catchUp()
                passes++
            } while (PushSignals.current() != seen && passes < MAX_PASSES)
            graph.scheduler.enqueueReconcile()
            graph.scheduler.enqueueUploads()
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        } catch (e: ApiException) {
            val transient = e.retryable || e.kind == ErrorKind.UNAVAILABLE ||
                (e.kind == ErrorKind.AUTH && graph.tokens.signedIn.value)
            if (transient) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_PASSES = 3
    }
}
