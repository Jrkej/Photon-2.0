package com.photon.app.net

import com.photon.proto.Error
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Adds the user's access token and, on `401 TOKEN_EXPIRED` (or `UNAUTHENTICATED`), refreshes
 * once through the single-flight [TokenRefresher] and retries the request once.
 *
 * This is an application interceptor rather than an `okhttp3.Authenticator` because the image
 * client puts Cronet's interceptor after it, and Cronet short-circuits OkHttp's own follow-up
 * logic, so an Authenticator would never run there.
 *
 * Requests that already carry an Authorization header (uploads with their upload token) pass
 * through untouched.
 */
class AuthInterceptor(private val refresher: TokenRefresher) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(AUTHORIZATION) != null) return chain.proceed(request)

        val token = blocking { refresher.validAccessToken() } ?: return chain.proceed(request)
        val response = chain.proceed(request.withBearer(token))
        if (response.code != 401 || !isRefreshable(response)) return response

        val fresh = blocking { refresher.refresh(token) } ?: return response
        response.close()
        return chain.proceed(request.withBearer(fresh))
    }

    private fun isRefreshable(response: Response): Boolean {
        val code = try {
            Error.ADAPTER.decode(response.peekBody(16 * 1024).bytes()).code
        } catch (e: Exception) {
            ""
        }
        return code == ErrorCodes.TOKEN_EXPIRED || code == ErrorCodes.UNAUTHENTICATED
    }

    /**
     * OkHttp runs interceptors on its own threads, and anything but an IOException thrown there
     * crashes the process, so a retryable refresh error travels as [RefreshFailedException].
     */
    private fun <T> blocking(block: suspend () -> T): T = try {
        runBlocking { block() }
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw RefreshFailedException(e)
    }

    private fun Request.withBearer(token: String): Request =
        newBuilder().header(AUTHORIZATION, "Bearer $token").build()

    companion object {
        const val AUTHORIZATION = "Authorization"
    }
}

/** A token refresh failed for a reason worth retrying later (server busy, rate limited). */
class RefreshFailedException(cause: Throwable) : IOException("token refresh failed: ${cause.message}", cause)
