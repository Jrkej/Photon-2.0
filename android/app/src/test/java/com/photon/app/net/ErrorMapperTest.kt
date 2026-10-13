package com.photon.app.net

import com.photon.proto.Error
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMapperTest {

    private fun body(code: String, retryable: Boolean = false, retryAfterS: Int = 0, missing: List<Int> = emptyList()) =
        Error(code = code, message = "msg", retryable = retryable, retry_after_s = retryAfterS, missing_chunks = missing).encode()

    @Test
    fun parsesTheErrorBody() {
        val e = ErrorMapper.map(429, body("RATE_LIMITED", retryable = true, retryAfterS = 7), retryAfterHeader = null)
        assertEquals(429, e.httpStatus)
        assertEquals("RATE_LIMITED", e.code)
        assertEquals("msg", e.serverMessage)
        assertTrue(e.retryable)
        assertEquals(7_000L, e.retryAfterMs)
        assertEquals(ErrorKind.LIMITED, e.kind)
    }

    @Test
    fun retryAfterHeaderIsUsedWhenTheBodyHasNone() {
        val e = ErrorMapper.map(503, body("BUSY", retryable = true), retryAfterHeader = "5")
        assertEquals(5_000L, e.retryAfterMs)
    }

    @Test
    fun bodyRetryAfterWinsOverTheHeader() {
        val e = ErrorMapper.map(503, body("BUSY", retryable = true, retryAfterS = 3), retryAfterHeader = "9")
        assertEquals(3_000L, e.retryAfterMs)
    }

    @Test
    fun missingChunksAreCarried() {
        val e = ErrorMapper.map(400, body("INVALID_REQUEST", missing = listOf(2, 5, 9)), null)
        assertEquals(listOf(2, 5, 9), e.missingChunks)
        assertEquals(ErrorKind.INVALID, e.kind)
        assertFalse(e.retryable)
    }

    @Test
    fun everyContractCodeMapsToItsKind() {
        val expected = mapOf(
            "INVALID_REQUEST" to ErrorKind.INVALID,
            "TOKEN_EXPIRED" to ErrorKind.AUTH,
            "UNAUTHENTICATED" to ErrorKind.AUTH,
            "NOT_A_MEMBER" to ErrorKind.FORBIDDEN,
            "FORBIDDEN" to ErrorKind.FORBIDDEN,
            "UNKNOWN_UPLOAD" to ErrorKind.NOT_FOUND,
            "PHOTO_NOT_FOUND" to ErrorKind.NOT_FOUND,
            "SESSION_NOT_FOUND" to ErrorKind.NOT_FOUND,
            "ALREADY_IN_SESSION" to ErrorKind.CONFLICT,
            "UPLOAD_SUPERSEDED" to ErrorKind.CONFLICT,
            "UPLOAD_FINALIZED" to ErrorKind.CONFLICT,
            "SESSION_ENDED" to ErrorKind.GONE,
            "PHOTO_EXPIRED" to ErrorKind.GONE,
            "PHOTO_RECALLED" to ErrorKind.GONE,
            "UPLOAD_EXPIRED" to ErrorKind.GONE,
            "RESYNC_REQUIRED" to ErrorKind.RESYNC,
            "TOO_LARGE" to ErrorKind.REJECTED,
            "UNSUPPORTED_FORMAT" to ErrorKind.REJECTED,
            "HASH_MISMATCH" to ErrorKind.REJECTED,
            "INVALID_IMAGE" to ErrorKind.REJECTED,
            "RATE_LIMITED" to ErrorKind.LIMITED,
            "TOO_MANY_UPLOADS" to ErrorKind.LIMITED,
            "QUOTA_EXCEEDED" to ErrorKind.LIMITED,
            "BUSY" to ErrorKind.UNAVAILABLE,
            "PROCESSING_FAILED" to ErrorKind.UNAVAILABLE,
        )
        assertEquals(expected.keys, ErrorMapper.knownCodes)
        expected.forEach { (code, kind) -> assertEquals(code, kind, ErrorMapper.kindOf(code, 418)) }
    }

    @Test
    fun unknownCodeFallsBackToTheStatus() {
        val e = ErrorMapper.map(410, body("SOMETHING_NEW"), null)
        assertEquals("SOMETHING_NEW", e.code)
        assertEquals(ErrorKind.GONE, e.kind)
    }

    @Test
    fun missingOrGarbageBodyFallsBackToTheStatus() {
        val proxy = ErrorMapper.map(502, "<html>Bad gateway</html>".toByteArray(), null)
        assertEquals("HTTP_502", proxy.code)
        assertEquals(ErrorKind.UNAVAILABLE, proxy.kind)
        assertTrue(proxy.retryable)

        val empty = ErrorMapper.map(404, ByteArray(0), null)
        assertEquals(ErrorKind.NOT_FOUND, empty.kind)
        assertFalse(empty.retryable)
        assertNull(empty.retryAfterMs)

        val limited = ErrorMapper.map(429, null, "12")
        assertTrue(limited.retryable)
        assertEquals(12_000L, limited.retryAfterMs)
    }

    @Test
    fun sessionEndingCodes() {
        assertTrue(ErrorMapper.map(410, body("SESSION_ENDED"), null).endsSession)
        assertTrue(ErrorMapper.map(403, body("NOT_A_MEMBER"), null).endsSession)
        assertFalse(ErrorMapper.map(410, body("PHOTO_RECALLED"), null).endsSession)
        assertFalse(ErrorMapper.map(403, body("FORBIDDEN"), null).endsSession)
    }
}
