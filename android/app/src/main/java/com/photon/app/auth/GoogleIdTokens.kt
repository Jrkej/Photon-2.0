package com.photon.app.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * "Sign in with Google" through Credential Manager. The only client id involved is the server's
 * web client id from `/v1/auth/config`; Google matches the app by package name and signing
 * certificate against the Android OAuth client in the same Cloud project.
 */
object GoogleIdTokens {

    // The lint check looks for GoogleIdTokenCredential next to the option; both of its types are
    // handled in fromCredential below.
    @SuppressLint("CredentialManagerSignInWithGoogle")
    suspend fun request(activity: Activity, serverClientId: String, nonce: String): GoogleTokenResult {
        val option = GetSignInWithGoogleOption.Builder(serverClientId).setNonce(nonce).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            fromCredential(CredentialManager.create(activity).getCredential(activity, request).credential)
        } catch (e: GetCredentialException) {
            classify(e)
        }
    }

    fun classify(e: GetCredentialException): GoogleTokenResult = when (e) {
        is GetCredentialCancellationException -> GoogleTokenResult.Cancelled
        is NoCredentialException -> GoogleTokenResult.NoAccount
        else -> {
            Log.w(TAG, "Google sign-in failed: ${e.type}: ${e.message}")
            GoogleTokenResult.Failed("${e.type}: ${e.message.orEmpty()}")
        }
    }

    /** The "Sign in with Google" button flow answers with the SIWG type, the account picker with the plain one. */
    fun isGoogleIdToken(type: String): Boolean =
        type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
            type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL

    private fun fromCredential(credential: Credential): GoogleTokenResult {
        if (credential !is CustomCredential || !isGoogleIdToken(credential.type)) {
            return GoogleTokenResult.Failed("unexpected credential ${credential.type}")
        }
        return try {
            GoogleTokenResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        } catch (e: GoogleIdTokenParsingException) {
            GoogleTokenResult.Failed("unreadable Google credential")
        }
    }

    private const val TAG = "PHOTON"
}
