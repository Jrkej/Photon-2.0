package com.photon.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM session_state LIMIT 1")
    fun observe(): Flow<SessionState?>

    @Query("SELECT * FROM session_state LIMIT 1")
    suspend fun get(): SessionState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: SessionState)

    @Query("DELETE FROM session_state")
    suspend fun clear()

    @Query("UPDATE session_state SET last_event_id = :eventId WHERE session_id = :sessionId")
    suspend fun setLastEventId(sessionId: String, eventId: String)

    @Query("UPDATE session_state SET scan_cursor_ms = :cursorMs WHERE session_id = :sessionId AND scan_cursor_ms < :cursorMs")
    suspend fun advanceScanCursor(sessionId: String, cursorMs: Long)

    @Query("UPDATE session_state SET paused = :paused, paused_at_ms = :pausedAtMs WHERE session_id = :sessionId")
    suspend fun setPaused(sessionId: String, paused: Boolean, pausedAtMs: Long?)

    @Query(
        "UPDATE session_state SET join_code = :code, invite_url = :inviteUrl, qr_payload = :qr, " +
            "expires_at_ms = :expiresAtMs WHERE session_id = :sessionId",
    )
    suspend fun updateInvite(sessionId: String, code: String, inviteUrl: String, qr: String, expiresAtMs: Long)

    @Query(
        "UPDATE session_state SET role = :role, host_id = :hostId, join_code = :code, invite_url = :inviteUrl, " +
            "qr_payload = :qr, expires_at_ms = :expiresAtMs WHERE session_id = :sessionId",
    )
    suspend fun updateInfo(sessionId: String, role: String, hostId: String, code: String, inviteUrl: String, qr: String, expiresAtMs: Long)

    @Query("UPDATE session_state SET host_id = :hostId, role = :role WHERE session_id = :sessionId")
    suspend fun setHost(sessionId: String, hostId: String, role: String)

    @Query("SELECT member_id FROM session_members WHERE session_id = :sessionId ORDER BY member_id")
    fun observeMembers(sessionId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMembers(members: List<SessionMember>)

    @Query("DELETE FROM session_members WHERE session_id = :sessionId AND member_id = :memberId")
    suspend fun removeMember(sessionId: String, memberId: String)

    @Query("DELETE FROM session_members WHERE session_id = :sessionId")
    suspend fun clearMembers(sessionId: String)

    @Transaction
    suspend fun replaceMembers(sessionId: String, memberIds: List<String>) {
        clearMembers(sessionId)
        addMembers(memberIds.map { SessionMember(sessionId, it) })
    }

    @Query("DELETE FROM session_members")
    suspend fun clearAllMembers()

    @Insert
    suspend fun insertPauseWindow(window: PauseWindow)

    @Query("SELECT * FROM pause_windows WHERE session_id = :sessionId")
    suspend fun pauseWindows(sessionId: String): List<PauseWindow>

    @Query("DELETE FROM pause_windows")
    suspend fun clearPauseWindows()
}

@Dao
interface PendingPhotoDao {
    /** Returns -1 for each row that already existed (INSERT OR IGNORE on media_id). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<PendingPhoto>): List<Long>

    @Query("SELECT * FROM pending_photos WHERE media_id = :mediaId")
    suspend fun get(mediaId: Long): PendingPhoto?

    @Query("SELECT media_id FROM pending_photos WHERE media_id IN (:ids)")
    suspend fun existingIds(ids: List<Long>): List<Long>

    /** The next row the upload worker should look at. */
    @Query(
        "SELECT * FROM pending_photos WHERE session_id = :sessionId " +
            "AND state IN ('PENDING', 'HASHED', 'UPLOADING', 'WAITING') AND next_attempt_ms <= :nowMs " +
            "ORDER BY taken_at_ms, media_id LIMIT 1",
    )
    suspend fun nextDue(sessionId: String, nowMs: Long): PendingPhoto?

    /** The earliest future attempt among unfinished rows, to schedule a wake-up. */
    @Query(
        "SELECT MIN(next_attempt_ms) FROM pending_photos WHERE session_id = :sessionId " +
            "AND state IN ('PENDING', 'HASHED', 'UPLOADING', 'WAITING') AND next_attempt_ms > :nowMs",
    )
    suspend fun nextWakeupMs(sessionId: String, nowMs: Long): Long?

    /**
     * Writes the worker's view of a row unless the row has meanwhile reached a final state
     * (the receive pipeline marks rows SYNCED when another member's upload of the same bytes is
     * stored). Final states are never left. Returns the number of rows changed.
     */
    @Query(
        "UPDATE pending_photos SET state = :state, sha256 = :sha256, size_bytes = :sizeBytes, " +
            "copy_path = :copyPath, upload_id = :uploadId, upload_url = :uploadUrl, chunk_size = :chunkSize, " +
            "attempts = :attempts, next_attempt_ms = :nextAttemptMs, last_error = :lastError " +
            "WHERE media_id = :mediaId AND state NOT IN ('SYNCED', 'SKIPPED', 'FAILED')",
    )
    suspend fun updateUnlessFinal(
        mediaId: Long,
        state: String,
        sha256: String?,
        sizeBytes: Long,
        copyPath: String?,
        uploadId: String?,
        uploadUrl: String?,
        chunkSize: Int,
        attempts: Int,
        nextAttemptMs: Long,
        lastError: String?,
    ): Int

