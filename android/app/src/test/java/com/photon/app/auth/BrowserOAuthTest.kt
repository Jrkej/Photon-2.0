package com.photon.app.auth

import com.photon.app.net.ErrorMapper
import com.photon.app.net.FakeTokenStore
import com.photon.app.net.PhotonApi
import com.photon.proto.AuthConfig
import com.photon.proto.Error
import com.photon.proto.OAuthCallbackRequest
import com.photon.proto.OAuthStartRequest
import com.photon.proto.OAuthStartResponse
import com.photon.proto.TokenPair
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class GitHubRulesTest {

    @Test
    fun gitHubButtonFollowsTheServerConfig() {
        assertTrue(AuthRules.showGitHub(AuthConfig(github_login = true)))
        assertFalse(AuthRules.showGitHub(AuthConfig(github_login = false)))
        assertFalse(AuthRules.showGitHub(AuthConfig(google_client_id = "web-1", dev_login = true)))
        assertFalse(AuthRules.showGitHub(null))
    }

    @Test
    fun callbackErrorsMapToGitHubProblems() {
        val expired = ErrorMapper.map(401, Error(code = "UNAUTHENTICATED").encode(), null)
        val notConfigured = ErrorMapper.map(400, Error(code = "INVALID_REQUEST").encode(), null)
        assertEquals(SignInProblem.LOGIN_EXPIRED, AuthRules.problemFor(expired, SignInMethod.GITHUB))
        assertEquals(SignInProblem.REJECTED, AuthRules.problemFor(expired, SignInMethod.GOOGLE))
        assertEquals(SignInProblem.GITHUB_NOT_CONFIGURED, AuthRules.problemFor(notConfigured, SignInMethod.GITHUB))
        assertEquals(SignInProblem.OFFLINE, AuthRules.problemFor(IOException("down"), SignInMethod.GITHUB))
    }

    @Test
    fun redirectQueryIsParsedAndDecoded() {
        assertEquals(OAuthRedirect("c1", "s+1", null), OAuthRedirect.parse("photon://auth/cb?code=c1&state=s%2B1"))
        assertEquals(OAuthRedirect(null, "s2", "access_denied"), OAuthRedirect.parse("photon://auth/cb?error=access_denied&state=s2"))
        assertEquals(OAuthRedirect(null, null, null), OAuthRedirect.parse("photon://auth/cb"))
        assertEquals(OAuthRedirect(null, null, null), OAuthRedirect.parse("photon://auth/cb?code=&state="))
    }

    @Test
    fun onlyTheRegisteredRedirectIsALoginRedirect() {
        assertTrue(OAuthRedirects.matches("photon://auth/cb?code=1&state=2", "photon://auth/cb"))
        assertTrue(OAuthRedirects.matches("https://Photon.Example/auth/cb/?code=1", "https://photon.example/auth/cb"))
        assertFalse(OAuthRedirects.matches("https://photon.example/j/token", "https://photon.example/auth/cb"))
        assertFalse(OAuthRedirects.matches("https://evil.example/auth/cb?code=1", "https://photon.example/auth/cb"))
        assertFalse(OAuthRedirects.matches("photon://auth/cb", "https://photon.example/auth/cb"))
    }

    @Test
    fun closingTheBrowserWithoutARedirectClearsLoadingOnResume() {
        val t = BrowserLoginTracker()
        assertFalse(t.resumedWithoutRedirect()) // an ordinary resume
        t.launched()
        assertTrue(t.resumedWithoutRedirect()) // back from the tab with nothing
        assertFalse(t.resumedWithoutRedirect())

        t.launched()
        t.redirectArrived() // onNewIntent comes before onResume
        assertFalse(t.resumedWithoutRedirect())
    }
}

