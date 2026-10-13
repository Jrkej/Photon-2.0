package com.photon.app.data.prefs

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.photon.app.net.PendingLogin
import com.photon.app.net.TokenStore
import com.photon.proto.TokenPair
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tokens and the in-flight PKCE verifier, encrypted with a key held in the Android Keystore
 * (AndroidX Security). Backups are disabled in the manifest, so nothing here leaves the device.
 *
 * Writes use commit(), not apply(): a rotated refresh token must be on disk before the old one
 * is forgotten, or a crash right after a refresh would replay a dead token and revoke the family.
 */
@SuppressLint("ApplySharedPref")
class EncryptedTokenStore(context: Context) : TokenStore {
    private val prefs: SharedPreferences = open(context.applicationContext)
    private val signedInFlow = MutableStateFlow(prefs.getString(KEY_REFRESH, null) != null)

    override val accessToken: String? get() = prefs.getString(KEY_ACCESS, null)
    override val refreshToken: String? get() = prefs.getString(KEY_REFRESH, null)
    override val accessExpiresAtMs: Long get() = prefs.getLong(KEY_ACCESS_EXP, 0)
    override val userId: String? get() = prefs.getString(KEY_USER, null)
    override val signedIn: StateFlow<Boolean> = signedInFlow.asStateFlow()

    @Synchronized
    override fun save(pair: TokenPair) {
        val editor = prefs.edit()
            .putString(KEY_ACCESS, pair.access_token)
            .putLong(KEY_ACCESS_EXP, pair.access_expires_at_ms)
        // The refresh endpoint may omit the user id; keep the one from login then.
        if (pair.refresh_token.isNotEmpty()) editor.putString(KEY_REFRESH, pair.refresh_token)
        if (pair.user_id.isNotEmpty()) editor.putString(KEY_USER, pair.user_id)
        editor.commit()
        signedInFlow.value = prefs.getString(KEY_REFRESH, null) != null
    }

    @Synchronized
    override fun clear() {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_ACCESS_EXP).remove(KEY_USER).commit()
        signedInFlow.value = false
    }

    override fun savePendingLogin(verifier: String, state: String) {
        prefs.edit().putString(KEY_PKCE_VERIFIER, verifier).putString(KEY_PKCE_STATE, state).commit()
    }

    override fun pendingLogin(): PendingLogin? {
        val verifier = prefs.getString(KEY_PKCE_VERIFIER, null) ?: return null
        val state = prefs.getString(KEY_PKCE_STATE, null) ?: return null
        return PendingLogin(verifier, state)
    }

    override fun clearPendingLogin() {
        prefs.edit().remove(KEY_PKCE_VERIFIER).remove(KEY_PKCE_STATE).commit()
    }

    private companion object {
        const val FILE = "photon_secure"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_ACCESS_EXP = "access_expires_at_ms"
        const val KEY_USER = "user_id"
        const val KEY_PKCE_VERIFIER = "pkce_verifier"
        const val KEY_PKCE_STATE = "pkce_state"

        fun open(context: Context): SharedPreferences = try {
            create(context)
        } catch (e: Exception) {
            // A Keystore key lost to a restore or OS bug makes the file unreadable. Starting over
            // only costs a new login.
            Log.w("PHOTON", "secure prefs unreadable, resetting", e)
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
