package com.photon.app.net

import com.photon.proto.Error
import com.photon.proto.JoinRequest
import com.photon.proto.KeyState
import com.photon.proto.KeyUpload
import com.photon.proto.KeyUploadResult
import com.photon.proto.SealedKey
import com.photon.proto.SessionInfo
import com.photon.proto.SessionRef
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PhotonApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PhotonApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient()
        api = PhotonApi(server.url("/"), client, client)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun proto(bytes: ByteArray, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/x-protobuf").setBody(Buffer().write(bytes))

    @Test
    fun currentSessionReturnsTheLiveSession() = runBlocking {
        server.enqueue(proto(SessionInfo(session_id = "s9", join_code = "ABCD2345", role = "host", host_id = "alice").encode()))
        val info = api.currentSession()
        assertEquals("s9", info?.session_id)
        assertEquals("host", info?.role)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/sessions/current", req.path)
    }

    @Test
    fun currentSessionIsNullWhenTheUserIsInNone() = runBlocking {
        server.enqueue(proto(Error(code = "SESSION_NOT_FOUND", message = "You are not in a session").encode(), 404))
        assertNull(api.currentSession())
    }

    @Test
    fun currentSessionPassesOtherErrorsOn() = runBlocking {
        server.enqueue(proto(Error(code = "BUSY", retryable = true).encode(), 503))
        val e = runCatching { api.currentSession() }.exceptionOrNull() as ApiException
        assertEquals("BUSY", e.code)
    }

    @Test
    fun keysGetsTheKeyState() = runBlocking {
        server.enqueue(proto(KeyState(epoch = 3, rotation_required = true).encode()))
        val state = api.keys("s9")
        assertEquals(3, state.epoch)
        assertEquals(true, state.rotation_required)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/sessions/s9/keys", req.path)
    }

    @Test
    fun uploadKeysPostsTheSealedKeys() = runBlocking {
        server.enqueue(proto(KeyUploadResult(epoch = 2, stored = 1).encode()))
        val box = ByteArray(117) { it.toByte() }.toByteString()
        val result = api.uploadKeys("s9", KeyUpload(epoch = 2, keys = listOf(SealedKey(member_id = "bob", epoch = 2, box = box))))
        assertEquals(1, result.stored)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/sessions/s9/keys", req.path)
        val sent = KeyUpload.ADAPTER.decode(req.body.readByteArray())
        assertEquals(box, sent.keys.single().box)
    }

    @Test
    fun joinCarriesTheMemberKey() = runBlocking {
        server.enqueue(proto(SessionInfo(session_id = "s9").encode()))
        val key = ByteArray(32) { 7 }.toByteString()
        api.join(JoinRequest(code = "ABCD2345", member_key = key))
        val sent = JoinRequest.ADAPTER.decode(server.takeRequest().body.readByteArray())
        assertEquals("ABCD2345", sent.code)
        assertEquals(key, sent.member_key)
    }

    @Test
    fun leaveNamesTheSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        api.leave("s9")
        val req = server.takeRequest()
        assertEquals("/v1/sessions/leave", req.path)
        assertEquals("s9", SessionRef.ADAPTER.decode(req.body.readByteArray()).session_id)
    }
}
