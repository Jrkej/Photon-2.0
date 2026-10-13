package com.photon.app.net

import android.content.Context
import android.util.Log
import com.google.android.gms.net.CronetProviderInstaller
import com.google.android.gms.tasks.Task
import com.google.net.cronet.okhttptransport.CronetInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import java.io.File
import kotlin.coroutines.resume

/**
 * The image-server client (8.4). It starts as plain OkHttp and switches to Cronet once a native
 * Cronet provider is available (from Google Play services), so uploads and downloads can use
 * HTTP/3 when the server advertises it through Alt-Svc; Cronet itself falls back to HTTP/2 over
 * TCP when UDP is blocked. Without Play services, or if Cronet fails to start, the app stays on
 * OkHttp. The orchestrator client never uses Cronet.
 */
class ImageTransport(private val okHttpClient: OkHttpClient) {
    @Volatile
    var client: OkHttpClient = okHttpClient
        private set

    @Volatile
    var name: String = "okhttp"
        private set

    suspend fun tryEnableCronet(context: Context, imageBase: HttpUrl) {
        val engine = buildEngine(context.applicationContext, imageBase) ?: return
        // Cronet's interceptor must be the last application interceptor: it performs the call.
        client = okHttpClient.newBuilder()
            .addInterceptor(CronetInterceptor.newBuilder(engine).build())
            .build()
        name = "cronet"
        Log.i(TAG, "image transport: Cronet ${engine.versionString}")
    }

    private suspend fun buildEngine(context: Context, imageBase: HttpUrl): CronetEngine? =
        withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(INSTALL_TIMEOUT_MS) { CronetProviderInstaller.installProvider(context).awaitQuietly() }
                val provider = CronetProvider.getAllProviders(context).firstOrNull {
                    it.isEnabled && it.name != CronetProvider.PROVIDER_NAME_FALLBACK
                } ?: run {
                    Log.i(TAG, "no native Cronet provider; staying on OkHttp")
                    return@withContext null
                }
                val storage = File(context.cacheDir, "cronet").apply { mkdirs() }
                val builder = provider.createBuilder()
                    .enableHttp2(true)
                    .enableQuic(true)
                    .enableBrotli(true)
                    .setStoragePath(storage.absolutePath)
                    // Keeps Alt-Svc and QUIC state across restarts; photos are never cached
                    // (the server sends no-store anyway).
                    .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, 1024 * 1024)
                if (imageBase.isHttps) builder.addQuicHint(imageBase.host, imageBase.port, imageBase.port)
                builder.build()
            } catch (t: Throwable) {
                Log.w(TAG, "Cronet unavailable; staying on OkHttp", t)
                null
            }
        }

    private suspend fun Task<Void>.awaitQuietly() = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { if (cont.isActive) cont.resume(Unit) }
    }

    private companion object {
        const val TAG = "PHOTON"
        const val INSTALL_TIMEOUT_MS = 10_000L
    }
}
