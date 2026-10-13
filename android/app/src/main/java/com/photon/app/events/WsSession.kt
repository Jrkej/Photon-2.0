package com.photon.app.events

import com.photon.proto.ClientFrame
import com.photon.proto.ServerFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** How one WebSocket connection ended. */
data class WsOutcome(
    /** The server's close code, or null when the connection failed without one. */
    val closeCode: Int?,
    val reason: String,
    /** True once `ready_cursor` arrived, i.e. the server accepted the hello. */
    val wasReady: Boolean,
    val failure: Throwable? = null,
    /** The last `ServerFrame.error`, which the server sends before closing. */
    val lastErrorCode: String? = null,
)

/**
 * One connection to `/v1/ws`: sends `hello` first, pings every 30 seconds, and hands every
 * server frame to [onFrame] in order. Frames received before the close are all delivered before
 * [run] returns, so a SESSION_ENDED event is processed before its 4410 close is acted on.
 */
class WsSession(
    private val client: OkHttpClient,
    private val url: String,
    private val hello: ClientFrame,
    private val onFrame: suspend (ServerFrame) -> Unit,
    private val pingIntervalMs: Long = PING_INTERVAL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): WsOutcome = coroutineScope {
        val frames = Channel<ServerFrame>(Channel.UNLIMITED)
        val closed = CompletableDeferred<WsOutcome>()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(hello.encode().toByteString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val frame = try {
                    ServerFrame.ADAPTER.decode(bytes)
                } catch (e: Exception) {
                    return // a frame we can't parse is skipped; the stream continues
                }
                frames.trySend(frame)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Binary frames only.
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(CloseCodes.NORMAL, null)
                closed.complete(WsOutcome(code, reason, wasReady = false))
                frames.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(WsOutcome(code, reason, wasReady = false))
                frames.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete(WsOutcome(null, t.message.orEmpty(), wasReady = false, failure = t))
                frames.close()
            }
        }

        val ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
        val pinger = launch {
            while (isActive) {
                delay(pingIntervalMs)
                ws.send(ClientFrame(ping = nowMs()).encode().toByteString())
            }
        }
        var ready = false
        var lastError: String? = null
        try {
            for (frame in frames) {
                if (frame.ready_cursor != null) ready = true
                frame.error?.let { lastError = it.code }
                onFrame(frame)
            }
            closed.await().copy(wasReady = ready, lastErrorCode = lastError)
        } finally {
            pinger.cancel()
            ws.close(CloseCodes.NORMAL, null)
        }
    }

    companion object {
        const val PING_INTERVAL_MS = 30_000L
    }
}
