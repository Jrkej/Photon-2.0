import Foundation
import XCTest
@testable import PhotonKit

/// Store, uploads, receiver and processor over a scriptable transport.
final class ReceiveHarness: @unchecked Sendable {
    let clock: PhotonClock
    let secure = MemorySecureStore()
    let store = FileStore(fileURL: nil)
    let library = FakeLibrary()
    let network = FakeNetwork()
    let changes = ChangeLog()
    let files: FileLayout
    let transport: FakeTransport
    let api: APIClient
    let uploads: UploadEngine
    let receiver: ReceivePipeline
    let processor: EventProcessor
    var tuning: PhotonTuning

    init(clock: PhotonClock = TestClock(), tuning: PhotonTuning = PhotonTuning(),
         handler: @escaping FakeTransport.Handler = { _ in errorResponse(404, "NOT_ROUTED") }) {
        self.clock = clock
        self.tuning = tuning
        let root = tempDirectory()
        files = FileLayout(dataRoot: root.appendingPathComponent("data"), cacheRoot: root.appendingPathComponent("cache"))
        transport = FakeTransport(handler)
        secure.seedTokens()
        api = APIClient(config: testConfig, transport: transport, secureStore: secure, clock: clock)
        uploads = UploadEngine(api: api, store: store, library: library, uploader: HTTPChunkUploader(transport: transport),
                               files: files, network: network, clock: clock, tuning: tuning, onChange: changes.handler)
        receiver = ReceivePipeline(api: api, store: store, library: library, files: files, network: network, clock: clock,
                                   tuning: tuning, onChange: changes.handler)
        processor = EventProcessor(store: store, uploads: uploads, receiver: receiver, clock: clock, onChange: changes.handler)
        try! store.write { $0.session = makeSession() }
    }

    var lastEventID: String? { store.read { $0.session?.lastEventID } }
}

final class EventProcessorTests: XCTestCase {
    let shaA = SHA256Hasher.hash(Data("A".utf8))
    let shaB = SHA256Hasher.hash(Data("B".utf8))
    let shaC = SHA256Hasher.hash(Data("C".utf8))

    func testDedupeByEventIDAndByHash() async throws {
        let h = ReceiveHarness()
        let p = h.processor
        let first = await p.handle(storedEvent(id: "5-0", sha: shaA))
        XCTAssertEqual(first, .handled)
        XCTAssertEqual(h.store.read { $0.receivedPhotos[self.shaA.hexString]?.state }, .pending)
        XCTAssertEqual(h.lastEventID, "5-0")

        let again = await p.handle(storedEvent(id: "5-0", sha: shaA))
        XCTAssertEqual(again, .duplicate)
        let older = await p.handle(storedEvent(id: "4-9", sha: shaB))
        XCTAssertEqual(older, .duplicate)
        XCTAssertNil(h.store.read { $0.receivedPhotos[self.shaB.hexString] })

        // A new id for a hash already held: the cursor moves, nothing new is received.
        let sameHash = await p.handle(storedEvent(id: "6-0", sha: shaA))
        XCTAssertEqual(sameHash, .handled)
        XCTAssertEqual(h.store.read { $0.receivedPhotos.count }, 1)

        // Ids compare numerically: 10-0 comes after 6-0.
        _ = await p.handle(storedEvent(id: "10-0", sha: shaC))
        XCTAssertEqual(h.lastEventID, "10-0")
        XCTAssertNotNil(h.store.read { $0.receivedPhotos[self.shaC.hexString] })
    }

    func testOwnUploadAndLocallyHeldHashesAreNotReceived() async throws {
        let h = ReceiveHarness()
        _ = await h.processor.handle(storedEvent(id: "1-0", sha: shaA, uploader: "me"))
        XCTAssertNil(h.store.read { $0.receivedPhotos[self.shaA.hexString] })
        XCTAssertEqual(h.store.read { $0.knownHashes[self.shaA.hexString]?.source }, .uploaded)

        // A local job waiting on the same bytes (IN_PROGRESS) is synced by the event, and
        // the photo is not downloaded: this device has it.
        try h.store.write { s in
            var job = UploadJob(id: "L1", sessionID: "sid1", takenAtMs: 1, nowMs: 1)
            job.state = .waiting
            job.sha256Hex = self.shaB.hexString
            s.uploadJobs["L1"] = job
        }
        _ = await h.processor.handle(storedEvent(id: "2-0", sha: shaB, uploader: "alice"))
        XCTAssertEqual(h.store.read { $0.uploadJobs["L1"]?.state }, .synced)
        XCTAssertNil(h.store.read { $0.receivedPhotos[self.shaB.hexString] })
    }

