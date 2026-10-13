package com.photon.app.net

import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val PROTOBUF: MediaType = "application/x-protobuf".toMediaType()

fun Message<*, *>.toProtoBody(): RequestBody = encode().toRequestBody(PROTOBUF)

fun emptyProtoBody(): RequestBody = ByteArray(0).toRequestBody(PROTOBUF)

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { response.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}

/** Turns a non-2xx response into an [ApiException]; the response is consumed either way. */
fun Response.throwIfError() {
    if (isSuccessful) return
    val bytes = try {
        body?.bytes()
    } catch (e: IOException) {
        null
    }
    throw ErrorMapper.map(code, bytes, header("Retry-After"))
}

/** Sends [request] and decodes the 2xx body with [adapter]. */
suspend fun <T : Any> OkHttpClient.callProto(request: Request, adapter: ProtoAdapter<T>): T =
    withContext(Dispatchers.IO) {
        newCall(request).await().use { response ->
            response.throwIfError()
            adapter.decode(response.body?.bytes() ?: ByteArray(0))
        }
    }

/** Sends [request] and ignores a 2xx body. */
suspend fun OkHttpClient.callUnit(request: Request) {
    withContext(Dispatchers.IO) {
        newCall(request).await().use { it.throwIfError() }
    }
}
