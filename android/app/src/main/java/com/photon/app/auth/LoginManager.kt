package com.photon.app.auth

import android.net.Uri
import com.photon.app.net.PhotonApi
import com.photon.app.net.TokenStore
import com.photon.proto.OAuthCallbackRequest
import com.photon.proto.OAuthStartRequest

class LoginException(message: String) : Exception(message)

/**
 * Backend-mediated OAuth with PKCE (7.3). The verifier and state are kept in encrypted storage
 * between [start] and [finish], and deleted once used.
 */
class LoginManager(
    private val api: PhotonApi,
    private val tokens: TokenStore,
    private val provider: String,
    private val redirectUri: String,
    /** Debug builds point a localhost authorization URL (the dev provider) at API_BASE. */
    private val rewriteAuthorizationUrl: (String) -> String = { it },
) {
    /** Returns the provider URL to open in Custom Tabs. */
    suspend fun start(): Uri {
        val verifier = Pkce.newVerifier()
        val response = api.oauthStart(
            OAuthStartRequest(provider = provider, redirect_uri = redirectUri, code_challenge = Pkce.challenge(verifier)),
        )
        tokens.savePendingLogin(verifier, response.state)
        return Uri.parse(rewriteAuthorizationUrl(response.authorization_url))
    }

    fun isRedirect(uri: Uri): Boolean {
        val expected = Uri.parse(redirectUri)
        return uri.scheme.equals(expected.scheme, ignoreCase = true) &&
            uri.host.equals(expected.host, ignoreCase = true) &&
            uri.path.orEmpty().trimEnd('/') == expected.path.orEmpty().trimEnd('/')
    }

    /** Exchanges the redirect's code. Throws [LoginException] for a redirect we didn't ask for. */
    suspend fun finish(redirect: Uri) {
        redirect.getQueryParameter("error")?.let {
            tokens.clearPendingLogin()
            throw LoginException(it)
        }
        val code = redirect.getQueryParameter("code") ?: throw LoginException("missing code")
        val state = redirect.getQueryParameter("state") ?: throw LoginException("missing state")
        val pending = tokens.pendingLogin() ?: throw LoginException("no login in progress")
        // The server checks the state too; this stops a redirect we never started.
        if (pending.state != state) throw LoginException("state mismatch")
        try {
            val pair = api.oauthCallback(
                OAuthCallbackRequest(code = code, state = state, code_verifier = pending.verifier, redirect_uri = redirectUri),
            )
            tokens.save(pair)
        } finally {
            tokens.clearPendingLogin()
        }
    }
}
