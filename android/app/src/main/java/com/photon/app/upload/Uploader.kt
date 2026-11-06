package com.photon.app.upload

import android.content.Context
import com.photon.app.core.Clock
import com.photon.app.core.Hashes
import com.photon.app.core.MediaCache
import com.photon.app.core.Notifications
import com.photon.app.data.db.KnownHash
import com.photon.app.data.db.PendingPhoto
import com.photon.app.data.db.PhotonDatabase
import com.photon.app.data.db.SessionState
import com.photon.app.data.db.UploadState
import com.photon.app.data.prefs.SettingsStore
import com.photon.app.e2ee.Bundle
import com.photon.app.e2ee.E2ee
import com.photon.app.e2ee.E2eeException
import com.photon.app.e2ee.Header
import com.photon.app.keys.KeyManager
import com.photon.app.net.ApiException
import com.photon.app.net.CompleteOutcome
import com.photon.app.net.ErrorCodes
import com.photon.app.net.ErrorKind
import com.photon.app.net.ImageApi
import com.photon.app.net.PhotonApi
import com.photon.app.net.RetryPolicy
import com.photon.app.session.SessionManager
import com.photon.proto.AnnounceRequest
import com.photon.proto.AnnounceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicReference

sealed interface QueueResult {
    /** Nothing more to do now; run again at [wakeupAtMs] if set. */
    data class Done(val wakeupAtMs: Long?) : QueueResult

    /** A transient failure; let WorkManager back off and retry. */
    data object Retry : QueueResult
}

/**
 * The upload queue (7.7, e2ee.md 7): copy, strip GPS and hash; check the image, tag it and build the encrypted
 * file; announce, resume, chunks, complete. Every step is resumable: the row keeps its encrypted file, hash,
 * upload id and chunk size, so a run that is killed part-way continues where the server says it stopped, and a
 * re-announce sends the same bytes.
 */
