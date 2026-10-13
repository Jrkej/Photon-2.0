package com.photon.app.net

import com.photon.proto.Error
import java.io.IOException

/** What the client should do about an error, per the error contract in Design.md. */
enum class ErrorKind {
    /** 400: fix the request; never blind-retry (unless the server marked it retryable). */
    INVALID,

    /** 401: refresh once, then retry. */
    AUTH,

    /** 403: stop; rejoin only if the session still exists and the user chooses to. */
    FORBIDDEN,

    /** 404: drop the local task. */
    NOT_FOUND,

    /** 409: stop this upload; re-announce only if the photo is still unsynced. */
    CONFLICT,

    /** 410: final, the thing is gone; clear local state. */
    GONE,

    /** 410 RESYNC_REQUIRED: load the snapshot. */
    RESYNC,

    /** 413, 415, 422: the file is refused; stop, do not retry. */
    REJECTED,

    /** 429: retry after Retry-After. */
    LIMITED,

    /** 503 and other server trouble: retry with backoff and jitter. */
    UNAVAILABLE,
}

object ErrorCodes {
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val TOKEN_EXPIRED = "TOKEN_EXPIRED"
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val NOT_A_MEMBER = "NOT_A_MEMBER"
    const val FORBIDDEN = "FORBIDDEN"
    const val UNKNOWN_UPLOAD = "UNKNOWN_UPLOAD"
    const val PHOTO_NOT_FOUND = "PHOTO_NOT_FOUND"
    const val SESSION_NOT_FOUND = "SESSION_NOT_FOUND"
    const val ALREADY_IN_SESSION = "ALREADY_IN_SESSION"
    const val UPLOAD_SUPERSEDED = "UPLOAD_SUPERSEDED"
    const val UPLOAD_FINALIZED = "UPLOAD_FINALIZED"
    const val SESSION_ENDED = "SESSION_ENDED"
    const val PHOTO_EXPIRED = "PHOTO_EXPIRED"
    const val PHOTO_RECALLED = "PHOTO_RECALLED"
    const val UPLOAD_EXPIRED = "UPLOAD_EXPIRED"
    const val RESYNC_REQUIRED = "RESYNC_REQUIRED"
    const val TOO_LARGE = "TOO_LARGE"
    const val UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
    const val HASH_MISMATCH = "HASH_MISMATCH"
    const val INVALID_IMAGE = "INVALID_IMAGE"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val TOO_MANY_UPLOADS = "TOO_MANY_UPLOADS"
    const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
    const val BUSY = "BUSY"
    const val PROCESSING_FAILED = "PROCESSING_FAILED"
}

/**
 * A non-2xx answer from the server, parsed from its `Error` body. Network failures are plain
 * [IOException]s and never become an [ApiException].
 */
class ApiException(
    val httpStatus: Int,
    val code: String,
    val serverMessage: String,
    val retryable: Boolean,
    /** From `retry_after_s`, or the `Retry-After` header when the body has none. */
    val retryAfterMs: Long?,
    val missingChunks: List<Int>,
    val kind: ErrorKind,
) : Exception("HTTP $httpStatus $code${if (serverMessage.isNotEmpty()) ": $serverMessage" else ""}") {

    fun isCode(vararg codes: String) = code in codes

    /** Membership or session errors that end the local session wherever they come from (7.12). */
    val endsSession: Boolean
        get() = code == ErrorCodes.SESSION_ENDED || code == ErrorCodes.NOT_A_MEMBER ||
            code == ErrorCodes.SESSION_NOT_FOUND
}

