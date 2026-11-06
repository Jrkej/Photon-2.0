package com.photon.app.sync

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.photon.app.core.Notifications
import com.photon.app.events.CatchUpWorker
import com.photon.app.keys.KeyMaintenanceWorker
import com.photon.app.push.DeviceRegistrationWorker
import com.photon.app.receive.OriginalWorker
import com.photon.app.receive.ReceiveWorker
import com.photon.app.session.PurgeWorker
import com.photon.app.upload.UploadWorker
import java.util.concurrent.TimeUnit

/** Every WorkManager request the app makes, with its unique name. */
class WorkScheduler(context: Context) {
    private val wm = WorkManager.getInstance(context.applicationContext)

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** The upload queue (7.7). KEEP: a running worker drains every due row, including new ones. */
    fun enqueueUploads() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(connected)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_SESSION)
            .build()
        wm.enqueueUniqueWork(UPLOAD_QUEUE, ExistingWorkPolicy.KEEP, request)
    }

    /** Re-runs the queue later, for WAITING rows and rate limits. Serialised with the queue by a mutex. */
    fun scheduleUploadWakeup(delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(connected)
            .setInitialDelay(delayMs.coerceAtLeast(1_000), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_SESSION)
            .build()
        wm.enqueueUniqueWork(UPLOAD_WAKEUP, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelUploads() {
        wm.cancelUniqueWork(UPLOAD_QUEUE)
        wm.cancelUniqueWork(UPLOAD_WAKEUP)
    }

    /**
     * The jobs that keep a session alive when the process is dead or the service was refused:
     * the media trigger and the 15-minute safety net. Both are session work, so they are
     * cancelled when the session ends. KEEP: an armed trigger is left alone.
     */
    fun ensureBackgroundTriggers() {
        wm.enqueueUniqueWork(MEDIA_TRIGGER, ExistingWorkPolicy.KEEP, mediaTriggerRequest())
        val safetyNet = PeriodicWorkRequestBuilder<SafetyNetWorker>(15, TimeUnit.MINUTES)
            .addTag(TAG_SESSION)
            .build()
        wm.enqueueUniquePeriodicWork(SAFETY_NET, ExistingPeriodicWorkPolicy.KEEP, safetyNet)
    }

    /**
     * Content-URI triggers fire once, so the trigger job re-arms itself when it runs. Appending
     * (rather than replacing) lets the running job finish instead of cancelling it.
     */
    fun rearmMediaTrigger() {
        wm.enqueueUniqueWork(MEDIA_TRIGGER, ExistingWorkPolicy.APPEND_OR_REPLACE, mediaTriggerRequest())
    }

    /**
     * Runs when MediaStore images change, even with the process dead (JobScheduler holds the
     * trigger). No network constraint: the scan is local, and the upload work it enqueues has one.
     */
    private fun mediaTriggerRequest(): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(1, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(5, TimeUnit.SECONDS)
            .build()
        return OneTimeWorkRequestBuilder<MediaTriggerWorker>()
            .setConstraints(constraints)
            .addTag(TAG_SESSION)
            .build()
    }

    /** 7.6: session start, app start, service restart, boot, resume from pause. */
    fun enqueueReconcile() {
        val request = OneTimeWorkRequestBuilder<ReconcileWorker>().addTag(TAG_SESSION).build()
        wm.enqueueUniqueWork(RECONCILE, ExistingWorkPolicy.KEEP, request)
    }

    /** 8.5: push hint and app start. */
    fun enqueueCatchUp() {
        val request = OneTimeWorkRequestBuilder<CatchUpWorker>()
            .setConstraints(connected)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_SESSION)
            .build()
        wm.enqueueUniqueWork(CATCH_UP, ExistingWorkPolicy.KEEP, request)
    }

    /** Thumbnail, then preview, then (maybe) the original of one received photo (7.9). */
    fun enqueueReceive(sha256: String) {
        val request = OneTimeWorkRequestBuilder<ReceiveWorker>()
            .setInputData(workDataOf(ReceiveWorker.KEY_SHA256 to sha256))
            .setConstraints(connected)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TAG_SESSION)
            .build()
        wm.enqueueUniqueWork(receiveName(sha256), ExistingWorkPolicy.KEEP, request)
    }

    /**
     * The same, after key maintenance brought the epoch key a photo was waiting for. Appended to a job that is
     * still running (it may be about to give up for want of that key), so the photo always gets another run.
     */
    fun resumeReceive(sha256: String) {
        val request = OneTimeWorkRequestBuilder<ReceiveWorker>()
            .setInputData(workDataOf(ReceiveWorker.KEY_SHA256 to sha256))
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TAG_SESSION)
            .build()
        wm.enqueueUniqueWork(receiveName(sha256), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Key maintenance (e2ee.md 5.3) in the background, after an in-process run failed. */
    fun enqueueKeyMaintenance() {
        val request = OneTimeWorkRequestBuilder<KeyMaintenanceWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_SESSION)
            .build()
        wm.enqueueUniqueWork(KEY_MAINTENANCE, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * "Originals on Wi-Fi or while charging": two works, one needing an unmetered network and one
     * needing the charger. Whichever runs first saves the photo and cancels the other.
     */
    fun enqueueDeferredOriginal(sha256: String) {
        fun request(variant: String, constraints: Constraints): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<OriginalWorker>()
                .setInputData(workDataOf(OriginalWorker.KEY_SHA256 to sha256, OriginalWorker.KEY_VARIANT to variant))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG_SESSION)
                .build()
        val unmetered = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        val charging = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresCharging(true).build()
        wm.enqueueUniqueWork(originalName(sha256, OriginalWorker.UNMETERED), ExistingWorkPolicy.KEEP, request(OriginalWorker.UNMETERED, unmetered))
        wm.enqueueUniqueWork(originalName(sha256, OriginalWorker.CHARGING), ExistingWorkPolicy.KEEP, request(OriginalWorker.CHARGING, charging))
    }

    fun cancelOriginalVariant(sha256: String, variant: String) = wm.cancelUniqueWork(originalName(sha256, variant))

    fun cancelReceive(sha256: String) {
        wm.cancelUniqueWork(receiveName(sha256))
        wm.cancelUniqueWork(originalName(sha256, OriginalWorker.UNMETERED))
        wm.cancelUniqueWork(originalName(sha256, OriginalWorker.CHARGING))
    }

    /** 7.12: every session-scoped job. */
    fun cancelSessionWork() {
        wm.cancelAllWorkByTag(TAG_SESSION)
    }

    /** Deletes `hashes` and `received_assets` 24 hours after a session ends (7.12). */
    fun schedulePurge(delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<PurgeWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniqueWork(PURGE, ExistingWorkPolicy.REPLACE, request)
    }

    /** The same purge at app start, without disturbing a delayed one. */
    fun enqueuePurgeNow() {
        wm.enqueueUniqueWork(PURGE_AT_START, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<PurgeWorker>().build())
    }

    fun enqueueDeviceRegistration() {
        val request = OneTimeWorkRequestBuilder<DeviceRegistrationWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniqueWork(DEVICE_REGISTRATION, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val TAG_SESSION = "photon-session"
        const val UPLOAD_QUEUE = "upload-queue"
        const val MEDIA_TRIGGER = "media-trigger"
        const val SAFETY_NET = "safety-net"
        const val UPLOAD_WAKEUP = "upload-wakeup"
        const val RECONCILE = "reconcile"
        const val CATCH_UP = "catch-up"
        const val KEY_MAINTENANCE = "key-maintenance"
        const val PURGE = "purge"
        const val PURGE_AT_START = "purge-at-start"
        const val DEVICE_REGISTRATION = "device-registration"

        fun receiveName(sha256: String) = "rx-$sha256"
        fun originalName(sha256: String, variant: String) = "orig-$sha256-$variant"
    }
}

/**
 * Base for the app's workers. Expedited work runs as a foreground service on Android 11 and
 * lower, which needs a notification.
 */
abstract class PhotonWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Notifications.ID_WORK, Notifications.work(applicationContext))
}
