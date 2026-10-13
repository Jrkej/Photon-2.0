package com.photon.app.events

import com.photon.proto.ClientFrame
import com.photon.proto.Hello
import com.photon.proto.PhotoEvent
import com.squareup.wire.ProtoReader
import okio.Buffer

/**
 * The event's type, or null when the server sent a type this build doesn't know. Wire decodes
 * an unknown proto3 enum value as the zero value (PHOTO_STORED) and keeps the real value in the
 * unknown fields, so a new event type must be spotted there rather than trusted.
 */
fun PhotoEvent.knownType(): PhotoEvent.Type? {
    if (unknownFields.size == 0) return type
    return try {
        val reader = ProtoReader(Buffer().write(unknownFields))
        val token = reader.beginMessage()
        var unknownType = false
        while (true) {
            val tag = reader.nextTag()
            if (tag == -1) break
            if (tag == TYPE_TAG) unknownType = true
            reader.skip()
        }
        reader.endMessageAndGetUnknownFields(token)
        if (unknownType) null else type
    } catch (e: Exception) {
        type
    }
}

private const val TYPE_TAG = 2

fun helloFrame(accessToken: String, sessionId: String, lastEventId: String) =
    ClientFrame(hello = Hello(access_token = accessToken, session_id = sessionId, last_event_id = lastEventId))

fun pingFrame(nowMs: Long) = ClientFrame(ping = nowMs)
