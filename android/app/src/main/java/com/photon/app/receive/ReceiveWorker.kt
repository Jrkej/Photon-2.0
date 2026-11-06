package com.photon.app.receive

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.photon.app.AppGraph
import com.photon.app.data.db.ReceiveState
import com.photon.app.e2ee.E2eeException
import com.photon.app.e2ee.MissingEpochKeyException
import com.photon.app.graph
import com.photon.app.net.ApiException
import com.photon.app.net.ErrorCodes
import com.photon.app.net.ErrorKind
import com.photon.app.sync.PhotonWorker
import java.io.IOException

/** Thumbnail at once, preview on a good network, then the original (7.9). One job per photo. */
class ReceiveWorker(context: Context, params: WorkerParameters) : PhotonWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val sha = inputData.getString(KEY_SHA256) ?: return Result.failure()
        val row = graph.db.received().get(sha) ?: return Result.success()
        if (row.state == ReceiveState.SAVED || row.isTerminal) return Result.success()
        if (row.expiresAtMs in 1..graph.clock.nowMs()) {
            graph.pipeline.sweepExpired()
            return Result.success()
        }
        // A photo waiting for its epoch key is resumed by key maintenance (KeySync) once the key is here.
        return runCatchingDownload(graph, row.sessionId, sha, runAttemptCount, resumedAfterKeys = row.thumbPath == null) {
            if (row.thumbPath == null) graph.fetcher.fetchThumb(row)
            val quality = graph.network.quality()
            if (row.previewPath == null && ReceivePolicy.fetchPreview(quality)) graph.fetcher.fetchPreview(row)
            val wifiOnly = graph.settings.originalsWifiOrChargingNow()
            when (ReceivePolicy.original(wifiOnly, quality)) {
                OriginalTiming.NOW -> graph.fetcher.fetchAndSaveOriginal(sha)
                OriginalTiming.DEFERRED -> graph.scheduler.enqueueDeferredOriginal(sha)
            }
        }
    }

    companion object {
        const val KEY_SHA256 = "sha256"
    }
}

/** A deferred original: one variant waits for an unmetered network, the other for the charger. */
class OriginalWorker(context: Context, params: WorkerParameters) : PhotonWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        val sha = inputData.getString(KEY_SHA256) ?: return Result.failure()
        val variant = inputData.getString(KEY_VARIANT) ?: return Result.failure()
        val row = graph.db.received().get(sha) ?: return Result.success()
        val result = runCatchingDownload(graph, row.sessionId, sha, runAttemptCount, resumedAfterKeys = false) {
            graph.fetcher.fetchAndSaveOriginal(sha)
        }
        if (result == Result.success()) {
            val other = if (variant == UNMETERED) CHARGING else UNMETERED
            graph.scheduler.cancelOriginalVariant(sha, other)
        }
        return result
    }

    companion object {
        const val KEY_SHA256 = "sha256"
        const val KEY_VARIANT = "variant"
        const val UNMETERED = "unmetered"
        const val CHARGING = "charging"
    }
}

/**
 * Shared error handling for download jobs, per the error contract and e2ee.md 6.3: a file encrypted for an
 * epoch this phone doesn't hold yet waits for key maintenance (it never fails for that); one that doesn't
 * decrypt, or doesn't match its tag or type, fails and is not saved.
 */
internal suspend fun runCatchingDownload(
    graph: AppGraph,
    sessionId: String,
    sha256: String,
    attempt: Int,
    /** True when key maintenance runs this photo again once the key arrives ([com.photon.app.keys.KeySync]). */
    resumedAfterKeys: Boolean,
    block: suspend () -> Unit,
): ListenableWorker.Result = try {
    block()
    ListenableWorker.Result.success()
} catch (e: MissingEpochKeyException) {
    Log.i("PHOTON", "photo $sha256 waits for the key of epoch ${e.epoch}")
    graph.keySync.maintenanceNeeded()
    if (resumedAfterKeys) ListenableWorker.Result.success() else ListenableWorker.Result.retry()
} catch (e: E2eeException) {
    graph.pipeline.markFailed(sessionId, sha256, e.message ?: "does not decrypt")
    ListenableWorker.Result.success()
} catch (e: IOException) {
    ListenableWorker.Result.retry()
} catch (e: ApiException) {
    when {
        graph.sessions.onSessionError(e) -> ListenableWorker.Result.success()
        e.isCode(ErrorCodes.PHOTO_RECALLED) -> {
            graph.pipeline.markGone(sessionId, sha256, recalled = true)
            ListenableWorker.Result.success()
        }
        e.httpStatus == 410 -> {
            graph.pipeline.markGone(sessionId, sha256, recalled = false)
            ListenableWorker.Result.success()
        }
        // 404 right after the event can be the moment between the orchestrator applying the
        // report and the file's promotion; give it a few tries before dropping the task.
        e.httpStatus == 404 && attempt < 3 -> ListenableWorker.Result.retry()
        e.httpStatus == 404 -> {
            graph.pipeline.markGone(sessionId, sha256, recalled = false)
            ListenableWorker.Result.success()
        }
        // A 401 that survived the interceptor's refresh is transient while still signed in.
        e.kind == ErrorKind.AUTH && graph.tokens.signedIn.value -> ListenableWorker.Result.retry()
        e.retryable -> ListenableWorker.Result.retry()
        else -> ListenableWorker.Result.failure()
    }
}
