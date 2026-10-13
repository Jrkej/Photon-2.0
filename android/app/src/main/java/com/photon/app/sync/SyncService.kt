package com.photon.app.sync

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.photon.app.R
import com.photon.app.core.Notifications
import com.photon.app.data.db.SessionState
import com.photon.app.graph
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Runs while a session is active (7.5), with a visible notification naming the session and
 * offering Pause and Leave. It holds the gallery observer and the event connection.
 *
 * Foreground service type: specialUse. dataSync is limited to 6 hours a day on Android 15,
 * shorter than a 12-hour session.
 */
class SyncService : LifecycleService() {
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var observer: ContentObserver? = null
    private var current: SessionState? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground(buildNotification(null))
        val graph = applicationContext.graph

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                changes.trySend(Unit)
            }
        }.also {
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, it)
        }

        // The observer only notices: it queues eligible rows and enqueues the upload work.
        // Hashing and the network belong to WorkManager.
        lifecycleScope.launch {
            @OptIn(FlowPreview::class)
            changes.consumeAsFlow().debounce(OBSERVER_DEBOUNCE_MS).collect {
                val s = graph.db.sessions().get() ?: return@collect
                if (s.paused) return@collect
                try {
                    if (graph.scanner.scanSinceCursor() > 0) graph.scheduler.enqueueUploads()
                } catch (e: Exception) {
                    Log.w(TAG, "observer scan failed", e)
                }
            }
        }

        lifecycleScope.launch {
            graph.db.sessions().observe().distinctUntilChanged().collectLatest { s ->
                if (s == null) {
                    stopSelf()
                    return@collectLatest
                }
                current = s
                if (Notifications.canPost(this@SyncService)) {
                    NotificationManagerCompat.from(this@SyncService).notify(Notifications.ID_SYNC, buildNotification(s))
                }
            }
        }

        lifecycleScope.launch {
            while (true) {
                delay(EXPIRY_SWEEP_MS)
                graph.pipeline.sweepExpired()
            }
        }

        graph.connection.acquire(HOLDER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val graph = applicationContext.graph
        when (intent?.action) {
            ACTION_PAUSE -> lifecycleScope.launch { graph.sessions.setPaused(true) }
            ACTION_RESUME -> lifecycleScope.launch { graph.sessions.setPaused(false) }
            ACTION_LEAVE -> graph.appScope.launch {
                try {
                    graph.sessions.leave()
                } catch (e: Exception) {
                    Log.w(TAG, "leave failed", e)
                }
            }
            null -> {
                // Restarted by the system (START_STICKY): catch up on what the observer missed.
                lifecycleScope.launch {
                    if (graph.db.sessions().get() == null) {
                        stopSelf()
                    } else {
                        graph.scheduler.enqueueReconcile()
                        graph.scheduler.enqueueCatchUp()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        applicationContext.graph.connection.release(HOLDER)
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifications.ID_SYNC, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.ID_SYNC, notification)
        }
    }

    private fun buildNotification(s: SessionState?): Notification {
        val paused = s?.paused == true
        val name = s?.joinCode?.ifEmpty { null } ?: s?.sessionId?.take(8) ?: ""
        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_sync_title, name))
            .setContentText(getString(if (paused) R.string.notif_sync_text_paused else R.string.notif_sync_text_on))
            .setContentIntent(Notifications.openAppIntent(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (s != null) {
            builder.addAction(
                0,
                getString(if (paused) R.string.notif_resume else R.string.notif_pause),
                actionIntent(if (paused) ACTION_RESUME else ACTION_PAUSE),
            )
            builder.addAction(0, getString(R.string.notif_leave), actionIntent(ACTION_LEAVE))
        }
        return builder.build()
    }

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, SyncService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "PHOTON"
        private const val HOLDER = "service"
        const val ACTION_PAUSE = "com.photon.app.action.PAUSE"
        const val ACTION_RESUME = "com.photon.app.action.RESUME"
        const val ACTION_LEAVE = "com.photon.app.action.LEAVE"
        private const val OBSERVER_DEBOUNCE_MS = 750L
        private const val EXPIRY_SWEEP_MS = 60_000L

        /**
         * Starts the service. Android 12+ refuses to start a foreground service from the
         * background except in exempt cases (boot, a visible activity, a notification action);
         * then the reconcile scan on the next app start covers the gap.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "cannot start the sync service now", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot start the sync service", e)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }
}