class Uploader(
    private val context: Context,
    private val db: PhotonDatabase,
    private val api: PhotonApi,
    private val imageApi: ImageApi,
    private val settings: SettingsStore,
    private val preparer: UploadPreparer,
    private val packager: PhotoPackager,
    private val keys: KeyManager,
    private val cache: MediaCache,
    private val throughput: ThroughputEstimator,
    private val sessions: () -> SessionManager,
    private val retry: RetryPolicy,
    private val clock: Clock,
    /** False once the refresh endpoint has refused the login; until then a 401 is transient. */
    private val signedIn: () -> Boolean = { true },
) {
    /** What happens to the queue after one row. */
    private sealed interface Step {
        /** The row is settled or deferred; go on. */
        data object Next : Step

        data object RetryLater : Step

        /** Rate limited: stop the queue until then. */
        data class PauseQueue(val untilMs: Long) : Step

        /** Session gone, paused or signed out. */
        data object Stop : Step
    }

    /** A row whose file is encrypted for the current epoch ([epoch]), or the step that settles it instead. */
    private sealed interface Current {
        data class Ready(val row: PendingPhoto, val epoch: Int) : Current
        data class Settled(val step: Step) : Current
    }

    private sealed interface Transfer {
        data object Stored : Transfer
        data object Reannounce : Transfer

        /** The upload is already finalized on the server; ask `complete` for the result. */
        data object Finalized : Transfer
        data class Settle(val step: Step) : Transfer
    }

    private var throughputLoaded = false

    suspend fun runQueue(): QueueResult = queueMutex.withLock {
        if (!throughputLoaded) {
            throughput.load(settings.uploadThroughput())
            throughputLoaded = true
        }
        try {
            while (true) {
                val s = db.sessions().get() ?: return@withLock QueueResult.Done(null)
                if (s.paused) return@withLock QueueResult.Done(null)
                val row = db.pending().nextDue(s.sessionId, clock.nowMs()) ?: break
                when (val step = process(s, row)) {
                    Step.Next -> Unit
                    Step.RetryLater -> return@withLock QueueResult.Retry
                    is Step.PauseQueue -> return@withLock QueueResult.Done(step.untilMs)
                    Step.Stop -> return@withLock QueueResult.Done(null)
                }
            }
            val s = db.sessions().get() ?: return@withLock QueueResult.Done(null)
            QueueResult.Done(db.pending().nextWakeupMs(s.sessionId, clock.nowMs()))
        } finally {
            withContext(NonCancellable) {
                cleanupCopies()
                throughput.snapshot()?.let { settings.setUploadThroughput(it) }
            }
        }
    }

    private suspend fun process(s: SessionState, row: PendingPhoto): Step {
        var r = row

        // 1. Copy, strip GPS and hash the original; then check it, tag it, make the thumbnail and preview and
        //    build the encrypted file for the current epoch (e2ee.md 7 steps 1-5). Needs the current key first.
        val built = r.copyPath?.let { File(it) }?.takeIf { it.exists() }
        if (r.state == UploadState.PENDING || r.sha256 == null || r.photoTag == null || built == null) {
            val bundle = when (val k = currentBundle(s, r)) {
                is KeyCheck.Ready -> k.bundle
                is KeyCheck.Settled -> return k.step
            }
            val prepared = try {
                preparer.prepare(r, settings.stripGpsNow())
            } catch (e: IOException) {
                return Step.RetryLater
            }
            when (prepared) {
                is Prepared.Skip -> return settle(r, UploadState.SKIPPED, prepared.reason)
                is Prepared.Ready -> {
                    // Echo layer 4: this original was received or shared before.
                    if (db.hashes().contains(prepared.sha256)) {
                        prepared.copy.delete()
                        return settle(r.copy(originalSha256 = prepared.sha256), UploadState.SKIPPED, "known hash")
                    }
                    val packaged = try {
                        packager.build(r, prepared.copy, prepared.sha256, bundle)
                    } catch (e: IOException) {
                        return Step.RetryLater
                    } finally {
                        prepared.copy.delete()
                    }
                    when (packaged) {
                        is Packaged.Refused -> return settle(r.copy(originalSha256 = prepared.sha256), packaged.state, packaged.reason)
                        is Packaged.Ready -> {
                            r = r.copy(
                                state = if (r.state == UploadState.PENDING) UploadState.HASHED else r.state,
                                sha256 = packaged.sha256,
                                sizeBytes = packaged.size,
                                copyPath = packaged.file.path,
                                photoTag = packaged.photoTag,
                                originalSha256 = prepared.sha256,
                                // A new file (a fresh photo key) is a new upload.
                                uploadId = null,
                                uploadUrl = null,
                                chunkSize = 0,
                                lastError = r.lastError.takeUnless { it == KEY_WAIT },
                            )
                            if (!save(r)) return Step.Next
                        }
                    }
                }
            }
        }

        // 2–5. Announce, then transfer; a superseded or expired upload is announced again. The key answers
        // (e2ee.md 7) rotate or refresh the keys, rebuild the file and announce again.
        var announces = 0
        var keyRounds = 0
        while (announces < MAX_ANNOUNCES_PER_RUN) {
            val epoch = when (val c = ensureCurrentEpoch(s, r)) {
                is Current.Ready -> {
                    r = c.row
                    c.epoch
                }
                is Current.Settled -> return c.step
            }
            announces++
            val announce = try {
                api.announce(
                    AnnounceRequest(
                        sha256 = Hashes.bytes(r.sha256!!),
                        size = r.sizeBytes,
                        mime = E2ee.MIME,
                        taken_at_ms = r.takenAtMs,
                        session_id = s.sessionId,
                        photo_tag = Hashes.bytes(r.photoTag!!),
                        key_epoch = epoch,
                    ),
                )
            } catch (e: ApiException) {
                if (isKeyAnswer(e)) {
                    if (keyRounds++ >= MAX_KEY_ROUNDS) return defer(r, retry.backoffMs(r.attempts + 3), e.code)
                    onKeyAnswer(s, r, e)?.let { return it }
                    announces--
                    continue
                }
                return onApiError(r, e)
            } catch (e: IOException) {
                return Step.RetryLater
            }

            when (announce.result) {
                AnnounceResponse.Result.ALREADY_UPLOADED -> return synced(r)
                AnnounceResponse.Result.IN_PROGRESS -> return waiting(r)
                AnnounceResponse.Result.UPLOAD -> Unit
            }

            r = r.copy(
                state = UploadState.UPLOADING,
                uploadId = announce.upload_id,
                uploadUrl = announce.upload_url,
                chunkSize = if (announce.upload_id == r.uploadId) r.chunkSize else 0,
            )
            if (!save(r)) return Step.Next

            val url = imageApi.uploadUrl(announce.upload_url, announce.upload_id)
            when (val t = transfer(r, url, announce)) {
                Transfer.Stored -> return synced(r)
                Transfer.Reannounce, Transfer.Finalized -> r = db.pending().get(r.mediaId) ?: return Step.Next
                is Transfer.Settle -> return t.step
            }
            if (r.isFinal) return Step.Next
        }
        return defer(r, retry.backoffMs(r.attempts + 3), "announced too often")
    }

    private sealed interface KeyCheck {
        data class Ready(val bundle: Bundle) : KeyCheck
        data class Settled(val step: Step) : KeyCheck
    }

    /**
     * The current epoch's bundle (e2ee.md 7 step 3). The first time this process sees the session, key
     * maintenance runs first, so a newer epoch or a freshly shared key is known.
     */
    private suspend fun currentBundle(s: SessionState, r: PendingPhoto): KeyCheck {
        if (keys.knownEpoch(s.sessionId) == null) {
            try {
                keys.maintain(s.sessionId)
            } catch (e: IOException) {
                return KeyCheck.Settled(Step.RetryLater)
            } catch (e: ApiException) {
                if (sessions().onSessionError(e)) return KeyCheck.Settled(Step.Stop)
                // Otherwise go on with the keys held; the announce answers if they are stale.
            }
        }
        val bundle = keys.currentBundle(s.sessionId) ?: return KeyCheck.Settled(waitForKey(r))
        return KeyCheck.Ready(bundle)
    }

    /** Rebuilds a file encrypted for an older epoch than the current one, so the announce carries the current. */
    private suspend fun ensureCurrentEpoch(s: SessionState, row: PendingPhoto): Current {
        var r = row
        val file = File(r.copyPath ?: return Current.Settled(prepareAgain(r)))
        val fileEpoch = try {
            readHeader(file).epoch
        } catch (e: IOException) {
            return Current.Settled(prepareAgain(r))
        } catch (e: E2eeException) {
            return Current.Settled(prepareAgain(r))
        }
        val bundle = when (val k = currentBundle(s, r)) {
            is KeyCheck.Ready -> k.bundle
            is KeyCheck.Settled -> return Current.Settled(k.step)
        }
        if (r.lastError == KEY_WAIT) r = r.copy(lastError = null)
        if (bundle.epoch == fileEpoch) return Current.Ready(r, fileEpoch)

        val (rebuilt, sha) = try {
            packager.rebuild(file, keys.epochKeys(s.sessionId), bundle, r.mediaId)
        } catch (e: E2eeException) {
            // Our own file no longer decrypts (its epoch key is gone): build it again from the gallery.
            return Current.Settled(prepareAgain(r))
        } catch (e: IOException) {
            return Current.Settled(Step.RetryLater)
        }
        r = r.copy(sha256 = sha, sizeBytes = rebuilt.length(), copyPath = rebuilt.path, uploadId = null, uploadUrl = null, chunkSize = 0)
        if (!save(r)) return Current.Settled(Step.Next)
        return Current.Ready(r, bundle.epoch)
    }

    private fun readHeader(file: File): Header = FileInputStream(file).use { input ->
        val head = ByteArray(E2ee.HEADER_LEN)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        Header.parse(head.copyOf(read))
    }

    /** The encrypted file is gone or unreadable: start the row over (copy, strip, hash, build). */
    private suspend fun prepareAgain(r: PendingPhoto): Step {
        r.copyPath?.let { File(it).delete() }
        save(r.copy(state = UploadState.PENDING, sha256 = null, copyPath = null, uploadId = null, uploadUrl = null, chunkSize = 0))
        return Step.Next
    }

    private fun isKeyAnswer(e: ApiException) =
        e.isCode(ErrorCodes.KEY_ROTATION_REQUIRED, ErrorCodes.KEY_EPOCH_STALE, ErrorCodes.KEY_REQUIRED)

    /**
     * e2ee.md 7: `KEY_ROTATION_REQUIRED` rotates (5.4, `EPOCH_TAKEN` included), `KEY_EPOCH_STALE` runs key
     * maintenance; both return null so the loop rebuilds the file and announces again. `KEY_REQUIRED` waits for
     * `KEY_SHARED`.
     */
    private suspend fun onKeyAnswer(s: SessionState, r: PendingPhoto, e: ApiException): Step? = try {
        when (e.code) {
            ErrorCodes.KEY_ROTATION_REQUIRED -> {
                keys.rotate(s.sessionId)
                null
            }
            ErrorCodes.KEY_EPOCH_STALE -> {
                keys.maintain(s.sessionId)
                null
            }
            else -> {
                keys.maintain(s.sessionId)
                waitForKey(r)
            }
        }
    } catch (io: IOException) {
        Step.RetryLater
    } catch (api: ApiException) {
        when {
            sessions().onSessionError(api) -> Step.Stop
            api.isCode(ErrorCodes.KEY_REQUIRED) -> waitForKey(r)
            api.retryable || api.kind == ErrorKind.UNAVAILABLE -> Step.RetryLater
            else -> defer(r, retry.backoffMs(r.attempts + 3), api.code)
        }
    }

    /**
     * No key for the current epoch on this phone yet (a newcomer): the row waits. Key maintenance makes it due
     * again as soon as a member's app has shared the key ([com.photon.app.keys.KeySync]); until then it is
     * retried with backoff.
     */
    private suspend fun waitForKey(r: PendingPhoto): Step {
        val wait = minOf(KEY_WAIT_FIRST_MS shl r.attempts.coerceIn(0, 4), WAIT_MAX_MS)
        save(r.copy(attempts = r.attempts + 1, nextAttemptMs = clock.nowMs() + wait, lastError = KEY_WAIT))
        return Step.Next
    }

    private suspend fun transfer(row: PendingPhoto, url: HttpUrl, announce: AnnounceResponse): Transfer {
        var r = row
        val token = announce.upload_token
        val file = File(r.copyPath ?: return Transfer.Reannounce)

        // 3. Resume: ask the server what it already has.
        val status = try {
            imageApi.status(url, token)
        } catch (e: ApiException) {
            return chunkError(r, e)
        } catch (e: IOException) {
            return Transfer.Settle(Step.RetryLater)
        }

        val chunkSize = if (r.chunkSize > 0) {
            r.chunkSize
        } else {
            ChunkPlanner.chooseChunkSize(r.sizeBytes, throughput.bytesPerSecond, announce.chunk_size_hint)
        }
        if (chunkSize != r.chunkSize) {
            r = r.copy(chunkSize = chunkSize)
            if (!save(r)) return Transfer.Settle(Step.Next)
        }
        val plan = ChunkPlan(r.sizeBytes, chunkSize)

        // 4. Chunks, unless the server already has the file or has moved past receiving.
        if (!status.server_has_file && (status.state.isEmpty() || status.state == STATE_RECEIVING)) {
            val missing = plan.missing(status.received_chunks)
            when (val outcome = sendChunks(r, file, plan, missing, url, token)) {
                null, Transfer.Finalized -> Unit
                else -> return outcome
            }
        }

        // 5. Complete, in a loop.
        return completeLoop(r, file, plan, url, token)
    }

    private suspend fun completeLoop(r: PendingPhoto, file: File, plan: ChunkPlan, url: HttpUrl, token: String): Transfer {
        var waits = 0
        var resends = 0
        while (true) {
            val outcome = try {
                imageApi.complete(url, token, plan.count)
            } catch (e: IOException) {
                if (++waits > MAX_COMPLETE_WAITS) return Transfer.Settle(Step.RetryLater)
                delay(retry.backoffMs(minOf(waits, 5)))
                continue
            } catch (e: ApiException) {
                when {
                    e.httpStatus == 400 && e.missingChunks.isNotEmpty() && resends++ < MAX_RESEND_ROUNDS -> {
                        val missing = e.missingChunks.filter { it in 0 until plan.count }.distinct()
                        when (val res = sendChunks(r, file, plan, missing, url, token)) {
                            null, Transfer.Finalized -> continue
                            else -> return res
                        }
                    }
                    e.httpStatus == 503 || e.isCode(ErrorCodes.PROCESSING_FAILED, ErrorCodes.BUSY) -> {
                        if (++waits > MAX_COMPLETE_WAITS) return Transfer.Settle(Step.RetryLater)
                        delay(e.retryAfterMs ?: retry.backoffMs(minOf(waits, 5)))
                        continue
                    }
                    else -> return chunkError(r, e)
                }
            }
            when (outcome) {
                is CompleteOutcome.Stored -> return Transfer.Stored
                is CompleteOutcome.Pending -> {
                    if (++waits > MAX_COMPLETE_WAITS) return Transfer.Settle(Step.RetryLater)
                    delay(outcome.retryAfterMs.coerceIn(500, 30_000))
                }
            }
        }
    }

    /** Sends [indexes], three at a time. Returns null when all arrived. */
    private suspend fun sendChunks(
        r: PendingPhoto,
        file: File,
        plan: ChunkPlan,
        indexes: List<Int>,
        url: HttpUrl,
        token: String,
    ): Transfer? {
        if (indexes.isEmpty()) return null
        val queue = Channel<Int>(Channel.UNLIMITED)
        indexes.forEach { queue.trySend(it) }
        queue.close()
        val failure = AtomicReference<Transfer?>(null)
        coroutineScope {
            List(minOf(PARALLEL_CHUNKS, indexes.size)) {
                launch(Dispatchers.IO) {
                    RandomAccessFile(file, "r").use { raf ->
                        val buffer = ByteArray(plan.chunkSize)
                        for (index in queue) {
                            if (failure.get() != null) break
                            val outcome = sendOne(r, raf, buffer, plan, index, url, token)
                            if (outcome != null) {
                                failure.compareAndSet(null, outcome)
                                break
                            }
                        }
                    }
                }
            }.joinAll()
        }
        return failure.get()
    }

    /** One chunk with its CRC32C, retried up to [CHUNK_RETRIES] times with backoff and jitter. */
    private suspend fun sendOne(
        r: PendingPhoto,
        raf: RandomAccessFile,
        buffer: ByteArray,
        plan: ChunkPlan,
        index: Int,
        url: HttpUrl,
        token: String,
    ): Transfer? {
        val length = plan.length(index)
        raf.seek(plan.offset(index))
        raf.readFully(buffer, 0, length)
        var attempt = 0
        while (true) {
            val started = System.nanoTime()
            try {
                imageApi.putChunk(url, token, index, buffer, 0, length)
                throughput.record(length.toLong(), System.nanoTime() - started)
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                val wait = retry.delayBeforeRetry(e, attempt) ?: return chunkError(r, e)
                if (attempt >= CHUNK_RETRIES) {
                    if (e.kind != ErrorKind.LIMITED) return Transfer.Settle(Step.RetryLater)
                    defer(r, wait, e.code)
                    return Transfer.Settle(Step.PauseQueue(clock.nowMs() + wait))
                }
                delay(wait)
            } catch (e: IOException) {
                if (attempt >= CHUNK_RETRIES) return Transfer.Settle(Step.RetryLater)
                delay(retry.backoffMs(attempt))
            }
            attempt++
        }
    }

    /** Errors from the upload endpoints (status, chunk, complete). */
    private suspend fun chunkError(r: PendingPhoto, e: ApiException): Transfer = when {
        e.isCode(ErrorCodes.UPLOAD_FINALIZED) -> Transfer.Finalized
        e.isCode(ErrorCodes.UPLOAD_SUPERSEDED, ErrorCodes.UPLOAD_EXPIRED, ErrorCodes.UNKNOWN_UPLOAD) -> Transfer.Reannounce
        // The upload token lives 10 minutes; announcing again issues a fresh one for the same upload.
        e.kind == ErrorKind.AUTH -> Transfer.Reannounce
        e.kind == ErrorKind.NOT_FOUND -> Transfer.Reannounce
        else -> Transfer.Settle(onApiError(r, e))
    }

    /** Errors that settle the row or the queue, per the error contract. */
    private suspend fun onApiError(r: PendingPhoto, e: ApiException): Step = when {
        sessions().onSessionError(e) -> Step.Stop
        e.isCode(ErrorCodes.PHOTO_RECALLED, ErrorCodes.PHOTO_EXPIRED) -> settle(r, UploadState.SKIPPED, e.code)
        e.kind == ErrorKind.REJECTED -> {
            if (e.httpStatus == 422) Notifications.uploadRejected(context, r.mediaId, e.code)
            settle(r, UploadState.FAILED, e.code)
        }
        e.kind == ErrorKind.LIMITED -> {
            val wait = e.retryAfterMs ?: DEFAULT_LIMIT_WAIT_MS
            defer(r, wait, e.code)
            Step.PauseQueue(clock.nowMs() + wait)
        }
        // The interceptor already tried a refresh. Signed out: wait for the next login, which
        // restarts the queue. Still signed in: transient, retry with backoff.
        e.kind == ErrorKind.AUTH -> if (signedIn()) Step.RetryLater else Step.Stop
        e.retryable || e.kind == ErrorKind.UNAVAILABLE -> Step.RetryLater
        else -> settle(r, UploadState.FAILED, e.code)
    }

    private suspend fun synced(r: PendingPhoto): Step {
        val now = clock.nowMs()
        // The file's hash for receive dedupe; the original's for echo layer 4.
        r.sha256?.let { db.hashes().insertIgnore(KnownHash(it, KnownHash.UPLOADED, r.sessionId, now)) }
        r.originalSha256?.let { db.hashes().insertIgnore(KnownHash(it, KnownHash.UPLOADED, r.sessionId, now)) }
        return settle(r, UploadState.SYNCED, null)
    }

    /**
     * IN_PROGRESS means another member's upload of these bytes is live, not that they are
     * stored. If that upload is rejected (someone may have announced this hash with other
     * bytes), nothing is stored and this device must still upload the real photo. So the row
     * is never marked SYNCED here: it waits for the PHOTO_STORED event (the receive pipeline
     * marks it SYNCED) and re-announces after 2 minutes, backing off to 15, until the answer
     * is ALREADY_UPLOADED or UPLOAD.
     */
    private suspend fun waiting(r: PendingPhoto): Step {
        val wait = minOf(WAIT_FIRST_MS shl r.attempts.coerceIn(0, 4), WAIT_MAX_MS)
        save(r.copy(state = UploadState.WAITING, attempts = r.attempts + 1, nextAttemptMs = clock.nowMs() + wait))
        return Step.Next
    }

    private suspend fun defer(r: PendingPhoto, waitMs: Long, reason: String): Step {
        save(r.copy(attempts = r.attempts + 1, nextAttemptMs = clock.nowMs() + waitMs, lastError = reason))
        return Step.Next
    }

    private suspend fun settle(r: PendingPhoto, state: String, reason: String?): Step {
        save(r.copy(state = state, lastError = reason, nextAttemptMs = 0))
        r.copyPath?.let { File(it).delete() }
        return Step.Next
    }

    /** False when the row reached a final state meanwhile (another path settled it). */
    private suspend fun save(r: PendingPhoto): Boolean = db.pending().updateUnlessFinal(
        mediaId = r.mediaId,
        state = r.state,
        sha256 = r.sha256,
        sizeBytes = r.sizeBytes,
        copyPath = r.copyPath,
        uploadId = r.uploadId,
        uploadUrl = r.uploadUrl,
        chunkSize = r.chunkSize,
        attempts = r.attempts,
        nextAttemptMs = r.nextAttemptMs,
        lastError = r.lastError,
        photoTag = r.photoTag,
        originalSha256 = r.originalSha256,
    ) > 0

    /** Step 6: copies of rows in a final state, and strays, are deleted. */
    private suspend fun cleanupCopies() {
        val live = db.pending().liveCopyPaths().toHashSet()
        cache.uploads.listFiles()?.forEach { if (it.path !in live) it.delete() }
        db.pending().forgetFinalCopies()
    }

    companion object {
        /** One queue runner per process, whichever WorkManager job started it. */
        private val queueMutex = Mutex()

        const val PARALLEL_CHUNKS = 3
        const val CHUNK_RETRIES = 3
        const val MAX_ANNOUNCES_PER_RUN = 3

        /** Rotations or key refreshes per row and run before backing off (e2ee.md 7). */
        const val MAX_KEY_ROUNDS = 3

        /** `last_error` of a row waiting for the current epoch's key; key maintenance makes those rows due. */
        const val KEY_WAIT = "KEY_REQUIRED"
        const val KEY_WAIT_FIRST_MS = 60_000L
        const val MAX_COMPLETE_WAITS = 30
        const val MAX_RESEND_ROUNDS = 3
        const val WAIT_FIRST_MS = 2 * 60_000L
        const val WAIT_MAX_MS = 15 * 60_000L
        const val DEFAULT_LIMIT_WAIT_MS = 60_000L
        private const val STATE_RECEIVING = "RECEIVING"
    }
}
