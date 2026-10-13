import Foundation
import XCTest
@testable import PhotonKit

final class ChangeLog: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [PhotonChange] = []
    var handler: ChangeHandler { { [self] change in lock.withLockValue { items.append(change) } } }
    var all: [PhotonChange] { lock.withLockValue { items } }
}

/// Everything an upload test needs, wired to the simulated server.
final class UploadHarness: @unchecked Sendable {
    let clock = TestClock()
    let server = FakeServer()
    let secure = MemorySecureStore()
    let store = FileStore(fileURL: nil)
    let library = FakeLibrary()
    let network = FakeNetwork()
    let changes = ChangeLog()
    let files: FileLayout
    let transport: FakeTransport
    let api: APIClient
    var tuning = PhotonTuning()

    init() {
        let root = tempDirectory()
        files = FileLayout(dataRoot: root.appendingPathComponent("data"), cacheRoot: root.appendingPathComponent("cache"))
        let server = self.server
        transport = FakeTransport { await server.handle($0) }
        secure.seedTokens()
        api = APIClient(config: testConfig, transport: transport, secureStore: secure, clock: clock)
        try! store.write { $0.session = makeSession(joinTimeMs: 1_000) }
        tuning.jobBackoff = Backoff(baseMs: 5_000, capMs: 300_000, random: { 0.5 })
        tuning.chunkBackoff = Backoff(baseMs: 500, capMs: 8_000, random: { 0.5 })
    }

    func engine() -> UploadEngine {
        UploadEngine(api: api, store: store, library: library, uploader: HTTPChunkUploader(transport: transport), files: files,
                     network: network, clock: clock, tuning: tuning, onChange: changes.handler)
    }

    /// A photo in the library and in the queue. Returns its sha256 hex.
    @discardableResult
    func addPhoto(_ id: String = "IMG-1/L0/001", size: Int = 2_500_000, seed: UInt8 = 1) -> String {
        let data = testBytes(size, seed: seed)
        library.add(id, data: data, createdAtMs: 5_000)
        try! store.write { $0.uploadJobs[id] = UploadJob(id: id, sessionID: "sid1", takenAtMs: 5_000, nowMs: clock.nowMs()) }
        return SHA256Hasher.hash(data).hexString
    }

    func job(_ id: String = "IMG-1/L0/001") -> UploadJob? { store.read { $0.uploadJobs[id] } }
    func chunkPuts(_ upload: String) -> [Int] { server.locked { $0.chunkPuts.filter { $0.upload == upload }.map(\.index) } }
}

final class UploadEngineTests: XCTestCase {
    func testHappyPathUploadsChunksAndCompletes() async throws {
        let h = UploadHarness()
        let hex = h.addPhoto()
        let result = await h.engine().run()
        XCTAssertEqual(result, .finished)

        let job = try XCTUnwrap(h.job())
        XCTAssertEqual(job.state, .synced)
        XCTAssertEqual(job.sha256Hex, hex)
        XCTAssertEqual(job.size, 2_500_000)
        XCTAssertEqual(job.chunkSize, 1 << 20)
        XCTAssertEqual(h.chunkPuts("up1").sorted(), [0, 1, 2])
        XCTAssertEqual(h.server.locked { $0.completeChunkCounts }, [3])
        XCTAssertEqual(h.server.locked { $0.storedHashes }, [hex])
        XCTAssertEqual(h.store.read { $0.knownHashes[hex]?.source }, .uploaded)
        // The copy and the chunk files are gone once the job is final.
        XCTAssertFalse(FileManager.default.fileExists(atPath: h.files.uploadDirectory(key: job.key).path))

        // Announce carried the hash, size, mime, capture time and session.
        let announce = body(h.transport.requests(path: "/v1/photos/announce")[0], as: PbAnnounceRequest.self)
        XCTAssertEqual(announce.sha256.hexString, hex)
        XCTAssertEqual(announce.size, 2_500_000)
        XCTAssertEqual(announce.mime, "image/jpeg")
        XCTAssertEqual(announce.takenAtMs, 5_000)
        XCTAssertEqual(announce.sessionID, "sid1")
        // Chunks: PUT to the image server with the upload token and a CRC32C header.
        let put = try XCTUnwrap(h.transport.requests.first { $0.url.path == "/v1/uploads/up1/chunks/0" })
        XCTAssertEqual(put.method, "PUT")
        XCTAssertEqual(put.url.host, "img.test")
        XCTAssertEqual(put.headers["Authorization"], "Bearer upload-token-1")
        let firstChunk = testBytes(2_500_000, seed: 1).prefix(1 << 20)
        XCTAssertEqual(put.headers["X-Chunk-CRC32C"], CRC32C.headerValue(CRC32C.checksum(firstChunk)))
        // Location removed by default.
        XCTAssertEqual(h.library.locked { $0.exportCalls.map(\.strip) }, [true])
    }

