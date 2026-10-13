package com.photon.app.events

import com.photon.proto.ClientFrame
import com.photon.proto.PhotoEvent
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoWriter
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtoExtTest {

    @Test
    fun knownTypesPassThrough() {
        val e = PhotoEvent(event_id = "1-0", type = PhotoEvent.Type.SESSION_ENDED)
        assertEquals(PhotoEvent.Type.SESSION_ENDED, PhotoEvent.ADAPTER.decode(e.encode()).knownType())
        assertEquals(PhotoEvent.Type.PHOTO_STORED, PhotoEvent.ADAPTER.decode(PhotoEvent(event_id = "2-0").encode()).knownType())
    }

    @Test
    fun hostChangedIsAKnownType() {
        val buffer = Buffer()
        val writer = ProtoWriter(buffer)
        ProtoAdapter.STRING.encodeWithTag(writer, 1, "5-0")
        ProtoAdapter.INT32.encodeWithTag(writer, 2, 5) // HOST_CHANGED on the wire
        ProtoAdapter.STRING.encodeWithTag(writer, 9, "bob")
        val decoded = PhotoEvent.ADAPTER.decode(buffer.readByteArray())
        assertEquals(PhotoEvent.Type.HOST_CHANGED, decoded.knownType())
        assertEquals("bob", decoded.member_id)
    }

    @Test
    fun aTypeFromANewerServerIsNotMistakenForPhotoStored() {
        val buffer = Buffer()
        val writer = ProtoWriter(buffer)
        ProtoAdapter.STRING.encodeWithTag(writer, 1, "3-0")
        ProtoAdapter.INT32.encodeWithTag(writer, 2, 42) // no such PhotoEvent.Type
        ProtoAdapter.BYTES.encodeWithTag(writer, 4, ByteArray(32).toByteString())
        val decoded = PhotoEvent.ADAPTER.decode(buffer.readByteArray())

        assertEquals(PhotoEvent.Type.PHOTO_STORED, decoded.type) // what Wire falls back to
        assertNull(decoded.knownType())
    }

    @Test
    fun otherUnknownFieldsDoNotHideTheType() {
        val buffer = Buffer()
        val writer = ProtoWriter(buffer)
        ProtoAdapter.STRING.encodeWithTag(writer, 1, "4-0")
        ProtoAdapter.INT32.encodeWithTag(writer, 2, PhotoEvent.Type.PHOTO_RECALLED.value)
        ProtoAdapter.STRING.encodeWithTag(writer, 99, "future field")
        val decoded = PhotoEvent.ADAPTER.decode(buffer.readByteArray())
        assertEquals(PhotoEvent.Type.PHOTO_RECALLED, decoded.knownType())
    }

    @Test
    fun helloIsTheOnlyFieldOfTheFirstFrame() {
        val frame = ClientFrame.ADAPTER.decode(helloFrame("tok", "sid", "5-0").encode())
        assertEquals("tok", frame.hello?.access_token)
        assertEquals("sid", frame.hello?.session_id)
        assertEquals("5-0", frame.hello?.last_event_id)
        assertNull(frame.ping)
        assertEquals(123L, ClientFrame.ADAPTER.decode(pingFrame(123).encode()).ping)
    }
}
