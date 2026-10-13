package com.photon.app.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Debug helper: the dev backend builds absolute URLs from its own idea of its address
 * (localhost by default), which an emulator or a phone can't reach. Such URLs are pointed at the
 * base URL the app was built with. Release builds never call this.
 */
object LocalUrls {
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1")

    fun rebaseLocalhost(url: String, base: HttpUrl): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (parsed.host !in LOCAL_HOSTS) return url
        return parsed.newBuilder().scheme(base.scheme).host(base.host).port(base.port).build().toString()
    }
}
