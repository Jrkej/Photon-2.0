package com.photon.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One row while in a session (implementation plan 7.1). */
@Entity(tableName = "session_state")
data class SessionState(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    val role: String,
    @ColumnInfo(name = "host_id") val hostId: String,
    @ColumnInfo(name = "join_time_ms") val joinTimeMs: Long,
    /** Highest event id fully handled; empty until the first snapshot is applied. */
    @ColumnInfo(name = "last_event_id") val lastEventId: String,
    @ColumnInfo(name = "scan_cursor_ms") val scanCursorMs: Long,
    val paused: Boolean,
    @ColumnInfo(name = "paused_at_ms") val pausedAtMs: Long?,
    @ColumnInfo(name = "join_code") val joinCode: String,
    @ColumnInfo(name = "invite_url") val inviteUrl: String,
    @ColumnInfo(name = "qr_payload") val qrPayload: String,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
) {
    val isHost: Boolean get() = role == ROLE_HOST

    companion object {
        const val ROLE_HOST = "host"
        const val ROLE_MEMBER = "member"
    }
}

@Entity(tableName = "session_members", primaryKeys = ["session_id", "member_id"])
data class SessionMember(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "member_id") val memberId: String,
)

/** A stretch of time with sharing paused; photos captured in it are never shared. */
@Entity(tableName = "pause_windows", indices = [Index("session_id")])
data class PauseWindow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
)

/** The upload queue. */
@Entity(tableName = "pending_photos", indices = [Index("state"), Index("sha256"), Index("photo_tag")])
data class PendingPhoto(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
    val uri: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "taken_at_ms") val takenAtMs: Long,
    val mime: String,
    val state: String,
    /** Lowercase hex of the encrypted file that is uploaded (e2ee.md 6.2): the photo's id on the server. */
    val sha256: String? = null,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0,
    @ColumnInfo(name = "copy_path") val copyPath: String? = null,
    @ColumnInfo(name = "upload_id") val uploadId: String? = null,
    @ColumnInfo(name = "upload_url") val uploadUrl: String? = null,
    @ColumnInfo(name = "chunk_size") val chunkSize: Int = 0,
    val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_ms") val nextAttemptMs: Long = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    /** Hex of the photo tag of the original (e2ee.md 6.2), the same for every member's upload of this photo. */
    @ColumnInfo(name = "photo_tag") val photoTag: String? = null,
    /** SHA-256 (hex) of the original inside the encrypted file: echo layer 4 for photos this device shared. */
    @ColumnInfo(name = "original_sha256") val originalSha256: String? = null,
) {
    val isFinal: Boolean get() = state in UploadState.FINAL
}

object UploadState {
    const val PENDING = "PENDING"
    const val HASHED = "HASHED"
    const val UPLOADING = "UPLOADING"
    const val WAITING = "WAITING"
    const val SYNCED = "SYNCED"
    const val SKIPPED = "SKIPPED"
    const val FAILED = "FAILED"

    val FINAL = setOf(SYNCED, SKIPPED, FAILED)
}

/** Echo layer 1: every gallery row the app created for a received photo. */
@Entity(tableName = "received_assets")
data class ReceivedAsset(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
    val sha256: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

/** Echo layer 4 and receive dedupe. */
@Entity(tableName = "hashes")
data class KnownHash(
    @PrimaryKey val sha256: String,
    val source: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
) {
    companion object {
        const val RECEIVED = "RECEIVED"
        const val UPLOADED = "UPLOADED"
    }
}

/** The receive pipeline's state per photo. */
@Entity(tableName = "received_photos", indices = [Index("session_id")])
data class ReceivedPhoto(
    @PrimaryKey val sha256: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val uploader: String,
    @ColumnInfo(name = "taken_at_ms") val takenAtMs: Long,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val state: String,
    @ColumnInfo(name = "thumb_path") val thumbPath: String? = null,
    @ColumnInfo(name = "preview_path") val previewPath: String? = null,
    /** The gallery row of the saved original, set before it becomes visible. */
    @ColumnInfo(name = "media_id") val mediaId: Long? = null,
    @ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
    /** Hex of the uploader's photo tag from the event, checked against the decrypted original. */
    @ColumnInfo(name = "photo_tag") val photoTag: String? = null,
    /** The epoch whose key encrypted the file, from the event; 0 when unknown (the header says it too). */
    @ColumnInfo(name = "key_epoch", defaultValue = "0") val keyEpoch: Int = 0,
) {
    val isTerminal: Boolean
        get() = state == ReceiveState.RECALLED || state == ReceiveState.EXPIRED || state == ReceiveState.FAILED
}

object ReceiveState {
    const val THUMB = "THUMB"
    const val PREVIEW = "PREVIEW"
    const val SAVED = "SAVED"
    const val RECALLED = "RECALLED"
    const val EXPIRED = "EXPIRED"

    /** Didn't decrypt, isn't the image its metadata names, or its original doesn't match its photo tag. */
    const val FAILED = "FAILED"
}
