package com.photon.app.net

import com.photon.proto.Error
import com.photon.proto.SessionInfo
import com.photon.proto.TokenPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class FakeTokenStore(access: String?, refresh: String?, expiresAt: Long = 0) : TokenStore {
    @Volatile override var accessToken: String? = access
    @Volatile override var refreshToken: String? = refresh
    @Volatile override var accessExpiresAtMs: Long = expiresAt
    override var userId: String? = "alice"
    private val flow = MutableStateFlow(refresh != null)
    override val signedIn: StateFlow<Boolean> = flow
    private var pending: PendingLogin? = null

    override fun save(pair: TokenPair) {
        accessToken = pair.access_token
        if (pair.refresh_token.isNotEmpty()) refreshToken = pair.refresh_token
        if (pair.user_id.isNotEmpty()) userId = pair.user_id
        accessExpiresAtMs = pair.access_expires_at_ms
        flow.value = true
    }

    override fun clear() {
        accessToken = null
        refreshToken = null
        flow.value = false
    }

    override fun savePendingLogin(verifier: String, state: String) {
        pending = PendingLogin(verifier, state)
    }

    override fun pendingLogin() = pending
    override fun clearPendingLogin() {
        pending = null
    }
}

class TokenRefresherTest {

    @Test
    fun concurrentRefreshesSendOneRequest() = runTest {
        val store = FakeTokenStore("old", "r1")
        val calls = AtomicInteger()
        val refresher = TokenRefresher(store, refreshCall = {
            calls.incrementAndGet()
            delay(50)
            TokenPair(access_token = "new", refresh_token = "r2")
        }, onSignedOut = {})

        val results = withContext(Dispatchers.Default) {
            (1..20).map { async { refresher.refresh("old") } }.awaitAll()
        }
        assertEquals(1, calls.get())
        assertTrue(results.all { it == "new" })
        assertEquals("r2", store.refreshToken)
    }

    @Test
    fun finalRefreshFailureSignsOut() = runTest {
        val store = FakeTokenStore("old", "r1")
        var signedOut = false
        val refresher = TokenRefresher(store, refreshCall = {
            throw ErrorMapper.map(401, Error(code = "UNAUTHENTICATED").encode(), null)
        }, onSignedOut = { signedOut = true })

        assertNull(refresher.refresh("old"))
        assertTrue(signedOut)
        assertNull(store.refreshToken)
        assertFalse(store.signedIn.value)
    }

    @Test
    fun onlyA401FromTheRefreshEndpointSignsOut() {
        assertTrue(SignOutRules.isRefreshRefused(ErrorMapper.map(401, Error(code = "UNAUTHENTICATED").encode(), null)))
        assertTrue(SignOutRules.isRefreshRefused(ErrorMapper.map(401, null, null)))
        for (status in listOf(400, 403, 404, 409, 429, 500, 502, 503)) {
            assertFalse("$status", SignOutRules.isRefreshRefused(ErrorMapper.map(status, Error(code = "X").encode(), null)))
        }
        assertFalse(SignOutRules.isRefreshRefused(IOException("timeout")))
        assertFalse(SignOutRules.isRefreshRefused(IllegalStateException("bad proto")))
    }

    @Test
    fun anyOtherRefreshFailureKeepsTheTokensAndIsReportedAsTransient() = runTest {
        val failures: List<() -> Nothing> = listOf(
            { throw ErrorMapper.map(429, Error(code = "RATE_LIMITED", retryable = true).encode(), null) },
            { throw ErrorMapper.map(500, Error(code = "INTERNAL", retryable = false).encode(), null) },
            { throw ErrorMapper.map(400, Error(code = "INVALID_REQUEST").encode(), null) },
            { throw ErrorMapper.map(404, null, null) },
            { throw IOException("connection reset") },
            { throw IllegalStateException("unparseable body") },
        )
        for (fail in failures) {
            val store = FakeTokenStore("old", "r1")
            var signedOut = false
            val refresher = TokenRefresher(store, refreshCall = { fail() }, onSignedOut = { signedOut = true })
            val thrown = runCatching { refresher.refresh("old") }.exceptionOrNull()
            assertTrue("$thrown", thrown is IOException)
            assertEquals("r1", store.refreshToken)
            assertEquals("old", store.accessToken)
            assertTrue(store.signedIn.value)
            assertFalse(signedOut)
        }
    }

