package com.photon.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.photon.app.graph
import kotlinx.coroutines.launch

/** After a reboot or an app update, a stored session resumes: service, reconcile scan, catch-up. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Null when invoked directly rather than by the system (tests).
        val pending: PendingResult? = goAsync()
        val graph = context.applicationContext.graph
        graph.appScope.launch {
            try {
                if (graph.db.sessions().get() != null && graph.tokens.signedIn.value) {
                    SyncService.start(context.applicationContext)
                    graph.scheduler.enqueueReconcile()
                    graph.scheduler.enqueueCatchUp()
                }
            } finally {
                pending?.finish()
            }
        }
    }
}
