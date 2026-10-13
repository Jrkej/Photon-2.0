package com.photon.app.net

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** The OkHttp clients. One connection pool is shared; dispatchers are not (see [auth]). */
class HttpClients(refresher: () -> TokenRefresher) {
    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val authInterceptor by lazy { AuthInterceptor(refresher()) }

    /** Auth endpoints: no token, and a dispatcher of its own so a refresh never waits behind the calls blocked on it. */
    val auth: OkHttpClient by lazy { base.newBuilder().dispatcher(Dispatcher()).build() }

    /** Orchestrator API with the access token. */
    val api: OkHttpClient by lazy { base.newBuilder().addInterceptor(authInterceptor).build() }

    /** WebSocket: the token goes in the first frame, never in a header or the URL. */
    val webSocket: OkHttpClient by lazy {
        base.newBuilder().dispatcher(Dispatcher()).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    /** Image server over OkHttp; [ImageTransport] layers Cronet on top when available. */
    val image: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply { maxRequestsPerHost = 16 }
        base.newBuilder()
            .dispatcher(dispatcher)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .build()
    }
}
