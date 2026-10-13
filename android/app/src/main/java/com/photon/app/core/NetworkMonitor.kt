package com.photon.app.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.photon.app.receive.NetQuality
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class NetworkMonitor(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    fun quality(): NetQuality {
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val unmetered = caps != null && (
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                (Build.VERSION.SDK_INT >= 30 && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED))
            )
        return NetQuality(
            connected = connected,
            unmetered = unmetered,
            downstreamKbps = caps?.linkDownstreamBandwidthKbps ?: 0,
            charging = isCharging(),
        )
    }

    private fun isCharging(): Boolean {
        val bm = app.getSystemService(BatteryManager::class.java)
        if (bm != null && bm.isCharging) return true
        val status = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    /** Emits whenever a network becomes available; used to cut reconnect backoff short. */
    fun availability(): Flow<Unit> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }
}
