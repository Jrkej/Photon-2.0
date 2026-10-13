package com.photon.app.auth

import com.photon.app.net.ApiException
import com.photon.app.net.ErrorKind
import com.photon.app.net.PhotonApi
import com.photon.app.net.TokenStore
import com.photon.proto.AuthConfig
import com.photon.proto.DevLoginRequest
import com.photon.proto.GoogleSignInRequest
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** What the platform account picker gave back. */
sealed interface GoogleTokenResult {
    data class Token(val idToken: String) : GoogleTokenResult

    /** The user closed the picker: say nothing. */
    data object Cancelled : GoogleTokenResult

    /** No Google account on the phone. */
    data object NoAccount : GoogleTokenResult

    data class Failed(val detail: String) : GoogleTokenResult
}

enum class SignInMethod { GOOGLE, GITHUB, DEV }

enum class SignInProblem {
    NO_GOOGLE_ACCOUNT,
    GOOGLE_FAILED,
    /** The server refused the ID token or the nonce (401). */
    REJECTED,
    /** Google sign-in isn't configured on the server (400). */
    NOT_CONFIGURED,
    GITHUB_FAILED,
    GITHUB_NOT_CONFIGURED,
    /** The browser login's state was already used or has expired (401 from the callback). */
    LOGIN_EXPIRED,
    /** A redirect that doesn't belong to the login in progress. */
    STATE_MISMATCH,
    NO_BROWSER,
    DEV_LOGIN_OFF,
    BAD_USERNAME,
    RATE_LIMITED,
    OFFLINE,
    SERVER,
}

sealed interface SignInOutcome {
    data object SignedIn : SignInOutcome
    data object Cancelled : SignInOutcome

    /** A repeat of a redirect already handled: change nothing. */
    data object Ignored : SignInOutcome
    data class Failed(val problem: SignInProblem) : SignInOutcome
}

object AuthRules {
    private val USERNAME = Regex("[A-Za-z0-9_.-]{1,40}")

    /** The server's web client id, or null when Google sign-in is off there. */
    fun googleClientId(config: AuthConfig?): String? = config?.google_client_id?.trim()?.takeIf { it.isNotEmpty() }

    /** GitHub (browser flow) when the server has a GitHub OAuth App configured. */
    fun showGitHub(config: AuthConfig?): Boolean = config?.github_login == true

    /** Developer sign-in: debug builds only, and only when the server offers it. */
    fun showDevLogin(isDebugBuild: Boolean, config: AuthConfig?): Boolean = isDebugBuild && config?.dev_login == true

    /** What the dev server accepts (and what its own login page allowed). */
    fun isValidUsername(username: String): Boolean = USERNAME.matches(username.trim())

    fun problemFor(e: Throwable, method: SignInMethod): SignInProblem = when {
        e is ApiException && e.httpStatus == 401 ->
            if (method == SignInMethod.GITHUB) SignInProblem.LOGIN_EXPIRED else SignInProblem.REJECTED
        e is ApiException && e.httpStatus == 404 && method == SignInMethod.DEV -> SignInProblem.DEV_LOGIN_OFF
        e is ApiException && e.httpStatus == 400 -> when (method) {
            SignInMethod.DEV -> SignInProblem.BAD_USERNAME
            SignInMethod.GOOGLE -> SignInProblem.NOT_CONFIGURED
            SignInMethod.GITHUB -> SignInProblem.GITHUB_NOT_CONFIGURED
        }
        e is ApiException && e.kind == ErrorKind.LIMITED -> SignInProblem.RATE_LIMITED
        e is IOException -> SignInProblem.OFFLINE
        else -> SignInProblem.SERVER
    }
}

/**
 * Sign-in without a browser: Google through the platform account picker (Credential Manager)
 * with a server nonce, or, on development servers, a bare username.
 */
class AuthService(private val api: PhotonApi, private val tokens: TokenStore) {

    suspend fun config(): AuthConfig = api.authConfig()

    /**
     * Gets a nonce, asks [idToken] for a Google ID token carrying it (for the server's web
     * client id [clientId]), and exchanges it for a token pair.
     */
    suspend fun signInWithGoogle(
        clientId: String,
        idToken: suspend (clientId: String, nonce: String) -> GoogleTokenResult,
    ): SignInOutcome = guard(SignInMethod.GOOGLE) {
        val nonce = api.authNonce().nonce
        when (val result = idToken(clientId, nonce)) {
            is GoogleTokenResult.Token -> {
                tokens.save(api.googleSignIn(GoogleSignInRequest(id_token = result.idToken, nonce = nonce)))
                SignInOutcome.SignedIn
            }
            GoogleTokenResult.Cancelled -> SignInOutcome.Cancelled
            GoogleTokenResult.NoAccount -> SignInOutcome.Failed(SignInProblem.NO_GOOGLE_ACCOUNT)
            is GoogleTokenResult.Failed -> SignInOutcome.Failed(SignInProblem.GOOGLE_FAILED)
        }
    }

    suspend fun devLogin(username: String): SignInOutcome {
        if (!AuthRules.isValidUsername(username)) return SignInOutcome.Failed(SignInProblem.BAD_USERNAME)
        return guard(SignInMethod.DEV) {
            tokens.save(api.devLogin(DevLoginRequest(username = username.trim())))
            SignInOutcome.SignedIn
        }
    }

    private suspend fun guard(method: SignInMethod, block: suspend () -> SignInOutcome): SignInOutcome = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        SignInOutcome.Failed(AuthRules.problemFor(e, method))
    } catch (e: IOException) {
        SignInOutcome.Failed(AuthRules.problemFor(e, method))
    }
}