    func testStripLocationSettingIsPassedToExport() async throws {
        let h = UploadHarness()
        try h.store.write { $0.settings.stripLocation = false }
        h.addPhoto()
        _ = await h.engine().run()
        XCTAssertEqual(h.library.locked { $0.exportCalls.map(\.strip) }, [false])
    }

    func testAtMostThreeChunksInParallel() async throws {
        let h = UploadHarness()
        h.addPhoto(size: 8 * (1 << 20))
        _ = await h.engine().run()
        XCTAssertEqual(h.job()?.state, .synced)
        XCTAssertEqual(h.chunkPuts("up1").count, 8)
        let maxInFlight = h.server.locked { $0.maxInFlight }
        XCTAssertLessThanOrEqual(maxInFlight, 3)
        XCTAssertGreaterThan(maxInFlight, 1)
    }

    func testAlreadyUploadedIsSyncedWithoutTransfer() async throws {
        let h = UploadHarness()
        let hex = h.addPhoto()
        h.server.locked { _ = $0.storedHashes.insert(hex) }
        _ = await h.engine().run()
        XCTAssertEqual(h.job()?.state, .synced)
        XCTAssertTrue(h.chunkPuts("up1").isEmpty)
        XCTAssertEqual(h.store.read { $0.knownHashes[hex]?.source }, .uploaded)
    }

    func testKnownHashIsSkippedBeforeAnnounce() async throws {
        let h = UploadHarness()
        let hex = h.addPhoto()
        try h.store.write { $0.knownHashes[hex] = KnownHash(sha256Hex: hex, source: .received, sessionID: "sid1", recordedAtMs: 0) }
        _ = await h.engine().run()
        XCTAssertEqual(h.job()?.state, .skipped)
        XCTAssertEqual(h.server.locked { $0.announceCount }, 0)
    }

    func testResumeSendsOnlyMissingChunks() async throws {
        let h = UploadHarness()
        let hex = h.addPhoto()
        let data = testBytes(2_500_000, seed: 1)
        h.server.locked { s in
            var u = FakeServer.Upload(id: "up1", sha: hex, size: 2_500_000, token: "")
            u.chunks[0] = data.subdata(in: 0..<(1 << 20))
            u.chunks[2] = data.subdata(in: (2 << 20)..<2_500_000)
            s.uploads["up1"] = u
        }
        _ = await h.engine().run()
        XCTAssertEqual(h.job()?.state, .synced)
        XCTAssertEqual(h.chunkPuts("up1"), [1])
    }

    func testServerHasFileGoesStraightToComplete() async throws {
        let h = UploadHarness()
        h.addPhoto()
        h.server.statusHook = { id in
            var st = PbUploadStatus()
            st.uploadID = id
            st.state = "RECEIVING"
            st.serverHasFile = true
            return pbResponse(st)
        }
        h.server.completeHook = { _ in pbResponse(PbCompleteResponse()) }
        _ = await h.engine().run()
        XCTAssertEqual(h.job()?.state, .synced)
        XCTAssertTrue(h.chunkPuts("up1").isEmpty)
    }

    func testInProgressWaitsBacksOffAndIsSyncedByTheEvent() async throws {
        let h = UploadHarness()
        let hex = h.addPhoto()
        h.server.announceHook = { _, _ in
            var r = PbAnnounceResponse()
            r.result = .inProgress
            return pbResponse(r)
        }
        let engine = h.engine()
        let start = h.clock.nowMs()
        _ = await engine.run()
        XCTAssertEqual(h.job()?.state, .waiting)
        XCTAssertEqual(h.job()?.nextAttemptAtMs, start + 120_000)
        let due = await engine.nextDueAtMs()
        XCTAssertEqual(due, start + 120_000)

        // Not due yet: no second announce.
        _ = await engine.run()
        XCTAssertEqual(h.server.locked { $0.announceCount }, 1)

        // Due: announce again; still IN_PROGRESS, so the wait doubles (towards 15 minutes).
        h.clock.advance(120_001)
        _ = await engine.run()
        XCTAssertEqual(h.server.locked { $0.announceCount }, 2)
        XCTAssertEqual(h.job()?.waitDelayMs, 240_000)

        // Never synced on IN_PROGRESS alone; the PHOTO_STORED event does it.
        await engine.photoStored(sha256Hex: hex)
        XCTAssertEqual(h.job()?.state, .synced)
        XCTAssertNotNil(h.store.read { $0.knownHashes[hex] })
        XCTAssertTrue(h.chunkPuts("up1").isEmpty)
    }

