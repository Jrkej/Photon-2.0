package com.photon.app.upload

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32C
import kotlin.random.Random

class Crc32cTest {

    @Test
    fun checkValueOf123456789() {
        // The standard CRC-32C check value.
        assertEquals(0xE3069283.toInt(), Crc32c.value("123456789".toByteArray()))
    }

    @Test
    fun headerIsBase64OfTheBigEndianValue() {
        assertEquals("4waSgw==", Crc32c.header("123456789".toByteArray()))
        val decoded = Base64.getDecoder().decode(Crc32c.header("123456789".toByteArray()))
        assertEquals(4, decoded.size)
        assertEquals(0xE3069283.toInt(), ByteBuffer.wrap(decoded).int)
    }

    @Test
    fun emptyChunk() {
        assertEquals("AAAAAA==", Crc32c.header(ByteArray(0)))
    }

    @Test
    fun coversOnlyTheGivenRange() {
        val buffer = "xx123456789yyyy".toByteArray()
        assertEquals("4waSgw==", Crc32c.header(buffer, offset = 2, length = 9))
    }

    @Test
    fun highBitValuesAreNotSignExtended() {
        // 32 zero bytes: CRC-32C = 0x8A9136AA, whose top bit is set.
        assertEquals(0x8A9136AA.toInt(), Crc32c.value(ByteArray(32)))
        assertEquals("ipE2qg==", Crc32c.header(ByteArray(32)))
    }

    @Test
    fun matchesTheJdkImplementationOnRandomRanges() {
        val random = Random(42)
        val data = random.nextBytes(70_000)
        repeat(200) {
            val offset = random.nextInt(0, 1_000)
            val length = random.nextInt(0, data.size - offset)
            val jdk = CRC32C().apply { update(data, offset, length) }.value.toInt()
            assertEquals("offset=$offset length=$length", jdk, Crc32c.value(data, offset, length))
        }
    }
}
