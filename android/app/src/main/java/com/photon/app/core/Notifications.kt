package com.photon.app.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.photon.app.R
import com.photon.app.ui.MainActivity

object Notifications {
    const val CHANNEL_SYNC = "sync"
    const val CHANNEL_UPLOADS = "uploads"
    const val CHANNEL_WORK = "work"

    const val ID_SYNC = 1
    const val ID_WORK = 2
    private const val ID_UPLOAD_FAILED_BASE = 1000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SYNC, context.getString(R.string.channel_sync), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.channel_sync_desc) },
                NotificationChannel(CHANNEL_UPLOADS, context.getString(R.string.channel_uploads), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_WORK, context.getString(R.string.channel_work), NotificationManager.IMPORTANCE_MIN),
            ),
        )
    }

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Shown by expedited work on Android 11 and lower, where it runs as a foreground service. */
    fun work(context: Context) = NotificationCompat.Builder(context, CHANNEL_WORK)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.notif_transfer))
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setOngoing(true)
        .build()

    fun uploadRejected(context: Context, mediaId: Long, code: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_UPLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_upload_rejected_title))
            .setContentText(context.getString(R.string.notif_upload_rejected_text, code))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_UPLOAD_FAILED_BASE + (mediaId % 1000).toInt(), n)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
