package com.photon.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.photon.app.graph
import kotlinx.coroutines.launch

/**
 * After a reboot or an app update (which kills the process, service included), a stored session
 * resumes: SyncService, the reconcile scan, catch-up, uploads and the background triggers.
 * Both broadcasts are exempt from the background foreground-service start restriction.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot completed"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            else -> return
        }
        // Null when invoked directly rather than by the system (tests).
        val pending: PendingResult? = goAsync()
        val app = context.applicationContext
        val graph = app.graph
        graph.appScope.launch {
            try {
                val hasSession = graph.db.sessions().get() != null
                val signedIn = graph.tokens.signedIn.value
                Log.i(TAG, "$reason: session=$hasSession signedIn=$signedIn")
                if (hasSession && signedIn) {
                    SyncService.start(app, reason)
                    graph.scheduler.ensureBackgroundTriggers()
                    graph.scheduler.enqueueReconcile()
                    graph.scheduler.enqueueCatchUp()
                    graph.scheduler.enqueueUploads()
                }
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        const val TAG = "PHOTON"
    }
}
