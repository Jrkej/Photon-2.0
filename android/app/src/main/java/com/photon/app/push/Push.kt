package com.photon.app.push

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.photon.app.events.PushSignals
import com.photon.app.graph
import com.photon.app.net.ApiException
import com.photon.app.net.PhotonApi
import com.photon.app.net.TokenStore
import com.photon.proto.DeviceRegistration
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * FCM is optional. Without google-services.json there is no default FirebaseApp, nothing is
 * registered, and the app relies on catch-up at start and on reconnect (push is only a hint).
 */
class PushRegistrar(
    private val context: Context,
    private val api: PhotonApi,
    private val tokens: TokenStore,
) {
    fun isAvailable(): Boolean = try {
        FirebaseApp.getApps(context).isNotEmpty()
    } catch (t: Throwable) {
        false
    }

    /** `PUT /v1/devices/current` with this device's FCM token. */
    suspend fun register() {
        if (!isAvailable() || tokens.accessToken == null) return
        val token = fcmToken() ?: return
        api.registerDevice(DeviceRegistration(platform = "android", push_token = token))
    }

    private suspend fun fcmToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!cont.isActive) return@addOnCompleteListener
            if (task.isSuccessful) {
                cont.resume(task.result)
            } else {
                cont.resumeWithException(IOException("FCM token unavailable", task.exception))
            }
        }
    }
}

class DeviceRegistrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        applicationContext.graph.push.register()
        Result.success()
    } catch (e: IOException) {
        Result.retry()
    } catch (e: ApiException) {
        if (e.retryable) Result.retry() else Result.failure()
    }
}

/**
 * A push is a hint that new events exist; it never carries photo data. Every message runs the
 * same catch-up as app start.
 */
class PhotonMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        Log.d("PHOTON", "push hint: ${message.data["kind"]}")
        PushSignals.bump()
        applicationContext.graph.scheduler.enqueueCatchUp()
    }

    override fun onNewToken(token: String) {
        applicationContext.graph.scheduler.enqueueDeviceRegistration()
    }
}
