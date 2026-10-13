package com.photon.app.core

/**
 * Event ids are Redis stream ids, "<ms>-<seq>". They are compared as (ms, seq) number pairs,
 * never as strings: "10-0" is after "9-5".
 */
object EventIds {
    data class StreamId(val ms: Long, val seq: Long) : Comparable<StreamId> {
        override fun compareTo(other: StreamId): Int =
            compareValuesBy(this, other, StreamId::ms, StreamId::seq)
    }

    fun parse(id: String): StreamId? {
        if (id.isEmpty()) return null
        val dash = id.indexOf('-')
        return if (dash < 0) {
            id.toLongOrNull()?.let { StreamId(it, 0) }
        } else {
            val ms = id.substring(0, dash).toLongOrNull() ?: return null
            val seq = id.substring(dash + 1).toLongOrNull() ?: return null
            StreamId(ms, seq)
        }
    }

    /**
     * True when [candidate] should be processed given the highest processed id [cursor].
     * An empty cursor means nothing has been processed yet. An id that can't be parsed is let
     * through: hash dedupe still protects against duplicates.
     */
    fun isAfter(candidate: String, cursor: String): Boolean {
        val c = parse(candidate) ?: return true
        val cur = parse(cursor) ?: return true
        return c > cur
    }

    /** The later of two ids, used to advance a cursor monotonically. */
    fun max(a: String, b: String): String {
        val pa = parse(a) ?: return b
        val pb = parse(b) ?: return a
        return if (pa >= pb) a else b
    }
}
