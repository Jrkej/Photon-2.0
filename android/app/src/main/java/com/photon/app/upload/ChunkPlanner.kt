package com.photon.app.upload

import kotlin.math.roundToLong

/**
 * Chunk layout for one upload. The size is chosen once per upload and stored with the row,
 * because the server's list of received chunk indexes only makes sense for that size.
 */
data class ChunkPlan(val size: Long, val chunkSize: Int) {
    init {
        require(size > 0) { "size must be positive" }
        require(chunkSize > 0) { "chunkSize must be positive" }
    }

    val count: Int = ((size + chunkSize - 1) / chunkSize).toInt()

    fun offset(index: Int): Long {
        require(index in 0 until count) { "chunk $index out of 0..<$count" }
        return index.toLong() * chunkSize
    }

    fun length(index: Int): Int = minOf(chunkSize.toLong(), size - offset(index)).toInt()

    /** Indexes still to send, in order. Indexes the server reports outside the plan are ignored. */
    fun missing(received: Collection<Int>): List<Int> {
        val have = received.toHashSet()
        return (0 until count).filter { it !in have }
    }
}

object ChunkPlanner {
    const val MIN_CHUNK = 256 * 1024
    const val MAX_CHUNK = 8 * 1024 * 1024
    const val DEFAULT_CHUNK = 1024 * 1024
    const val ALIGN = 64 * 1024

    /** The image server refuses chunk indexes from 4096 up. */
    const val MAX_CHUNKS = 4096

    /** Aim for chunks that take about this long at the measured throughput. */
    const val TARGET_SECONDS = 2.0

    /**
     * Picks the chunk size for a new upload: about [TARGET_SECONDS] of the measured upload
     * throughput, or the server's hint (then 1 MiB) when nothing has been measured yet, clamped
     * to 256 KiB..8 MiB, aligned to 64 KiB, and large enough to stay under [MAX_CHUNKS] chunks.
     */
    fun chooseChunkSize(size: Long, throughputBytesPerSec: Double?, serverHint: Int): Int {
        val raw: Long = when {
            throughputBytesPerSec != null && throughputBytesPerSec > 0 ->
                (throughputBytesPerSec * TARGET_SECONDS).roundToLong()
            serverHint > 0 -> serverHint.toLong()
            else -> DEFAULT_CHUNK.toLong()
        }
        var chunk = raw.coerceIn(MIN_CHUNK.toLong(), MAX_CHUNK.toLong())
        chunk = (chunk / ALIGN) * ALIGN
        val floorForCount = (size + MAX_CHUNKS - 1) / MAX_CHUNKS
        if (chunk < floorForCount) {
            chunk = ((floorForCount + ALIGN - 1) / ALIGN) * ALIGN
        }
        return chunk.coerceAtLeast(MIN_CHUNK.toLong()).toInt()
    }
}

/**
 * Exponentially weighted upload throughput, fed by every chunk sent. Thread-safe; the
 * persisted value survives restarts through [load] and [snapshot].
 */
class ThroughputEstimator(private val alpha: Double = 0.3) {
    @Volatile
    private var estimate: Double? = null

    val bytesPerSecond: Double? get() = estimate

    @Synchronized
    fun record(bytes: Long, elapsedNanos: Long) {
        if (bytes <= 0 || elapsedNanos <= 0) return
        val sample = bytes / (elapsedNanos / 1e9)
        val prev = estimate
        estimate = if (prev == null) sample else alpha * sample + (1 - alpha) * prev
    }

    @Synchronized
    fun load(value: Double?) {
        estimate = value?.takeIf { it > 0 }
    }

    fun snapshot(): Double? = estimate
}