    @Test
    fun anExpiringTokenIsRefreshedBeforeUse() = runTest {
        val now = 1_000_000L
        val store = FakeTokenStore("old", "r1", expiresAt = now + 10_000)
        val refresher = TokenRefresher(
            store,
            refreshCall = { TokenPair(access_token = "new", access_expires_at_ms = now + 900_000) },
            onSignedOut = {},
            clock = { now },
        )
        assertEquals("new", refresher.validAccessToken())
        assertEquals("new", refresher.validAccessToken())
    }
}

/** The interceptor end to end against a fake server: one refresh for many concurrent 401s. */
class AuthInterceptorTest {
    private lateinit var server: MockWebServer
    private val refreshes = AtomicInteger()

    /** What the refresh endpoint answers: 200 with a new pair, or this status. */
    @Volatile private var refreshStatus = 200

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val auth = request.getHeader("Authorization")
                return when {
                    request.path == "/v1/auth/refresh" -> {
                        refreshes.incrementAndGet()
                        Thread.sleep(100)
                        when (refreshStatus) {
                            200 -> proto(TokenPair(access_token = "fresh", refresh_token = "r2", user_id = "alice").encode())
                            401 -> proto(Error(code = "UNAUTHENTICATED").encode(), 401)
                            else -> proto(Error(code = "BUSY", retryable = true).encode(), refreshStatus)
                        }
                    }
                    auth == "Bearer fresh" -> proto(SessionInfo(session_id = "s1").encode())
                    auth == "Bearer upload-token" -> MockResponse().setResponseCode(204)
                    else -> proto(Error(code = "TOKEN_EXPIRED", message = "expired").encode(), 401)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun proto(bytes: ByteArray, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/x-protobuf").setBody(Buffer().write(bytes))

    @Test
    fun manyConcurrent401sCauseOneRefreshAndEveryCallSucceeds() = runBlocking {
        val store = FakeTokenStore("stale", "r1")
        lateinit var api: PhotonApi
        val refresher = TokenRefresher(store, refreshCall = { api.refresh(it) }, onSignedOut = {})
        val auth = OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher()).build()
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(refresher)).build()
        api = PhotonApi(server.url("/"), client, auth)

        val results = (1..8).map { async(Dispatchers.IO) { api.rotateInvites("s1") } }.awaitAll()

        assertTrue(results.all { it.session_id == "s1" })
        assertEquals(1, refreshes.get())
        assertEquals("fresh", store.accessToken)
        assertEquals("r2", store.refreshToken)
    }

    @Test
    fun aFailingRefreshEndpointFailsTheCallButKeepsTheLogin() = runBlocking {
        refreshStatus = 503
        val store = FakeTokenStore("stale", "r1")
        var signedOut = false
        lateinit var api: PhotonApi
        val refresher = TokenRefresher(store, refreshCall = { api.refresh(it) }, onSignedOut = { signedOut = true })
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(refresher)).build()
        api = PhotonApi(server.url("/"), client, OkHttpClient())

        val thrown = runCatching { api.rotateInvites("s1") }.exceptionOrNull()

        assertTrue("$thrown", thrown is IOException)
        assertFalse(signedOut)
        assertEquals("r1", store.refreshToken)
        assertTrue(store.signedIn.value)
    }

    @Test
    fun aRefusedRefreshSignsOut() = runBlocking {
        refreshStatus = 401
        val store = FakeTokenStore("stale", "r1")
        var signedOut = false
        lateinit var api: PhotonApi
        val refresher = TokenRefresher(store, refreshCall = { api.refresh(it) }, onSignedOut = { signedOut = true })
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(refresher)).build()
        api = PhotonApi(server.url("/"), client, OkHttpClient())

        runCatching { api.rotateInvites("s1") }

        assertTrue(signedOut)
        assertNull(store.refreshToken)
    }

    @Test
    fun requestsWithTheirOwnAuthorizationAreLeftAlone() = runBlocking {
        val store = FakeTokenStore("stale", "r1")
        val refresher = TokenRefresher(store, refreshCall = { error("must not refresh") }, onSignedOut = {})
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(refresher)).build()
        val imageApi = ImageApi(server.url("/"), { client }, rewriteLocalUrls = false)
        val url = imageApi.uploadUrl("", "u1")
        imageApi.putChunk(url, "upload-token", 0, ByteArray(10), 0, 10)
        assertEquals(0, refreshes.get())
        assertEquals("Bearer upload-token", server.takeRequest().getHeader("Authorization"))
    }
}
