package com.photon.app.upload

import java.nio.ByteBuffer
import java.util.Base64

/**
 * CRC-32C (Castagnoli) and the `X-Chunk-CRC32C` header: base64 of the 4-byte big-endian value.
 *
 * Implemented here (slicing-by-8) because Android only has `java.util.zip.CRC32C` from API 34,
 * and the app supports API 26.
 */
object Crc32c {
    const val HEADER = "X-Chunk-CRC32C"

    /** Reflected Castagnoli polynomial. */
    private const val POLY = 0x82F63B78.toInt()

    private val TABLE: Array<IntArray> = Array(8) { IntArray(256) }.also { t ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor POLY else c ushr 1 }
            t[0][i] = c
        }
        for (i in 0 until 256) {
            for (k in 1 until 8) {
                val prev = t[k - 1][i]
                t[k][i] = (prev ushr 8) xor t[0][prev and 0xFF]
            }
        }
    }

    fun value(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "range out of bounds" }
        val t0 = TABLE[0]; val t1 = TABLE[1]; val t2 = TABLE[2]; val t3 = TABLE[3]
        val t4 = TABLE[4]; val t5 = TABLE[5]; val t6 = TABLE[6]; val t7 = TABLE[7]
        var crc = -1
        var i = offset
        val end = offset + length
        while (end - i >= 8) {
            val lo = crc xor ((data[i].toInt() and 0xFF) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                ((data[i + 2].toInt() and 0xFF) shl 16) or
                ((data[i + 3].toInt() and 0xFF) shl 24))
            crc = t7[lo and 0xFF] xor t6[(lo ushr 8) and 0xFF] xor t5[(lo ushr 16) and 0xFF] xor t4[lo ushr 24] xor
                t3[data[i + 4].toInt() and 0xFF] xor t2[data[i + 5].toInt() and 0xFF] xor
                t1[data[i + 6].toInt() and 0xFF] xor t0[data[i + 7].toInt() and 0xFF]
            i += 8
        }
        while (i < end) {
            crc = (crc ushr 8) xor t0[(crc xor data[i].toInt()) and 0xFF]
            i++
        }
        return crc.inv()
    }

    fun header(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): String =
        encode(value(data, offset, length))

    fun encode(crc: Int): String =
        Base64.getEncoder().encodeToString(ByteBuffer.allocate(4).putInt(crc).array())
}
