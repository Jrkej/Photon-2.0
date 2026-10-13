package com.photon.app.net

import com.photon.app.upload.Crc32c
import com.photon.proto.CompleteRequest
import com.photon.proto.CompleteResponse
import com.photon.proto.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

enum class ImageSize(val query: String) { THUMB("thumb"), PREVIEW("preview"), FULL("full") }

sealed interface CompleteOutcome {
    data class Stored(val response: CompleteResponse) : CompleteOutcome

    /** 202: still verifying; ask again after [retryAfterMs]. */
    data class Pending(val retryAfterMs: Long) : CompleteOutcome
}

data class DownloadResult(val file: File, val contentType: String?)

/**
 * The image server (IMAGE_BASE): chunked uploads with the upload token, and downloads with the
 * user's access token (added by the client's [AuthInterceptor]). The client is looked up per
 * call because it switches to Cronet once Cronet is ready.
 */
class ImageApi(
    private val base: HttpUrl,
    private val client: () -> OkHttpClient,
    private val rewriteLocalUrls: Boolean,
) {
    /** The upload's base URL: `AnnounceResponse.upload_url`, or IMAGE_BASE/v1/uploads/{id}. */
    fun uploadUrl(uploadUrl: String, uploadId: String): HttpUrl {
        val fallback = base.newBuilder().addPathSegments("v1/uploads").addPathSegment(uploadId).build()
        if (uploadUrl.isBlank()) return fallback
        val absolute = uploadUrl.toHttpUrlOrNull()
        if (absolute != null) {
            if (!rewriteLocalUrls) return absolute
            return LocalUrls.rebaseLocalhost(uploadUrl, base).toHttpUrlOrNull() ?: absolute
        }
        return base.resolve(uploadUrl) ?: fallback
    }

    suspend fun putChunk(upload: HttpUrl, uploadToken: String, index: Int, data: ByteArray, offset: Int, length: Int) {
        val request = Request.Builder()
            .url(upload.newBuilder().addPathSegment("chunks").addPathSegment(index.toString()).build())
            .header(AuthInterceptor.AUTHORIZATION, "Bearer $uploadToken")
            .header(Crc32c.HEADER, Crc32c.header(data, offset, length))
            .put(data.toRequestBody(OCTET, offset, length))
            .build()
        client().callUnit(request)
    }

    suspend fun status(upload: HttpUrl, uploadToken: String): UploadStatus {
        val request = Request.Builder().url(upload)
            .header(AuthInterceptor.AUTHORIZATION, "Bearer $uploadToken")
            .get().build()
        return client().callProto(request, UploadStatus.ADAPTER)
    }

    suspend fun complete(upload: HttpUrl, uploadToken: String, chunkCount: Int): CompleteOutcome =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(upload.newBuilder().addPathSegment("complete").build())
                .header(AuthInterceptor.AUTHORIZATION, "Bearer $uploadToken")
                .post(CompleteRequest(chunk_count = chunkCount).toProtoBody())
                .build()
            client().newCall(request).await().use { response ->
                when {
                    response.code == 202 -> {
                        val seconds = response.header("Retry-After")?.trim()?.toLongOrNull()
                        CompleteOutcome.Pending((seconds ?: DEFAULT_PENDING_S) * 1000)
                    }
                    response.isSuccessful ->
                        CompleteOutcome.Stored(CompleteResponse.ADAPTER.decode(response.body?.bytes() ?: ByteArray(0)))
                    else -> {
                        response.throwIfError()
                        error("unreachable")
                    }
                }
            }
        }

    /**
     * Downloads one size of a photo to [dest]. For the original, a partial `dest.part` from an
     * earlier attempt is resumed with a `Range` request; the server answers 206 or a full 200.
     */
    suspend fun download(sessionId: String, sha256Hex: String, size: ImageSize, dest: File): DownloadResult =
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val part = File(dest.path + ".part")
            val resumeFrom = if (size == ImageSize.FULL && part.exists()) part.length() else 0L
            if (size != ImageSize.FULL) part.delete()

            val url = base.newBuilder()
                .addPathSegments("v1/sessions").addPathSegment(sessionId)
                .addPathSegment("photos").addPathSegment(sha256Hex)
                .addQueryParameter("size", size.query)
                .build()
            val builder = Request.Builder().url(url).get()
            if (resumeFrom > 0) builder.header("Range", "bytes=$resumeFrom-")

            client().newCall(builder.build()).await().use { response ->
                if (response.code == 416) {
                    part.delete()
                    throw IOException("range not satisfiable; restarting download")
                }
                response.throwIfError()
                val append = response.code == 206 && resumeFrom > 0
                val body = response.body ?: throw IOException("empty body")
                FileOutputStream(part, append).use { out -> body.byteStream().copyTo(out, 1 shl 16) }
                if (!part.renameTo(dest)) throw IOException("could not move ${part.name}")
                DownloadResult(dest, response.header("Content-Type"))
            }
        }

    companion object {
        private val OCTET = "application/octet-stream".toMediaType()
        private const val DEFAULT_PENDING_S = 2L
    }
}
