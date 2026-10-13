package com.photon.app.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {
    private val kib = 1024
    private val mib = 1024 * 1024

    @Test
    fun countOffsetsAndLengths() {
        val plan = ChunkPlan(size = 2_500_000, chunkSize = mib)
        assertEquals(3, plan.count)
        assertEquals(0L, plan.offset(0))
        assertEquals(mib.toLong(), plan.offset(1))
        assertEquals(mib, plan.length(0))
        assertEquals(2_500_000 - 2 * mib, plan.length(2))
        assertEquals(2_500_000L, (0 until plan.count).sumOf { plan.length(it).toLong() })
    }

    @Test
    fun exactMultipleHasNoEmptyTail() {
        val plan = ChunkPlan(size = 4L * mib, chunkSize = mib)
        assertEquals(4, plan.count)
        assertEquals(mib, plan.length(3))
    }

    @Test
    fun smallFileIsOneChunk() {
        val plan = ChunkPlan(size = 10, chunkSize = 256 * kib)
        assertEquals(1, plan.count)
        assertEquals(10, plan.length(0))
    }

    @Test
    fun missingSkipsReceivedAndIgnoresStrayIndexes() {
        val plan = ChunkPlan(size = 5L * mib, chunkSize = mib)
        assertEquals(listOf(0, 1, 2, 3, 4), plan.missing(emptyList()))
        assertEquals(listOf(1, 3), plan.missing(listOf(0, 2, 4, 4, 17)))
        assertEquals(emptyList<Int>(), plan.missing(listOf(4, 3, 2, 1, 0)))
    }

    @Test
    fun chunkSizeAimsForTwoSecondsOfThroughput() {
        // 1.5 MiB/s for 2 s = 3 MiB.
        assertEquals(3 * mib, ChunkPlanner.chooseChunkSize(20L * mib, 1.5 * mib, serverHint = mib))
    }

    @Test
    fun chunkSizeIsClampedAndAligned() {
        assertEquals(ChunkPlanner.MIN_CHUNK, ChunkPlanner.chooseChunkSize(20L * mib, 10.0 * kib, serverHint = 0))
        assertEquals(ChunkPlanner.MAX_CHUNK, ChunkPlanner.chooseChunkSize(20L * mib, 100.0 * mib, serverHint = 0))
        val odd = ChunkPlanner.chooseChunkSize(20L * mib, 700_001.0, serverHint = 0)
        assertEquals(0, odd % ChunkPlanner.ALIGN)
        assertTrue(odd in ChunkPlanner.MIN_CHUNK..ChunkPlanner.MAX_CHUNK)
    }

    @Test
    fun withoutMeasurementsTheServerHintThenOneMiBIsUsed() {
        assertEquals(2 * mib, ChunkPlanner.chooseChunkSize(20L * mib, null, serverHint = 2 * mib))
        assertEquals(ChunkPlanner.DEFAULT_CHUNK, ChunkPlanner.chooseChunkSize(20L * mib, null, serverHint = 0))
        assertEquals(ChunkPlanner.MIN_CHUNK, ChunkPlanner.chooseChunkSize(20L * mib, null, serverHint = 1000))
    }

    @Test
    fun hugeFilesStayUnderTheServersChunkIndexLimit() {
        val size = 4L * 1024 * mib
        val chunk = ChunkPlanner.chooseChunkSize(size, 10.0 * kib, serverHint = 0)
        assertTrue(ChunkPlan(size, chunk).count <= ChunkPlanner.MAX_CHUNKS)
    }

    @Test
    fun throughputEstimatorSmooths() {
        val t = ThroughputEstimator(alpha = 0.5)
        assertEquals(null, t.bytesPerSecond)
        t.record(1_000_000, 1_000_000_000) // 1 MB/s
        assertEquals(1_000_000.0, t.bytesPerSecond!!, 0.001)
        t.record(3_000_000, 1_000_000_000) // 3 MB/s
        assertEquals(2_000_000.0, t.bytesPerSecond!!, 0.001)
        t.record(0, 5) // ignored
        assertEquals(2_000_000.0, t.bytesPerSecond!!, 0.001)
    }
}
