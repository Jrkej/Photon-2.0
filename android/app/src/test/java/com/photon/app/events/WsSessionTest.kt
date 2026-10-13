package com.photon.app.events

import com.photon.proto.ClientFrame
import com.photon.proto.Error
import com.photon.proto.PhotoEvent
import com.photon.proto.ServerFrame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/** One connection against a scripted server: hello first, frames in order, then the close code. */
class WsSessionTest {
    private lateinit var server: MockWebServer
    private val received = Collections.synchronizedList(mutableListOf<ClientFrame>())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun script(onHello: (WebSocket, ClientFrame) -> Unit) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val frame = ClientFrame.ADAPTER.decode(bytes)
                received += frame
                if (frame.hello != null) onHello(webSocket, frame)
            }
        }))
    }

    private fun ServerFrame.bytes() = encode().toByteString()

    private fun run(pingMs: Long = 30_000, onFrame: suspend (ServerFrame) -> Unit = {}): WsOutcome = runBlocking {
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val url = server.url("/v1/ws").toString().replace("http://", "ws://")
        withTimeout(10_000) {
            WsSession(client, url, helloFrame("tok", "sid", "7-0"), onFrame, pingIntervalMs = pingMs).run()
        }
    }

    @Test
    fun sessionEndedEventIsDeliveredBeforeThe4410Close() {
        script { ws, _ ->
            ws.send(ServerFrame(ready_cursor = "7-0").bytes())
            ws.send(ServerFrame(event = PhotoEvent(event_id = "8-0", type = PhotoEvent.Type.PHOTO_STORED)).bytes())
            ws.send(ServerFrame(event = PhotoEvent(event_id = "9-0", type = PhotoEvent.Type.SESSION_ENDED)).bytes())
            ws.close(4410, "session ended")
        }
        val events = mutableListOf<String>()
        val outcome = run { frame -> frame.event?.let { events += it.event_id } }

        assertEquals(4410, outcome.closeCode)
        assertTrue(outcome.wasReady)
        assertEquals(listOf("8-0", "9-0"), events)
        assertEquals(WsAction.SESSION_ENDED, CloseCodes.actionFor(outcome.closeCode))
        val hello = received.first().hello
        assertNotNull(hello)
        assertEquals("tok", hello!!.access_token)
        assertEquals("7-0", hello.last_event_id)
    }

    @Test
    fun errorFrameThenResyncClose() {
        script { ws, _ ->
            ws.send(ServerFrame(error = Error(code = "RESYNC_REQUIRED")).bytes())
            ws.close(4409, "resync")
        }
        val outcome = run()
        assertEquals(4409, outcome.closeCode)
        assertFalse(outcome.wasReady)
        assertEquals("RESYNC_REQUIRED", outcome.lastErrorCode)
        assertEquals(WsAction.RESYNC_AND_RECONNECT, CloseCodes.actionFor(outcome.closeCode))
    }

    @Test
    fun tokenExpiryCloseAsksForARefresh() {
        script { ws, _ ->
            ws.send(ServerFrame(ready_cursor = "7-0").bytes())
            ws.close(4001, "token expired")
        }
        assertEquals(WsAction.REFRESH_AND_RECONNECT, CloseCodes.actionFor(run().closeCode))
    }

    @Test
    fun pingsAreSentOnTheInterval() {
        script { ws, _ ->
            ws.send(ServerFrame(ready_cursor = "7-0").bytes())
            Thread {
                Thread.sleep(400)
                ws.close(1012, "restart")
            }.start()
        }
        val outcome = run(pingMs = 100)
        assertEquals(1012, outcome.closeCode)
        assertTrue("expected pings, got $received", received.count { it.ping != null } >= 2)
        assertNull(received.first().ping)
    }

    @Test
    fun aDroppedConnectionHasNoCloseCode() {
        server.enqueue(MockResponse().setResponseCode(503))
        val outcome = run()
        assertNull(outcome.closeCode)
        assertNotNull(outcome.failure)
        assertEquals(WsAction.RECONNECT_WITH_BACKOFF, CloseCodes.actionFor(outcome.closeCode))
    }
}
