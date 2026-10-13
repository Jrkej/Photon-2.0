package com.photon.app.net

import com.photon.app.core.Backoff
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * The one retry rule every worker uses: retry an [ApiException] only when the server said
 * `retryable`, waiting `retry_after_s` when it is set; retry network failures with backoff.
 */
class RetryPolicy(private val backoff: Backoff = Backoff()) {

    /** Delay before the next attempt, or null when the error must not be retried. */
    fun delayBeforeRetry(error: Throwable, attempt: Int): Long? = when (error) {
        is ApiException -> if (!error.retryable) null else error.retryAfterMs ?: backoff.delayMs(attempt)
        is IOException -> backoff.delayMs(attempt)
        else -> null
    }

    fun backoffMs(attempt: Int): Long = backoff.delayMs(attempt)

    /**
     * Runs [block] up to [maxAttempts] times. The last error, or the first one that isn't
     * retryable, is rethrown.
     */
    suspend fun <T> retrying(maxAttempts: Int, block: suspend (attempt: Int) -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block(attempt)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val wait = delayBeforeRetry(e, attempt)
                if (wait == null || attempt + 1 >= maxAttempts) throw e
                delay(wait)
                attempt++
            }
        }
    }
}
