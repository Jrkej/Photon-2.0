package com.photon.app.data.prefs

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.photon.app.e2ee.Bundle
import com.photon.app.e2ee.E2ee
import com.photon.app.e2ee.E2eeException
import com.photon.app.e2ee.Hex
import com.photon.app.e2ee.Identity
import com.photon.app.keys.SessionKeyStore

/**
 * The end-to-end secrets (e2ee.md 3), encrypted at rest like the tokens (AES-256-GCM under a Keystore key,
 * AndroidX Security) but in their own file, so signing out keeps the member key and ending a session clears
 * the bundles without touching the tokens. Backups are disabled in the manifest.
 *
 * Writes use commit(): a bundle must be on disk before a photo encrypted with it is uploaded, and a new member
 * key before it is sent to the server.
 */
@SuppressLint("ApplySharedPref")
class EncryptedKeyStore(context: Context) : SessionKeyStore {
    private val app = context.applicationContext
    private val prefs: SharedPreferences by lazy { open(app) }

    /** Decoded bundles by session; the file is the authority, this only saves decrypting on every lookup. */
    private val cache = HashMap<String, Map<Int, Bundle>>()

    @Synchronized
    override fun memberPrivateKey(): ByteArray {
        prefs.getString(KEY_MEMBER, null)?.let { stored ->
            val raw = runCatching { Hex.decode(stored) }.getOrNull()
            if (raw != null && raw.size == E2ee.KEY_LEN) return raw
        }
        val fresh = Identity.newPrivateKey()
        prefs.edit().putString(KEY_MEMBER, Hex.encode(fresh)).commit()
        Log.i(TAG, "made this install's member key")
        return fresh
    }

    @Synchronized
    override fun bundles(sessionId: String): Map<Int, Bundle> =
        cache.getOrPut(sessionId) { decode(prefs.getString(BUNDLES + sessionId, null)) }

    @Synchronized
    override fun addBundles(sessionId: String, bundles: Collection<Bundle>) {
        val held = bundles(sessionId).toMutableMap()
        var changed = false
        for (b in bundles) {
            if (b.epoch !in held) {
                held[b.epoch] = b
                changed = true
            }
        }
        if (!changed) return
        val encoded = held.values.sortedBy { it.epoch }.joinToString("") { Hex.encode(it.encode()) }
        prefs.edit().putString(BUNDLES + sessionId, encoded).commit()
        cache[sessionId] = held
    }

    @Synchronized
    override fun clearSessions() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(BUNDLES) }.forEach { editor.remove(it) }
        editor.commit()
        cache.clear()
    }

    private fun decode(stored: String?): Map<Int, Bundle> {
        if (stored.isNullOrEmpty()) return emptyMap()
        val out = HashMap<Int, Bundle>()
        stored.chunked(E2ee.BUNDLE_LEN * 2).forEach { hex ->
            try {
                val b = Bundle.decode(Hex.decode(hex))
                out[b.epoch] = b
            } catch (e: E2eeException) {
                Log.w(TAG, "dropping an unreadable stored bundle")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "dropping an unreadable stored bundle")
            }
        }
        return out
    }

    private companion object {
        const val TAG = "PHOTON"
        const val FILE = "photon_e2ee"
        const val KEY_MEMBER = "member_private_key"
        const val BUNDLES = "bundles:"

        fun open(context: Context): SharedPreferences = try {
            create(context)
        } catch (e: Exception) {
            // A Keystore key lost to a restore or an OS bug makes the file unreadable. Starting over costs this
            // install its member key: the next maintenance sees the mismatch and joins again with the new one.
            Log.w(TAG, "e2ee prefs unreadable, resetting", e)
            context.deleteSharedPreferences(FILE)
            create(context)
        }

        fun create(context: Context): SharedPreferences {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
