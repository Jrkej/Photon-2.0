package com.photon.app.net

import android.util.Log
import com.photon.app.core.Clock
import com.photon.proto.TokenPair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** When the app may forget the user's tokens. */
object SignOutRules {
    /**
     * Only a definitive refusal from `POST /v1/auth/refresh` ends a login: a 401 (expired,
     * revoked or replayed refresh token). Everything else, including 5xx, 4xx other than 401,
     * timeouts and dropped connections, is treated as transient and the tokens are kept.
     */
    fun isRefreshRefused(e: Throwable): Boolean = e is ApiException && e.httpStatus == 401
}

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
     *
     * - A token: use it.
     * - null: the refresh endpoint refused the refresh token (401), or there is none; the tokens
     *   are cleared and the user must log in again.
     * - [RefreshFailedException] (an IOException): anything else. The tokens are kept; try later.
     */
    suspend fun refresh(staleAccessToken: String?): String? = mutex.withLock {
        val current = store.accessToken
        if (current != null && current != staleAccessToken && !isExpiring()) return@withLock current
        val refreshToken = store.refreshToken ?: return@withLock null
        try {
            val pair = refreshCall(refreshToken)
            store.save(pair)
            pair.access_token
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (SignOutRules.isRefreshRefused(e)) {
                Log.w(TAG, "refresh refused by the server (${(e as ApiException).code}); signing out")
                store.clear()
                onSignedOut()
                null
            } else {
                Log.w(TAG, "refresh failed, keeping tokens: ${e.message}")
                throw e as? IOException ?: RefreshFailedException(e)
            }
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

    private companion object {
        const val TAG = "PHOTON"
    }
}
