package com.photon.app.auth

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.NoCredentialException
import com.photon.app.net.FakeTokenStore
import com.photon.app.net.PhotonApi
import com.photon.proto.AuthConfig
import com.photon.proto.DevLoginRequest
import com.photon.proto.Error
import com.photon.proto.GoogleSignInRequest
import com.photon.proto.NonceResponse
import com.photon.proto.TokenPair
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthRulesTest {

    @Test
    fun devLoginShowsOnlyInDebugBuildsWhenTheServerOffersIt() {
        val on = AuthConfig(dev_login = true)
        val off = AuthConfig(dev_login = false)
        assertTrue(AuthRules.showDevLogin(isDebugBuild = true, config = on))
        assertFalse(AuthRules.showDevLogin(isDebugBuild = true, config = off))
        assertFalse(AuthRules.showDevLogin(isDebugBuild = false, config = on))
        assertFalse(AuthRules.showDevLogin(isDebugBuild = true, config = null)) // config not loaded
    }

    @Test
    fun googleNeedsAClientIdFromTheServer() {
        assertEquals("web-123.apps.googleusercontent.com", AuthRules.googleClientId(AuthConfig(google_client_id = " web-123.apps.googleusercontent.com ")))
        assertNull(AuthRules.googleClientId(AuthConfig(google_client_id = "")))
        assertNull(AuthRules.googleClientId(AuthConfig(google_client_id = "   ")))
        assertNull(AuthRules.googleClientId(null))
    }

    @Test
    fun usernamesMatchWhatTheDevServerAccepts() {
        listOf("alice", "a", "bob_2", "x.y-z", "a".repeat(40), " alice ").forEach { assertTrue(it, AuthRules.isValidUsername(it)) }
        listOf("", " ", "a".repeat(41), "al ice", "alice!", "émile").forEach { assertFalse(it, AuthRules.isValidUsername(it)) }
    }

    @Test
    fun credentialManagerOutcomes() {
        assertEquals(GoogleTokenResult.Cancelled, GoogleIdTokens.classify(GetCredentialCancellationException("closed")))
        assertEquals(GoogleTokenResult.NoAccount, GoogleIdTokens.classify(NoCredentialException("no accounts")))
        assertTrue(GoogleIdTokens.classify(GetCredentialInterruptedException("busy")) is GoogleTokenResult.Failed)
    }

    @Test
    fun bothGoogleIdTokenCredentialTypesAreAccepted() {
        assertTrue(GoogleIdTokens.isGoogleIdToken("com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"))
        assertTrue(GoogleIdTokens.isGoogleIdToken("com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL"))
        assertFalse(GoogleIdTokens.isGoogleIdToken("androidx.credentials.TYPE_PASSWORD_CREDENTIAL"))
    }
}

/** The sign-in calls against a scripted server. */
class AuthServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var tokens: FakeTokenStore
    private lateinit var auth: AuthService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokens = FakeTokenStore(null, null)
        val client = OkHttpClient()
        auth = AuthService(PhotonApi(server.url("/"), client, client), tokens)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun proto(bytes: ByteArray, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/x-protobuf").setBody(Buffer().write(bytes))

    private val pair = TokenPair(access_token = "a1", refresh_token = "r1", user_id = "u-google")

    @Test
    fun configIsRead() = runBlocking {
        server.enqueue(proto(AuthConfig(google_client_id = "web-1", dev_login = true).encode()))
        val config = auth.config()
        assertEquals("web-1", config.google_client_id)
        assertTrue(config.dev_login)
        assertEquals("/v1/auth/config", server.takeRequest().path)
    }

    @Test
    fun googleSignInPostsTheIdTokenWithTheServerNonce() = runBlocking {
        server.enqueue(proto(NonceResponse(nonce = "n-42", expires_at_ms = 1).encode()))
        server.enqueue(proto(pair.encode()))
        var seen: Pair<String, String>? = null

        val outcome = auth.signInWithGoogle("web-1") { clientId, nonce ->
            seen = clientId to nonce
            GoogleTokenResult.Token("id-token-xyz")
        }

        assertEquals(SignInOutcome.SignedIn, outcome)
        assertEquals("web-1" to "n-42", seen)
        assertEquals("/v1/auth/nonce", server.takeRequest().path)
        val post = server.takeRequest()
        assertEquals("/v1/auth/google", post.path)
        val body = GoogleSignInRequest.ADAPTER.decode(post.body.readByteArray())
        assertEquals("id-token-xyz", body.id_token)
        assertEquals("n-42", body.nonce)
        assertEquals("r1", tokens.refreshToken)
        assertEquals("u-google", tokens.userId)
    }

    @Test
    fun cancellingThePickerIsSilentAndSendsNothing() = runBlocking {
        server.enqueue(proto(NonceResponse(nonce = "n").encode()))
        assertEquals(SignInOutcome.Cancelled, auth.signInWithGoogle("web-1") { _, _ -> GoogleTokenResult.Cancelled })
        assertEquals(1, server.requestCount)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun noGoogleAccountIsExplained() = runBlocking {
        server.enqueue(proto(NonceResponse(nonce = "n").encode()))
        val outcome = auth.signInWithGoogle("web-1") { _, _ -> GoogleTokenResult.NoAccount }
        assertEquals(SignInOutcome.Failed(SignInProblem.NO_GOOGLE_ACCOUNT), outcome)
    }

    @Test
    fun serverAnswersBecomeProblems() = runBlocking {
        server.enqueue(proto(NonceResponse(nonce = "n").encode()))
        server.enqueue(proto(Error(code = "UNAUTHENTICATED").encode(), 401))
        assertEquals(SignInOutcome.Failed(SignInProblem.REJECTED), auth.signInWithGoogle("web-1") { _, _ -> GoogleTokenResult.Token("t") })

        server.enqueue(proto(NonceResponse(nonce = "n").encode()))
        server.enqueue(proto(Error(code = "INVALID_REQUEST").encode(), 400))
        assertEquals(SignInOutcome.Failed(SignInProblem.NOT_CONFIGURED), auth.signInWithGoogle("web-1") { _, _ -> GoogleTokenResult.Token("t") })

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(SignInOutcome.Failed(SignInProblem.OFFLINE), auth.signInWithGoogle("web-1") { _, _ -> GoogleTokenResult.Token("t") })
        assertNull(tokens.refreshToken)
    }

    @Test
    fun devLoginSendsTheTrimmedUsername() = runBlocking {
        server.enqueue(proto(pair.encode()))
        assertEquals(SignInOutcome.SignedIn, auth.devLogin(" alice "))
        val req = server.takeRequest()
        assertEquals("/v1/auth/dev/login", req.path)
        assertEquals("alice", DevLoginRequest.ADAPTER.decode(req.body.readByteArray()).username)
        assertEquals("r1", tokens.refreshToken)
    }

    @Test
    fun devLoginProblems() = runBlocking {
        assertEquals(SignInOutcome.Failed(SignInProblem.BAD_USERNAME), auth.devLogin("not ok"))
        assertEquals(0, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(SignInOutcome.Failed(SignInProblem.DEV_LOGIN_OFF), auth.devLogin("alice"))
    }
}
