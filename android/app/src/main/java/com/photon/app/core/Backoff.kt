package com.photon.app.core

import kotlin.math.min
import kotlin.random.Random

/** Exponential backoff with jitter: half the step is fixed, the other half random. */
class Backoff(
    private val baseMs: Long = 1_000,
    private val capMs: Long = 60_000,
    private val random: Random = Random.Default,
) {
    fun delayMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 30)
        val step = min(capMs, baseMs shl shift).coerceAtLeast(1)
        val half = step / 2
        return half + random.nextLong(0, step - half + 1)
    }
}

fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}