    func testExpiredAndForeignSessionEventsAreIgnored() async throws {
        let h = ReceiveHarness()
        let now = h.clock.nowMs()
        _ = await h.processor.handle(storedEvent(id: "1-0", sha: shaA, expiresAtMs: now - 1))
        XCTAssertNil(h.store.read { $0.receivedPhotos[self.shaA.hexString] })
        let foreign = await h.processor.handle(storedEvent(id: "2-0", sha: shaB, sessionID: "other"))
        XCTAssertEqual(foreign, .duplicate)
        XCTAssertEqual(h.lastEventID, "1-0")
    }

    func testRecallDeletesCachedCopiesButNotSavedOriginals() async throws {
        let h = ReceiveHarness()
        let a = shaA.hexString, b = shaB.hexString
        try h.files.writeCached(Data([1]), relativePath: FileLayout.thumbPath(a))
        try h.files.writeCached(Data([2]), relativePath: FileLayout.previewPath(a))
        try h.files.writeCached(Data([3]), relativePath: FileLayout.thumbPath(b))
        try h.store.write { s in
            var pa = ReceivedPhoto(sha256Hex: a, sessionID: "sid1", uploaderID: "alice", size: 1, takenAtMs: 1, expiresAtMs: .max, receivedAtMs: 1)
            pa.state = .preview
            pa.thumbPath = FileLayout.thumbPath(a)
            pa.previewPath = FileLayout.previewPath(a)
            var pb = ReceivedPhoto(sha256Hex: b, sessionID: "sid1", uploaderID: "alice", size: 1, takenAtMs: 1, expiresAtMs: .max, receivedAtMs: 1)
            pb.state = .saved
            pb.savedLocalID = "asset-b"
            pb.thumbPath = FileLayout.thumbPath(b)
            s.receivedPhotos[a] = pa
            s.receivedPhotos[b] = pb
        }
        var recall = PbPhotoEvent()
        recall.eventID = "3-0"
        recall.type = .photoRecalled
        recall.sha256 = shaA
        _ = await h.processor.handle(recall)
        recall.eventID = "4-0"
        recall.sha256 = shaB
        _ = await h.processor.handle(recall)

        XCTAssertEqual(h.store.read { $0.receivedPhotos[a]?.state }, .recalled)
        XCTAssertFalse(FileManager.default.fileExists(atPath: h.files.cacheURL(relativePath: FileLayout.thumbPath(a)).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: h.files.cacheURL(relativePath: FileLayout.previewPath(a)).path))
        XCTAssertEqual(h.store.read { $0.receivedPhotos[b]?.state }, .saved, "the saved original is the user's now")
        XCTAssertFalse(FileManager.default.fileExists(atPath: h.files.cacheURL(relativePath: FileLayout.thumbPath(b)).path))
    }

    func testMembershipAndSessionEndEvents() async throws {
        let h = ReceiveHarness()
        var e = PbPhotoEvent()
        e.eventID = "1-0"
        e.type = .memberJoined
        e.memberID = "carol"
        _ = await h.processor.handle(e)
        XCTAssertEqual(h.store.read { $0.session?.memberIDs }, ["host", "me", "carol"])
        e.eventID = "2-0"
        e.type = .memberLeft
        _ = await h.processor.handle(e)
        XCTAssertEqual(h.store.read { $0.session?.memberIDs }, ["host", "me"])
        e.eventID = "3-0"
        e.memberID = "me"
        let kicked = await h.processor.handle(e)
        XCTAssertEqual(kicked, .sessionGone(.removed))
        e.eventID = "4-0"
        e.type = .sessionEnded
        let ended = await h.processor.handle(e)
        XCTAssertEqual(ended, .sessionGone(.ended))
        XCTAssertEqual(h.lastEventID, "4-0")
    }

    func testHostChangedUpdatesRole() async throws {
        let h = ReceiveHarness()
        XCTAssertEqual(h.store.read { $0.session?.role }, .member)
        var e = PbPhotoEvent()
        e.eventID = "1-0"
        e.type = .hostChanged
        e.memberID = "me"
        let outcome = await h.processor.handle(e)
        XCTAssertEqual(outcome, .handled)
        XCTAssertEqual(h.store.read { $0.session?.role }, .host)
        XCTAssertEqual(h.store.read { $0.session?.hostID }, "me")
        XCTAssertEqual(h.store.read { $0.session?.isHost }, true)

        e.eventID = "2-0"
        e.memberID = "alice"
        _ = await h.processor.handle(e)
        XCTAssertEqual(h.store.read { $0.session?.role }, .member)
        XCTAssertEqual(h.store.read { $0.session?.hostID }, "alice")
        XCTAssertEqual(h.lastEventID, "2-0")
    }