    func testInProgressThenOtherUploadRejectedStillUploads() async throws {
        let h = UploadHarness()
        h.addPhoto()
        h.server.announceHook = { call, _ in
            guard call == 1 else { return nil }
            var r = PbAnnounceResponse()
            r.result = .inProgress
            return pbResponse(r)
        }
        let engine = h.engine()
        _ = await engine.run()
        XCTAssertEqual(h.job()?.state, .waiting)
        h.clock.advance(120_001)
        _ = await engine.run()
        XCTAssertEqual(h.job()?.state, .synced)
        XCTAssertEqual(h.chunkPuts("up1").sorted(), [0, 1, 2])
    }

    func testCompletePollsOn202AndRetriesOn503() async throws {
        let h = UploadHarness()
        h.addPhoto()
        h.server.completeHook = { call in
            switch call {
            case 1, 2: return HTTPResponse(status: 202, headers: ["Retry-After": "2"])
            case 3: return errorResponse(503, ErrorCode.processingFailed, retryable: true, retryAfter: 5)
            default: return nil
            }
        }
        _ = await h.engine().run()
        XCTAssertEqual(h.job()?.state, .synced)
        XCTAssertEqual(h.server.locked { $0.completeCount }, 4)
        XCTAssertEqual(h.clock.sleeps.filter { $0 == 2_000 }.count, 2)
        XCTAssertTrue(h.clock.sleeps.contains(5_000))
    }

    func testCompleteListingMissingChunksResendsThem() async throws {
        let h = UploadHarness()
        h.addPhoto()
        let server = h.server
        // Chunk 1's first PUT is acknowledged but lost.
        h.server.chunkHook = { _, index, attempt in
            index == 1 && attempt == 1 ? HTTPResponse(status: 204) : nil
        }
        _ = await h.engine().run()
        XCTAssertEqual(h.job()?.state, .synced)
        XCTAssertEqual(h.chunkPuts("up1").filter { $0 == 1 }.count, 2)
        XCTAssertEqual(server.locked { $0.completeCount }, 2)
    }

    func testRejectedPhotoFails() async throws {
        let h = UploadHarness()
        h.addPhoto()
        h.server.completeHook = { _ in errorResponse(422, ErrorCode.hashMismatch) }
        _ = await h.engine().run()
        XCTAssertEqual(h.job()?.state, .failed)
        XCTAssertTrue(h.changes.all.contains(.uploadRejected(localID: "IMG-1/L0/001", code: "HASH_MISMATCH")))
        let hex = try XCTUnwrap(h.job()?.sha256Hex)
        XCTAssertNil(h.store.read { $0.knownHashes[hex] })
    }

    func testSupersededUploadIsAnnouncedAgain() async throws {
        let h = UploadHarness()
        h.addPhoto()
        let server = h.server
        h.server.completeHook = { call in
            guard call == 1 else { return nil }
            // Another reservation took over: the old upload no longer matches the hash.
            server.locked { $0.uploads["up1"]?.sha = "superseded" }
            return errorResponse(409, ErrorCode.uploadSuperseded)
        }
        _ = await h.engine().run()
        XCTAssertEqual(h.job()?.state, .synced)
        XCTAssertEqual(h.job()?.uploadID, "up2")
        XCTAssertEqual(h.server.locked { $0.announceCount }, 2)
        XCTAssertEqual(h.chunkPuts("up2").sorted(), [0, 1, 2])
    }

    /// iOS suspended the app for longer than the 15-minute lease: re-announce, don't drop.
    func testExpiredLeaseIsAnnouncedAgain() async throws {
        let h = UploadHarness()
        h.addPhoto()
        h.server.chunkHook = { _, index, attempt in
            index == 0 && attempt == 1 ? errorResponse(410, ErrorCode.uploadExpired) : nil
        }
        _ = await h.engine().run()
        XCTAssertEqual(h.job()?.state, .synced)
        XCTAssertEqual(h.server.locked { $0.announceCount }, 2)
    }

    func testExpiredUploadTokenGetsAFreshOne() async throws {
        let h = UploadHarness()
        h.addPhoto()
        let server = h.server
        h.server.chunkHook = { _, index, attempt in
            guard index == 0 && attempt == 1 else { return nil }
            server.locked { $0.uploads["up1"]?.token = "rotated" }   // the old token no longer works
            return errorResponse(401, ErrorCode.tokenExpired)
        }
        _ = await h.engine().run()
        XCTAssertEqual(h.job()?.state, .synced)
        XCTAssertEqual(h.job()?.uploadID, "up1", "same reservation")
        let tokens = Set(h.server.locked { $0.chunkPuts.map(\.token) })
        XCTAssertTrue(tokens.contains("upload-token-2"))
    }