/** The browser flow against a scripted server, with the pending login in a fake store. */
class BrowserOAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var tokens: FakeTokenStore
    private lateinit var oauth: BrowserOAuth
    private val redirectUri = "photon://auth/cb"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokens = FakeTokenStore(null, null)
        val client = OkHttpClient()
        oauth = BrowserOAuth(PhotonApi(server.url("/"), client, client), tokens, redirectUri)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun proto(bytes: ByteArray, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/x-protobuf").setBody(Buffer().write(bytes))

    private val pair = TokenPair(access_token = "a1", refresh_token = "r1", user_id = "gh-user")

    private suspend fun started(state: String = "st-1"): String {
        server.enqueue(proto(OAuthStartResponse(authorization_url = "https://github.com/login/oauth/authorize?x=1", state = state).encode()))
        return oauth.start("github")
    }

    @Test
    fun startSendsTheProviderRedirectAndChallengeAndKeepsTheVerifier() = runBlocking {
        val url = started()
        assertEquals("https://github.com/login/oauth/authorize?x=1", url)
        val req = OAuthStartRequest.ADAPTER.decode(server.takeRequest().body.readByteArray())
        assertEquals("github", req.provider)
        assertEquals(redirectUri, req.redirect_uri)
        val pending = tokens.pendingLogin()
        assertNotNull(pending)
        assertEquals("st-1", pending!!.state)
        assertEquals(Pkce.challenge(pending.verifier), req.code_challenge)
    }

    @Test
    fun redirectCompletesTheLogin() = runBlocking {
        started()
        server.takeRequest()
        val verifier = tokens.pendingLogin()!!.verifier
        server.enqueue(proto(pair.encode()))

        val outcome = oauth.finish(OAuthRedirect.parse("photon://auth/cb?code=c-9&state=st-1"))

        assertEquals(SignInOutcome.SignedIn, outcome)
        val cb = OAuthCallbackRequest.ADAPTER.decode(server.takeRequest().body.readByteArray())
        assertEquals("c-9", cb.code)
        assertEquals("st-1", cb.state)
        assertEquals(verifier, cb.code_verifier)
        assertEquals(redirectUri, cb.redirect_uri)
        assertEquals("r1", tokens.refreshToken)
        assertNull(tokens.pendingLogin())
    }

    @Test
    fun accessDeniedIsAQuietCancel() = runBlocking {
        started()
        val outcome = oauth.finish(OAuthRedirect.parse("photon://auth/cb?error=access_denied&state=st-1"))
        assertEquals(SignInOutcome.Cancelled, outcome)
        assertNull(tokens.pendingLogin())
        assertEquals(1, server.requestCount) // no callback
    }

    @Test
    fun otherProviderErrorsAreReported() = runBlocking {
        started()
        val outcome = oauth.finish(OAuthRedirect.parse("photon://auth/cb?error=server_error&state=st-1"))
        assertEquals(SignInOutcome.Failed(SignInProblem.GITHUB_FAILED), outcome)
    }

    @Test
    fun aStateThatDoesNotMatchIsAnErrorAndKeepsThePendingLogin() = runBlocking {
        started()
        val outcome = oauth.finish(OAuthRedirect.parse("photon://auth/cb?code=c&state=someone-else"))
        assertEquals(SignInOutcome.Failed(SignInProblem.STATE_MISMATCH), outcome)
        assertEquals(1, server.requestCount) // no callback sent
        assertEquals("st-1", tokens.pendingLogin()?.state)

        // The real redirect still completes.
        server.enqueue(proto(pair.encode()))
        assertEquals(SignInOutcome.SignedIn, oauth.finish(OAuthRedirect.parse("photon://auth/cb?code=c&state=st-1")))
    }

    @Test
    fun expiredOrReusedStateAsksToTryAgain() = runBlocking {
        started()
        server.enqueue(proto(Error(code = "UNAUTHENTICATED", message = "state already used").encode(), 401))
        val outcome = oauth.finish(OAuthRedirect.parse("photon://auth/cb?code=c&state=st-1"))
        assertEquals(SignInOutcome.Failed(SignInProblem.LOGIN_EXPIRED), outcome)
        assertNull(tokens.pendingLogin())
        assertNull(tokens.refreshToken)
    }

    @Test
    fun theSameRedirectTwiceCallsTheServerOnce() = runBlocking {
        started()
        server.enqueue(proto(pair.encode()))
        val link = OAuthRedirect.parse("photon://auth/cb?code=c&state=st-1")
        assertEquals(SignInOutcome.SignedIn, oauth.finish(link))
        assertEquals(SignInOutcome.Ignored, oauth.finish(link)) // Chrome followed, and the user tapped "Return to PHOTON"
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aRedirectAfterProcessDeathUsesTheStoredVerifier() = runBlocking {
        started()
        server.takeRequest()
        val stored = tokens.pendingLogin()!!
        // A new process: a fresh BrowserOAuth over the same (persisted) store.
        val client = OkHttpClient()
        val afterRestart = BrowserOAuth(PhotonApi(server.url("/"), client, client), tokens, redirectUri)
        server.enqueue(proto(pair.encode()))

        assertEquals(SignInOutcome.SignedIn, afterRestart.finish(OAuthRedirect.parse("photon://auth/cb?code=c&state=st-1")))
        assertEquals(stored.verifier, OAuthCallbackRequest.ADAPTER.decode(server.takeRequest().body.readByteArray()).code_verifier)
    }

    @Test
    fun aRedirectWithNoLoginInProgress() = runBlocking {
        // Not signed in: it can't complete, say so.
        assertEquals(SignInOutcome.Failed(SignInProblem.LOGIN_EXPIRED), oauth.finish(OAuthRedirect.parse("photon://auth/cb?code=c&state=x")))
        // Already signed in (an old link opened again): ignore it.
        tokens.save(pair)
        assertEquals(SignInOutcome.Ignored, oauth.finish(OAuthRedirect.parse("photon://auth/cb?code=c&state=x")))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun missingCodeAndOfflineCallback() = runBlocking {
        started()
        assertEquals(SignInOutcome.Failed(SignInProblem.GITHUB_FAILED), oauth.finish(OAuthRedirect.parse("photon://auth/cb?state=st-1")))

        // The server can't be reached for the callback (same stored login, unreachable API).
        val client = OkHttpClient()
        val offline = BrowserOAuth(PhotonApi("http://127.0.0.1:1/".toHttpUrl(), client, client), tokens, redirectUri)
        assertEquals(SignInOutcome.Failed(SignInProblem.OFFLINE), offline.finish(OAuthRedirect.parse("photon://auth/cb?code=c&state=st-1")))
        assertNull(tokens.refreshToken)
    }
}