    /// A type from a newer server (wire value 9) is skipped, the cursor still advances.
    func testUnknownFutureEventTypeIsSkippedSafely() async throws {
        let h = ReceiveHarness()
        // Field 1 event_id "3-0", field 2 type 9, field 4 sha256 of "A".
        var bytes: [UInt8] = [0x0A, 0x03, 0x33, 0x2D, 0x30, 0x10, 0x09, 0x22, 0x20]
        bytes += [UInt8](shaA)
        let event = try PbPhotoEvent(serializedBytes: Data(bytes))
        XCTAssertEqual(event.type, .UNRECOGNIZED(9))
        let outcome = await h.processor.handle(event)
        XCTAssertEqual(outcome, .handled)
        XCTAssertEqual(h.lastEventID, "3-0")
        XCTAssertTrue(h.store.read { $0.receivedPhotos.isEmpty })
        XCTAssertNotNil(h.store.read { $0.session })
    }

    func testSnapshotReplacesCursorAndAppliesState() async throws {
        let h = ReceiveHarness()
        try h.store.write { $0.session?.lastEventID = "50-0" }
        let c = shaC.hexString
        try h.store.write { s in
            s.receivedPhotos[c] = ReceivedPhoto(sha256Hex: c, sessionID: "sid1", uploaderID: "x", size: 1, takenAtMs: 1,
                                                expiresAtMs: .max, receivedAtMs: 1)
        }
        var snap = PbSnapshot()
        snap.cursor = "42-0"
        var live = storedEvent(id: "", sha: shaA)
        live.eventID = ""
        snap.livePhotos = [live, storedEvent(id: "", sha: shaB, uploader: "me")]
        snap.recalledSha256 = [shaC]
        snap.memberIds = ["host", "me", "alice"]
        await h.processor.apply(snapshot: snap)

        XCTAssertEqual(h.lastEventID, "42-0", "the server's cursor wins, even if lower")
        XCTAssertEqual(h.store.read { $0.session?.memberIDs }, ["host", "me", "alice"])
        XCTAssertEqual(h.store.read { $0.receivedPhotos[self.shaA.hexString]?.state }, .pending)
        XCTAssertNil(h.store.read { $0.receivedPhotos[self.shaB.hexString] })
        XCTAssertEqual(h.store.read { $0.receivedPhotos[c]?.state }, .recalled)
    }
}

final class ReceivePipelineTests: XCTestCase {
    let original = testBytes(50_000, seed: 9)
    var hex: String { SHA256Hasher.hash(original).hexString }

    private func harness(_ override: (@Sendable (HTTPRequest) -> HTTPResponse?)? = nil) -> ReceiveHarness {
        let original = self.original
        let h = ReceiveHarness { request in
            if let r = override?(request) { return r }
            let size = URLComponents(url: request.url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "size" }?.value
            switch size {
            case "thumb": return HTTPResponse(status: 200, headers: ["Content-Type": "image/webp"], body: Data("T".utf8))
            case "preview": return HTTPResponse(status: 200, headers: ["Content-Type": "image/webp"], body: Data("P".utf8))
            case "full": return HTTPResponse(status: 200, headers: ["Content-Type": "image/heic"], body: original)
            default: return errorResponse(404, "NOT_ROUTED")
            }
        }
        let hex = self.hex
        try! h.store.write { s in
            s.receivedPhotos[hex] = ReceivedPhoto(sha256Hex: hex, sessionID: "sid1", uploaderID: "alice", size: 50_000,
                                                  takenAtMs: 1_690_000_000_000, expiresAtMs: h.clock.nowMs() + 1_800_000,
                                                  receivedAtMs: h.clock.nowMs())
        }
        return h
    }

    private func sizes(_ h: ReceiveHarness) -> [String] {
        h.transport.requests.compactMap { URLComponents(url: $0.url, resolvingAgainstBaseURL: false)?.queryItems?.first?.value }
    }