    func testTransientChunkErrorsAreRetried() async throws {
        let h = UploadHarness()
        h.addPhoto()
        h.server.chunkHook = { _, index, attempt in
            index == 1 && attempt <= 2 ? errorResponse(503, ErrorCode.busy, retryable: true, retryAfter: 1) : nil
        }
        _ = await h.engine().run()
        XCTAssertEqual(h.job()?.state, .synced)
        XCTAssertEqual(h.chunkPuts("up1").filter { $0 == 1 }.count, 3)
        XCTAssertEqual(h.clock.sleeps.filter { $0 == 1_000 }.count, 2)
    }

    /// Retries spent: the job stays queued with its copy; a later run, even in a new
    /// process with no upload token in memory, sends only what is missing.
    func testExhaustedRetriesDeferAndResumeAfterRestart() async throws {
        let h = UploadHarness()
        h.addPhoto()
        h.server.chunkHook = { _, index, _ in
            index == 1 ? errorResponse(503, ErrorCode.busy, retryable: true) : nil
        }
        _ = await h.engine().run()
        let deferred = try XCTUnwrap(h.job())
        XCTAssertEqual(deferred.state, .uploading)
        XCTAssertEqual(deferred.attempts, 1)
        XCTAssertNotNil(deferred.nextAttemptAtMs)
        XCTAssertEqual(h.chunkPuts("up1").filter { $0 == 1 }.count, 4, "1 try + 3 retries")
        XCTAssertTrue(FileManager.default.fileExists(atPath: h.files.uploadDirectory(key: deferred.key).path))

        // "Restart": a new engine, no tokens in memory, network back.
        h.server.chunkHook = nil
        h.clock.advance(deferred.nextAttemptAtMs! - h.clock.nowMs() + 1)
        let putsBefore = h.chunkPuts("up1").count
        _ = await h.engine().run()
        XCTAssertEqual(h.job()?.state, .synced)
        XCTAssertEqual(h.server.locked { $0.announceCount }, 2, "announce again for a fresh token")
        XCTAssertEqual(Array(h.chunkPuts("up1").dropFirst(putsBefore)), [1], "only the missing chunk")
    }

    func testSessionEndedStopsTheQueue() async throws {
        let h = UploadHarness()
        h.addPhoto()
        h.addPhoto("IMG-2/L0/001", seed: 2)
        h.server.announceHook = { _, _ in errorResponse(410, ErrorCode.sessionEnded) }
        let result = await h.engine().run()
        XCTAssertEqual(result, .sessionGone(.ended))
        XCTAssertEqual(h.server.locked { $0.announceCount }, 1)
        XCTAssertTrue(h.changes.all.contains(.sessionGone(.ended)))
    }

    func testRecalledAndNotMemberAnswers() async throws {
        let h = UploadHarness()
        h.addPhoto()
        h.server.announceHook = { _, _ in errorResponse(410, ErrorCode.photoRecalled) }
        _ = await h.engine().run()
        XCTAssertEqual(h.job()?.state, .skipped)

        let k = UploadHarness()
        k.addPhoto()
        k.server.announceHook = { _, _ in errorResponse(403, ErrorCode.notAMember) }
        let result = await k.engine().run()
        XCTAssertEqual(result, .sessionGone(.removed))
    }

    func testRateLimitDefersWithRetryAfter() async throws {
        let h = UploadHarness()
        h.addPhoto()
        h.server.announceHook = { _, _ in errorResponse(429, ErrorCode.rateLimited, retryable: true, retryAfter: 30) }
        let now = h.clock.nowMs()
        _ = await h.engine().run()
        XCTAssertEqual(h.job()?.state, .hashed)
        XCTAssertEqual(h.job()?.nextAttemptAtMs, now + 30_000)
    }

    func testPausedAndOfflineDoNothing() async throws {
        let h = UploadHarness()
        h.addPhoto()
        try h.store.write { $0.session?.paused = true }
        let paused = await h.engine().run()
        XCTAssertEqual(paused, .paused)
        try h.store.write { $0.session?.paused = false }
        h.network.set(.offline)
        let offline = await h.engine().run()
        XCTAssertEqual(offline, .offline)
        XCTAssertTrue(h.library.locked { $0.exportCalls.isEmpty })
    }

    func testDeletedAssetIsSkipped() async throws {
        let h = UploadHarness()
        try h.store.write { $0.uploadJobs["gone"] = UploadJob(id: "gone", sessionID: "sid1", takenAtMs: 1, nowMs: 1) }
        _ = await h.engine().run()
        XCTAssertEqual(h.job("gone")?.state, .skipped)
    }
}
