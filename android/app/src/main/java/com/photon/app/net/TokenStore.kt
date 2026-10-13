package com.photon.app.net

import com.photon.proto.TokenPair
import kotlinx.coroutines.flow.StateFlow

/** Where the access and refresh tokens and the in-flight PKCE verifier live. */
interface TokenStore {
    val accessToken: String?
    val refreshToken: String?
    val accessExpiresAtMs: Long
    val userId: String?

    /** True while a refresh token is held, so the user counts as signed in. */
    val signedIn: StateFlow<Boolean>

    fun save(pair: TokenPair)
    fun clear()

    fun savePendingLogin(verifier: String, state: String)
    fun pendingLogin(): PendingLogin?
    fun clearPendingLogin()
}

data class PendingLogin(val verifier: String, val state: String)
