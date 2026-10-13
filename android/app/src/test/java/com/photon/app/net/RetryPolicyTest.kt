package com.photon.app.net

import com.photon.app.core.Backoff
import com.photon.proto.Error
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

class RetryPolicyTest {
    private val policy = RetryPolicy(Backoff(baseMs = 1_000, capMs = 60_000, random = Random(1)))

    private fun api(code: String, status: Int, retryable: Boolean, retryAfterS: Int = 0) =
        ErrorMapper.map(status, Error(code = code, retryable = retryable, retry_after_s = retryAfterS).encode(), null)

    @Test
    fun retriesOnlyWhenTheServerSaysSo() {
        assertNull(policy.delayBeforeRetry(api("HASH_MISMATCH", 422, retryable = false), 0))
        assertTrue(policy.delayBeforeRetry(api("BUSY", 503, retryable = true), 0)!! > 0)
    }

    @Test
    fun waitsRetryAfterWhenSet() {
        assertEquals(30_000L, policy.delayBeforeRetry(api("QUOTA_EXCEEDED", 429, retryable = true, retryAfterS = 30), 0))
    }

    @Test
    fun networkErrorsBackOffWithJitterInsideTheCap() {
        for (attempt in 0..10) {
            val d = policy.delayBeforeRetry(IOException("reset"), attempt)!!
            val step = minOf(60_000L, 1_000L shl attempt)
            assertTrue("attempt $attempt gave $d", d in step / 2..step)
        }
    }

    @Test
    fun retryingGivesUpAfterMaxAttempts() = runTest {
        var calls = 0
        val result = runCatching {
            policy.retrying(3) {
                calls++
                throw IOException("down")
            }
        }
        assertEquals(3, calls)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun retryingStopsAtTheFirstFinalError() = runTest {
        var calls = 0
        runCatching {
            policy.retrying(5) {
                calls++
                throw api("TOO_LARGE", 413, retryable = false)
            }
        }
        assertEquals(1, calls)
    }
}
