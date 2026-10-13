package com.photon.app.net

import com.photon.app.core.Clock
import com.photon.proto.TokenPair
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-flight token refresh. The server rotates the refresh token on every use and treats a
 * replay outside a short grace window as theft, so the client must never send two refreshes at
 * once: callers queue on [mutex], and whoever comes second finds a token newer than the one that
 * failed and uses it without refreshing again.
 */
class TokenRefresher(
    private val store: TokenStore,
    private val refreshCall: suspend (refreshToken: String) -> TokenPair,
    private val onSignedOut: () -> Unit,
    private val clock: Clock = Clock.SYSTEM,
    private val skewMs: Long = 30_000,
) {
    private val mutex = Mutex()

    /**
     * Returns an access token newer than [staleAccessToken], refreshing if nobody else has.
     * Null means the user must log in again. Network trouble is rethrown as [java.io.IOException],
     * and a retryable server error as [ApiException], so callers can retry later.
     */
    suspend fun refresh(staleAccessToken: String?): String? = mutex.withLock {
        val current = store.accessToken
        if (current != null && current != staleAccessToken && !isExpiring()) return@withLock current
        val refreshToken = store.refreshToken ?: return@withLock null
        try {
            val pair = refreshCall(refreshToken)
            store.save(pair)
            pair.access_token
        } catch (e: ApiException) {
            if (e.retryable) throw e
            // 401 (revoked family, expired refresh token) or anything else final: log in again.
            store.clear()
            onSignedOut()
            null
        }
    }

    /** The current access token, refreshed first when it is about to expire. */
    suspend fun validAccessToken(): String? {
        val token = store.accessToken
        if (token != null && !isExpiring()) return token
        if (store.refreshToken == null) return token
        return refresh(token)
    }

    private fun isExpiring(): Boolean {
        val exp = store.accessExpiresAtMs
        return exp > 0 && exp - skewMs <= clock.nowMs()
    }
}
