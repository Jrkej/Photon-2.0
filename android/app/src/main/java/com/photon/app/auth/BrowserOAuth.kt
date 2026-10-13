package com.photon.app.auth

import com.photon.app.net.ApiException
import com.photon.app.net.PhotonApi
import com.photon.app.net.TokenStore
import com.photon.proto.OAuthCallbackRequest
import com.photon.proto.OAuthStartRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** The query of a redirect to `photon://auth/cb` or `https://<host>/auth/cb`. */
data class OAuthRedirect(val code: String?, val state: String?, val error: String?) {
    companion object {
        fun parse(uri: String): OAuthRedirect {
            val query = try {
                URI(uri).rawQuery
            } catch (e: URISyntaxException) {
                null
            }.orEmpty()
            val params = query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                decode(key) to decode(value)
            }
            return OAuthRedirect(params["code"]?.ifEmpty { null }, params["state"]?.ifEmpty { null }, params["error"]?.ifEmpty { null })
        }

        private fun decode(s: String): String = URLDecoder.decode(s, "UTF-8")
    }
}

object OAuthRedirects {
    /** Same scheme, host and path as the registered redirect URI (the query is ignored). */
    fun matches(candidate: String, redirectUri: String): Boolean {
        val a = try {
            URI(candidate)
        } catch (e: URISyntaxException) {
            return false
        }
        val b = URI(redirectUri)
        return a.scheme.equals(b.scheme, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            a.path.orEmpty().trimEnd('/') == b.path.orEmpty().trimEnd('/')
    }
}

/**
 * Browser OAuth with PKCE, used for GitHub: `oauth/start`, the provider's page in a Custom Tab,
 * the server's return page forwarding to [redirectUri], then `oauth/callback`. The verifier and
 * state stay in encrypted storage between [start] and [finish], so the login survives the app's
 * process being killed while the browser is open.
 */
class BrowserOAuth(
    private val api: PhotonApi,
    private val tokens: TokenStore,
    private val redirectUri: String,
    /** Debug builds point a localhost authorization URL at API_BASE. */
    private val rewriteAuthorizationUrl: (String) -> String = { it },
) {
    private val mutex = Mutex()
    private var lastHandledState: String? = null

    fun isRedirect(uri: String): Boolean = OAuthRedirects.matches(uri, redirectUri)

    /** Starts a login with [provider]; returns the URL to open in a Custom Tab. */
    suspend fun start(provider: String): String {
        val verifier = Pkce.newVerifier()
        val response = api.oauthStart(
            OAuthStartRequest(provider = provider, redirect_uri = redirectUri, code_challenge = Pkce.challenge(verifier)),
        )
        tokens.savePendingLogin(verifier, response.state)
        return rewriteAuthorizationUrl(response.authorization_url)
    }

    /**
     * Completes a login from the redirect. The server's return page may be followed by Chrome and
     * also tapped by the user ("Return to PHOTON"), so a repeat of a redirect already handled is
     * [SignInOutcome.Ignored].
     */
    suspend fun finish(redirect: OAuthRedirect): SignInOutcome = mutex.withLock {
        if (redirect.state != null && redirect.state == lastHandledState) return@withLock SignInOutcome.Ignored
        val pending = tokens.pendingLogin()

        if (redirect.error != null) {
            if (pending != null && (redirect.state == null || redirect.state == pending.state)) tokens.clearPendingLogin()
            lastHandledState = redirect.state
            return@withLock if (redirect.error == "access_denied") {
                SignInOutcome.Cancelled
            } else {
                SignInOutcome.Failed(SignInProblem.GITHUB_FAILED)
            }
        }
        if (pending == null) {
            // Already completed (or never started here); a stale link must not show an error
            // to someone who is signed in.
            return@withLock if (tokens.signedIn.value) SignInOutcome.Ignored else SignInOutcome.Failed(SignInProblem.LOGIN_EXPIRED)
        }
        val code = redirect.code
        val state = redirect.state
        if (code == null || state == null) return@withLock SignInOutcome.Failed(SignInProblem.GITHUB_FAILED)
        // The server checks the state too; this stops a redirect this login never started. The
        // pending login is kept, so the real redirect can still complete it.
        if (state != pending.state) return@withLock SignInOutcome.Failed(SignInProblem.STATE_MISMATCH)

        lastHandledState = state
        try {
            val pair = api.oauthCallback(
                OAuthCallbackRequest(code = code, state = state, code_verifier = pending.verifier, redirect_uri = redirectUri),
            )
            tokens.save(pair)
            SignInOutcome.SignedIn
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            SignInOutcome.Failed(AuthRules.problemFor(e, SignInMethod.GITHUB))
        } catch (e: IOException) {
            SignInOutcome.Failed(AuthRules.problemFor(e, SignInMethod.GITHUB))
        } finally {
            // The server consumed the state either way; a retry starts a new login.
            tokens.clearPendingLogin()
        }
    }
}

/**
 * Tracks a browser login between opening the Custom Tab and the redirect. When the screen
 * resumes with no redirect, the user closed the tab: the loading state is cleared.
 */
class BrowserLoginTracker {
    var awaitingRedirect: Boolean = false
        private set

    fun launched() {
        awaitingRedirect = true
    }

    /** The redirect intent is delivered (onNewIntent) before the activity resumes. */
    fun redirectArrived() {
        awaitingRedirect = false
    }

    /** True when the user came back without completing the login. */
    fun resumedWithoutRedirect(): Boolean {
        val abandoned = awaitingRedirect
        awaitingRedirect = false
        return abandoned
    }
}