object ErrorMapper {
    private val KIND_BY_CODE: Map<String, ErrorKind> = mapOf(
        ErrorCodes.INVALID_REQUEST to ErrorKind.INVALID,
        ErrorCodes.TOKEN_EXPIRED to ErrorKind.AUTH,
        ErrorCodes.UNAUTHENTICATED to ErrorKind.AUTH,
        ErrorCodes.NOT_A_MEMBER to ErrorKind.FORBIDDEN,
        ErrorCodes.FORBIDDEN to ErrorKind.FORBIDDEN,
        ErrorCodes.UNKNOWN_UPLOAD to ErrorKind.NOT_FOUND,
        ErrorCodes.PHOTO_NOT_FOUND to ErrorKind.NOT_FOUND,
        ErrorCodes.SESSION_NOT_FOUND to ErrorKind.NOT_FOUND,
        ErrorCodes.ALREADY_IN_SESSION to ErrorKind.CONFLICT,
        ErrorCodes.UPLOAD_SUPERSEDED to ErrorKind.CONFLICT,
        ErrorCodes.UPLOAD_FINALIZED to ErrorKind.CONFLICT,
        ErrorCodes.SESSION_ENDED to ErrorKind.GONE,
        ErrorCodes.PHOTO_EXPIRED to ErrorKind.GONE,
        ErrorCodes.PHOTO_RECALLED to ErrorKind.GONE,
        ErrorCodes.UPLOAD_EXPIRED to ErrorKind.GONE,
        ErrorCodes.RESYNC_REQUIRED to ErrorKind.RESYNC,
        ErrorCodes.TOO_LARGE to ErrorKind.REJECTED,
        ErrorCodes.UNSUPPORTED_FORMAT to ErrorKind.REJECTED,
        ErrorCodes.HASH_MISMATCH to ErrorKind.REJECTED,
        ErrorCodes.INVALID_IMAGE to ErrorKind.REJECTED,
        ErrorCodes.RATE_LIMITED to ErrorKind.LIMITED,
        ErrorCodes.TOO_MANY_UPLOADS to ErrorKind.LIMITED,
        ErrorCodes.QUOTA_EXCEEDED to ErrorKind.LIMITED,
        ErrorCodes.BUSY to ErrorKind.UNAVAILABLE,
        ErrorCodes.PROCESSING_FAILED to ErrorKind.UNAVAILABLE,
    )

    /** Every code in the contract, for tests and diagnostics. */
    val knownCodes: Set<String> get() = KIND_BY_CODE.keys

    fun kindForStatus(status: Int): ErrorKind = when (status) {
        400 -> ErrorKind.INVALID
        401 -> ErrorKind.AUTH
        403 -> ErrorKind.FORBIDDEN
        404 -> ErrorKind.NOT_FOUND
        409 -> ErrorKind.CONFLICT
        410 -> ErrorKind.GONE
        413, 415, 422 -> ErrorKind.REJECTED
        429 -> ErrorKind.LIMITED
        else -> if (status >= 500) ErrorKind.UNAVAILABLE else ErrorKind.INVALID
    }

    fun kindOf(code: String, status: Int): ErrorKind = KIND_BY_CODE[code] ?: kindForStatus(status)

    /**
     * Builds the exception for a non-2xx response. A body that is missing or isn't an `Error`
     * (a proxy page, say) falls back to the status: 408, 429 and 5xx are retryable, the rest not.
     */
    fun map(status: Int, body: ByteArray?, retryAfterHeader: String?): ApiException {
        val parsed: Error? = body?.takeIf { it.isNotEmpty() }?.let {
            try {
                Error.ADAPTER.decode(it)
            } catch (e: IOException) {
                null
            } catch (e: IllegalStateException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }?.takeIf { it.code.isNotEmpty() }

        val headerMs = retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)
        return if (parsed != null) {
            val bodyMs = parsed.retry_after_s.toLong().takeIf { it > 0 }?.times(1000)
            ApiException(
                httpStatus = status,
                code = parsed.code,
                serverMessage = parsed.message,
                retryable = parsed.retryable,
                retryAfterMs = bodyMs ?: headerMs,
                missingChunks = parsed.missing_chunks,
                kind = kindOf(parsed.code, status),
            )
        } else {
            ApiException(
                httpStatus = status,
                code = "HTTP_$status",
                serverMessage = "",
                retryable = status == 408 || status == 429 || status >= 500,
                retryAfterMs = headerMs,
                missingChunks = emptyList(),
                kind = kindForStatus(status),
            )
        }
    }
}
