package com.photon.app.net

import com.photon.app.upload.Crc32c
import com.photon.proto.CompleteRequest
import com.photon.proto.CompleteResponse
import com.photon.proto.Error
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageApiTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var api: ImageApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ImageApi(server.url("/"), { OkHttpClient() }, rewriteLocalUrls = false)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun proto(bytes: ByteArray, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/x-protobuf").setBody(Buffer().write(bytes))

    @Test
    fun uploadUrlComesFromTheAnnounce() {
        assertEquals("https://img.example/v1/uploads/u1", api.uploadUrl("https://img.example/v1/uploads/u1", "u1").toString())
        assertEquals(server.url("/v1/uploads/u2"), api.uploadUrl("", "u2"))
        assertEquals(server.url("/v1/uploads/u3"), api.uploadUrl("/v1/uploads/u3", "u3"))
    }

    @Test
    fun debugBuildsPointALocalhostUploadUrlAtImageBase() {
        val debug = ImageApi("http://10.0.2.2:8081/".toHttpUrl(), { OkHttpClient() }, rewriteLocalUrls = true)
        assertEquals("http://10.0.2.2:8081/v1/uploads/u1", debug.uploadUrl("http://localhost:8081/v1/uploads/u1", "u1").toString())
        assertEquals("https://img.example/v1/uploads/u1", debug.uploadUrl("https://img.example/v1/uploads/u1", "u1").toString())
    }

    @Test
    fun chunkCarriesTheUploadTokenAndCrc32c() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val data = "xx123456789yy".toByteArray()
        api.putChunk(api.uploadUrl("", "u1"), "tok", 3, data, 2, 9)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/v1/uploads/u1/chunks/3", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("4waSgw==", req.getHeader(Crc32c.HEADER))
        assertArrayEquals("123456789".toByteArray(), req.body.readByteArray())
    }

    @Test
    fun completeHandles202Then200() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setHeader("Retry-After", "3"))
        server.enqueue(proto(CompleteResponse(state = "STORED", stored_at_ms = 5).encode()))
        val url = api.uploadUrl("", "u1")

        val first = api.complete(url, "tok", 4)
        assertEquals(CompleteOutcome.Pending(3_000), first)
        val second = api.complete(url, "tok", 4)
        assertTrue(second is CompleteOutcome.Stored)
        assertEquals("STORED", (second as CompleteOutcome.Stored).response.state)

        val req = server.takeRequest()
        assertEquals("/v1/uploads/u1/complete", req.path)
        assertEquals("application/x-protobuf", req.getHeader("Content-Type"))
        assertEquals(4, CompleteRequest.ADAPTER.decode(req.body.readByteArray()).chunk_count)
    }

    @Test
    fun completeListsMissingChunks() = runBlocking {
        server.enqueue(proto(Error(code = "INVALID_REQUEST", missing_chunks = listOf(1, 3)).encode(), 400))
        val e = runCatching { api.complete(api.uploadUrl("", "u1"), "tok", 4) }.exceptionOrNull() as ApiException
        assertEquals(listOf(1, 3), e.missingChunks)
    }

    @Test
    fun completeRejection() = runBlocking {
        server.enqueue(proto(Error(code = "HASH_MISMATCH").encode(), 422))
        val e = runCatching { api.complete(api.uploadUrl("", "u1"), "tok", 1) }.exceptionOrNull() as ApiException
        assertEquals(ErrorKind.REJECTED, e.kind)
    }

    @Test
    fun originalDownloadResumesWithRange() = runBlocking {
        val dest = tmp.root.resolve("orig")
        val part = tmp.root.resolve("orig.part")
        part.writeBytes("hello ".toByteArray())
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "image/jpeg").setBody("world"))

        val result = api.download("s1", "ab".repeat(32), ImageSize.FULL, dest)

        val req = server.takeRequest()
        assertEquals("bytes=6-", req.getHeader("Range"))
        assertEquals("/v1/sessions/s1/photos/${"ab".repeat(32)}?size=full", req.path)
        assertEquals("hello world", dest.readText())
        assertEquals("image/jpeg", result.contentType)
    }

    @Test
    fun recalledDownloadIsAnError() = runBlocking {
        server.enqueue(proto(Error(code = "PHOTO_RECALLED").encode(), 410))
        val e = runCatching { api.download("s1", "cd".repeat(32), ImageSize.THUMB, tmp.root.resolve("t")) }
            .exceptionOrNull() as ApiException
        assertEquals("PHOTO_RECALLED", e.code)
    }
}