    func testThumbThenPreviewThenOriginalOnWiFi() async throws {
        let h = harness()
        let hex = self.hex
        let store = h.store
        let recordedInsideChangeBlock = Counter()
        h.library.duringSave = { localID in
            // Echo layers 1 and 4 are in place before the asset becomes visible.
            if store.read({ $0.receivedAssets[localID] != nil && $0.knownHashes[hex]?.source == .received }) {
                recordedInsideChangeBlock.increment()
            }
        }
        let result = await h.receiver.run()
        XCTAssertEqual(result, .finished)
        XCTAssertEqual(sizes(h), ["thumb", "preview", "full"])
        XCTAssertEqual(h.transport.requests[0].url.path, "/v1/sessions/sid1/photos/\(hex)")
        XCTAssertEqual(h.transport.requests[0].headers["Authorization"], "Bearer access-1")

        let photo = try XCTUnwrap(h.store.read { $0.receivedPhotos[hex] })
        XCTAssertEqual(photo.state, .saved)
        XCTAssertEqual(try Data(contentsOf: h.files.cacheURL(relativePath: photo.thumbPath!)), Data("T".utf8))
        XCTAssertEqual(try Data(contentsOf: h.files.cacheURL(relativePath: photo.previewPath!)), Data("P".utf8))
        let saved = try XCTUnwrap(h.library.saved.first)
        XCTAssertEqual(h.library.saved.count, 1, "only the original goes to the library")
        XCTAssertEqual(saved.data, original)
        XCTAssertEqual(saved.mime, "image/heic")
        XCTAssertEqual(saved.takenAtMs, 1_690_000_000_000, "original capture time kept")
        XCTAssertEqual(photo.savedLocalID, saved.localID)
        XCTAssertEqual(recordedInsideChangeBlock.value, 1)
        XCTAssertEqual((try? FileManager.default.contentsOfDirectory(atPath: h.files.downloadsDirectory.path))?.count ?? 0, 0)
    }

    func testCellularAndWiFiOnlySettingDeferOriginalUntilCharging() async throws {
        let h = harness()
        h.network.set(.cellular)
        try h.store.write { $0.settings.originals = .wifiOrCharging }
        _ = await h.receiver.run()
        XCTAssertEqual(sizes(h), ["thumb"], "no preview without a fast or unmetered link, original deferred")
        XCTAssertEqual(h.store.read { $0.receivedPhotos[self.hex]?.state }, .thumb)

        h.network.set(NetworkStatus(isConnected: true, isExpensive: true, isConstrained: false, isCharging: true))
        _ = await h.receiver.run()
        XCTAssertEqual(sizes(h), ["thumb", "full"])
        XCTAssertEqual(h.store.read { $0.receivedPhotos[self.hex]?.state }, .saved)
    }

    func testPreviewFailureKeepsThumbnail() async throws {
        let h = harness { request in
            request.url.query == "size=preview" ? errorResponse(503, ErrorCode.processingFailed, retryable: true) : nil
        }
        _ = await h.receiver.run()
        let photo = try XCTUnwrap(h.store.read { $0.receivedPhotos[self.hex] })
        XCTAssertTrue(photo.previewUnavailable)
        XCTAssertNotNil(photo.thumbPath)
        XCTAssertEqual(photo.state, .saved)
        XCTAssertEqual(sizes(h), ["thumb", "preview", "full"])
    }

    func testRecalledWhileDownloading() async throws {
        let h = harness { request in
            request.url.query == "size=full" ? errorResponse(410, ErrorCode.photoRecalled) : nil
        }
        _ = await h.receiver.run()
        let photo = try XCTUnwrap(h.store.read { $0.receivedPhotos[self.hex] })
        XCTAssertEqual(photo.state, .recalled)
        XCTAssertNil(photo.thumbPath)
        XCTAssertTrue(h.library.saved.isEmpty)
    }

    func testCorruptOriginalIsNeverSaved() async throws {
        let h = harness { request in
            request.url.query == "size=full" ? HTTPResponse(status: 200, headers: ["Content-Type": "image/jpeg"], body: Data("bad".utf8)) : nil
        }
        _ = await h.receiver.run()
        let photo = try XCTUnwrap(h.store.read { $0.receivedPhotos[self.hex] })
        XCTAssertTrue(h.library.saved.isEmpty)
        XCTAssertEqual(photo.attempts, 1)
        XCTAssertNotNil(photo.nextAttemptAtMs)
        XCTAssertNil(h.store.read { $0.knownHashes[self.hex] })
    }

    func testExpiredPhotoDropsCachesWithoutRequests() async throws {
        let h = harness()
        let hex = self.hex
        try h.files.writeCached(Data([1]), relativePath: FileLayout.thumbPath(hex))
        try h.store.write { s in
            s.receivedPhotos[hex]?.state = .thumb
            s.receivedPhotos[hex]?.thumbPath = FileLayout.thumbPath(hex)
            s.receivedPhotos[hex]?.expiresAtMs = h.clock.nowMs()
        }
        _ = await h.receiver.run()
        XCTAssertTrue(h.transport.requests.isEmpty)
        XCTAssertEqual(h.store.read { $0.receivedPhotos[hex]?.state }, .expired)
        XCTAssertFalse(FileManager.default.fileExists(atPath: h.files.cacheURL(relativePath: FileLayout.thumbPath(hex)).path))
    }

    func testSessionEndedStopsReceiving() async throws {
        let h = harness { _ in errorResponse(410, ErrorCode.sessionEnded) }
        let result = await h.receiver.run()
        XCTAssertEqual(result, .sessionGone(.ended))
        XCTAssertTrue(h.changes.all.contains(.sessionGone(.ended)))
    }
}
