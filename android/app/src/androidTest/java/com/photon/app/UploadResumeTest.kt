package com.photon.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photon.app.core.Backoff
import com.photon.app.core.Clock
import com.photon.app.core.Hashes
import com.photon.app.core.MediaCache
import com.photon.app.data.db.PendingPhoto
import com.photon.app.data.db.PhotonDatabase
import com.photon.app.data.db.UploadState
import com.photon.app.data.prefs.SettingsStore
import com.photon.app.net.ImageApi
import com.photon.app.net.PhotonApi
import com.photon.app.net.RetryPolicy
import com.photon.app.upload.QueueResult
import com.photon.app.upload.ThroughputEstimator
import com.photon.app.upload.UploadPreparer
import com.photon.app.upload.Uploader
import com.photon.proto.AnnounceResponse
import com.photon.proto.CompleteResponse
import com.photon.proto.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 7.13: an upload killed part-way resumes without resending finished chunks, and a network that
 * drops during an upload recovers. The server is a scripted MockWebServer that keeps the set of
 * received chunks like the image server does.
 */
@RunWith(AndroidJUnit4::class)
class UploadResumeTest {
    private val context = TestSupport.context
    private lateinit var db: PhotonDatabase
    private lateinit var server: MockWebServer
    private val release = CountDownLatch(1)

    private val chunk = 256 * 1024
    private val serverChunks: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())
    private val putsThisPhase: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    /** When set, chunk PUTs after this many successes hang (the process is about to die). */
    @Volatile private var hangAfter: Int? = null

    /** Chunk index -> how many times to drop the connection before accepting it. */
    private val drops = mutableMapOf<Int, Int>()

    private lateinit var photo: File

    @Before
    fun setUp() {
        db = TestSupport.inMemoryDb()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/v1/photos/announce" -> proto(
                        AnnounceResponse(
                            result = AnnounceResponse.Result.UPLOAD,
                            upload_id = "u1",
                            upload_url = server.url("/v1/uploads/u1").toString(),
                            upload_token = "upload-token",
                            chunk_size_hint = chunk,
                        ).encode(),
                    )
                    path == "/v1/uploads/u1" -> proto(
                        UploadStatus(upload_id = "u1", state = "RECEIVING", received_chunks = serverChunks.sorted()).encode(),
                    )
                    path.startsWith("/v1/uploads/u1/chunks/") -> {
                        val n = path.substringAfterLast('/').toInt()
                        synchronized(drops) {
                            val left = drops[n] ?: 0
                            if (left > 0) {
                                drops[n] = left - 1
                                return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                            }
                        }
                        val limit = hangAfter
                        if (limit != null && serverChunks.size >= limit) {
                            release.await(30, TimeUnit.SECONDS)
                            return MockResponse().setResponseCode(503)
                        }
                        putsThisPhase += n
                        serverChunks += n
                        MockResponse().setResponseCode(204)
                    }
                    path == "/v1/uploads/u1/complete" -> proto(CompleteResponse(state = "STORED").encode())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        photo = File(context.cacheDir, "resume-src.jpg").apply { writeBytes(Random(7).nextBytes(4 * chunk + 1000)) }
    }

    @After
    fun tearDown() {
        release.countDown()
        server.shutdown()
        db.close()
    }

    private fun proto(bytes: ByteArray) =
        MockResponse().setHeader("Content-Type", "application/x-protobuf").setBody(Buffer().write(bytes))

    /** A fresh uploader, as a new process would build it. */
    private fun uploader(): Uploader {
        val client = OkHttpClient()
        val cache = MediaCache(context)
        return Uploader(
            context = context,
            db = db,
            api = PhotonApi(server.url("/"), client, client),
            imageApi = ImageApi(server.url("/"), { client }, rewriteLocalUrls = false),
            settings = SettingsStore(context),
            preparer = UploadPreparer(context, cache),
            cache = cache,
            throughput = ThroughputEstimator(),
            sessions = { error("no session errors expected") },
            retry = RetryPolicy(Backoff(baseMs = 10, capMs = 50)),
            clock = Clock.SYSTEM,
        )
    }

    private suspend fun queuePhoto() {
        db.sessions().insert(TestSupport.session())
        db.pending().insertIgnore(
            listOf(
                PendingPhoto(
                    mediaId = 42, uri = Uri.fromFile(photo).toString(), sessionId = "sess-1",
                    takenAtMs = System.currentTimeMillis(), mime = "image/jpeg", state = UploadState.PENDING,
                    createdAtMs = System.currentTimeMillis(),
                ),
            ),
        )
    }

    @Test
    fun killedMidUploadResumesWithoutResendingChunks() = runBlocking {
        queuePhoto()

        // First "process": two chunks get through, then it dies.
        hangAfter = 2
        val job = launch(Dispatchers.IO) { uploader().runQueue() }
        withTimeout(20_000) { while (serverChunks.size < 2) delay(20) }
        job.cancel()
        job.join()
        val firstRun = serverChunks.toSet()
        val row = db.pending().get(42)!!
        assertEquals(UploadState.UPLOADING, row.state)
        assertEquals(chunk, row.chunkSize)

        // Second "process": only the missing chunks go out.
        hangAfter = null
        putsThisPhase.clear()
        val result = withTimeout(20_000) { uploader().runQueue() }

        assertTrue(result is QueueResult.Done)
        assertTrue("resent ${putsThisPhase.intersect(firstRun)}", putsThisPhase.intersect(firstRun).isEmpty())
        assertEquals((0..4).toSet(), serverChunks.toSet())
        assertEquals(UploadState.SYNCED, db.pending().get(42)!!.state)
        assertTrue(db.hashes().contains(Hashes.sha256(photo)))
    }

    @Test
    fun droppedConnectionsAreRetriedPerChunk() = runBlocking {
        queuePhoto()
        synchronized(drops) { drops[1] = 2 }
        val result = withTimeout(20_000) { uploader().runQueue() }
        assertTrue(result is QueueResult.Done)
        assertEquals(UploadState.SYNCED, db.pending().get(42)!!.state)
    }

    @Test
    fun offlineTooLongRetriesLaterAndThenFinishes() = runBlocking {
        queuePhoto()
        synchronized(drops) { drops[3] = 10 } // more than the per-chunk retries
        val first = withTimeout(20_000) { uploader().runQueue() }
        assertEquals(QueueResult.Retry, first)
        assertEquals(UploadState.UPLOADING, db.pending().get(42)!!.state)

        // "Network back": WorkManager runs the queue again.
        synchronized(drops) { drops.clear() }
        val sentBefore = serverChunks.toSet()
        putsThisPhase.clear()
        val second = withTimeout(20_000) { uploader().runQueue() }
        assertTrue(second is QueueResult.Done)
        assertTrue(putsThisPhase.intersect(sentBefore).isEmpty())
        assertEquals(UploadState.SYNCED, db.pending().get(42)!!.state)
    }
}