    @Query(
        "UPDATE pending_photos SET state = 'SYNCED', next_attempt_ms = 0 " +
            "WHERE sha256 = :sha256 AND state NOT IN ('SYNCED', 'SKIPPED', 'FAILED')",
    )
    suspend fun markSyncedByHash(sha256: String): Int

    @Query(
        "UPDATE pending_photos SET state = 'SKIPPED', last_error = :reason " +
            "WHERE sha256 = :sha256 AND state NOT IN ('SYNCED', 'SKIPPED', 'FAILED')",
    )
    suspend fun markSkippedByHash(sha256: String, reason: String): Int

    @Query("SELECT COUNT(*) FROM pending_photos WHERE sha256 = :sha256")
    suspend fun countByHash(sha256: String): Int

    @Query("SELECT * FROM pending_photos WHERE session_id = :sessionId ORDER BY taken_at_ms DESC")
    fun observeForSession(sessionId: String): Flow<List<PendingPhoto>>

    @Query("SELECT copy_path FROM pending_photos WHERE copy_path IS NOT NULL AND state IN ('PENDING', 'HASHED', 'UPLOADING', 'WAITING')")
    suspend fun liveCopyPaths(): List<String>

    @Query("UPDATE pending_photos SET copy_path = NULL WHERE state IN ('SYNCED', 'SKIPPED', 'FAILED')")
    suspend fun forgetFinalCopies()

    @Query("DELETE FROM pending_photos")
    suspend fun clear()
}

@Dao
interface ReceivedAssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(asset: ReceivedAsset)

    @Query("SELECT media_id FROM received_assets WHERE media_id IN (:ids)")
    suspend fun existingIds(ids: List<Long>): List<Long>

    @Query("DELETE FROM received_assets WHERE created_at_ms < :beforeMs")
    suspend fun purgeOlderThan(beforeMs: Long): Int
}

@Dao
interface HashDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(hash: KnownHash)

    @Query("SELECT EXISTS(SELECT 1 FROM hashes WHERE sha256 = :sha256)")
    suspend fun contains(sha256: String): Boolean

    @Query("DELETE FROM hashes WHERE created_at_ms < :beforeMs")
    suspend fun purgeOlderThan(beforeMs: Long): Int
}

@Dao
interface ReceivedPhotoDao {
    @Query("SELECT * FROM received_photos WHERE sha256 = :sha256")
    suspend fun get(sha256: String): ReceivedPhoto?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(photo: ReceivedPhoto): Long

    @Query("UPDATE received_photos SET thumb_path = :path WHERE sha256 = :sha256 AND state NOT IN ('RECALLED', 'EXPIRED')")
    suspend fun setThumb(sha256: String, path: String): Int

    @Query(
        "UPDATE received_photos SET preview_path = :path, state = CASE WHEN state = 'THUMB' THEN 'PREVIEW' ELSE state END " +
            "WHERE sha256 = :sha256 AND state NOT IN ('RECALLED', 'EXPIRED')",
    )
    suspend fun setPreview(sha256: String, path: String): Int

    @Query("UPDATE received_photos SET media_id = :mediaId WHERE sha256 = :sha256")
    suspend fun setMediaId(sha256: String, mediaId: Long)

    @Query("UPDATE received_photos SET state = 'SAVED' WHERE sha256 = :sha256 AND state NOT IN ('RECALLED', 'EXPIRED')")
    suspend fun markSaved(sha256: String): Int

    @Query("UPDATE received_photos SET state = :state, thumb_path = NULL, preview_path = NULL WHERE sha256 = :sha256")
    suspend fun markTerminal(sha256: String, state: String)

    @Query("UPDATE received_photos SET thumb_path = NULL, preview_path = NULL WHERE sha256 = :sha256")
    suspend fun clearCachedPaths(sha256: String)

    @Query(
        "SELECT * FROM received_photos WHERE expires_at_ms > 0 AND expires_at_ms <= :nowMs " +
            "AND (state NOT IN ('RECALLED', 'EXPIRED', 'SAVED') OR thumb_path IS NOT NULL OR preview_path IS NOT NULL)",
    )
    suspend fun expiredBy(nowMs: Long): List<ReceivedPhoto>

    @Query(
        "SELECT * FROM received_photos WHERE session_id = :sessionId AND state NOT IN ('RECALLED', 'EXPIRED') " +
            "ORDER BY taken_at_ms DESC",
    )
    fun observeVisible(sessionId: String): Flow<List<ReceivedPhoto>>

    @Query("DELETE FROM received_photos")
    suspend fun clear()
}
