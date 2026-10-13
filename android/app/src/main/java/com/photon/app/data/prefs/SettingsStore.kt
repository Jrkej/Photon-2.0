package com.photon.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** User settings (7.11) and a few persisted measurements. */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    /** Remove GPS from photos before upload. On by default (Design.md "Privacy controls"). */
    val stripGps: Flow<Boolean> = store.data.map { it[STRIP_GPS] ?: true }

    /** Fetch originals only on Wi-Fi or while charging. Off by default. */
    val originalsWifiOrCharging: Flow<Boolean> = store.data.map { it[ORIGINALS_WIFI] ?: false }

    suspend fun stripGpsNow(): Boolean = stripGps.first()
    suspend fun originalsWifiOrChargingNow(): Boolean = originalsWifiOrCharging.first()

    suspend fun setStripGps(value: Boolean) = store.edit { it[STRIP_GPS] = value }
    suspend fun setOriginalsWifiOrCharging(value: Boolean) = store.edit { it[ORIGINALS_WIFI] = value }

    suspend fun uploadThroughput(): Double? = store.data.first()[THROUGHPUT]
    suspend fun setUploadThroughput(value: Double) = store.edit { it[THROUGHPUT] = value }

    private companion object {
        val STRIP_GPS = booleanPreferencesKey("strip_gps")
        val ORIGINALS_WIFI = booleanPreferencesKey("originals_wifi_or_charging")
        val THROUGHPUT = doublePreferencesKey("upload_throughput_bps")
    }
}
