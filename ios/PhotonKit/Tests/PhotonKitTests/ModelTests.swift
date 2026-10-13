import Foundation
import SwiftProtobuf
import XCTest
@testable import PhotonKit

final class StreamIDTests: XCTestCase {
    func testComparesAsTuplesNotStrings() {
        XCTAssertLessThan(StreamID("9-0")!, StreamID("10-0")!)
        XCTAssertLessThan(StreamID("10-2")!, StreamID("10-10")!)
        XCTAssertEqual(StreamID("1700000000000")!, StreamID(ms: 1_700_000_000_000, seq: 0))
        XCTAssertEqual(StreamID("5-3")!.description, "5-3")
        XCTAssertNil(StreamID(""))
        XCTAssertNil(StreamID("a-1"))
        XCTAssertNil(StreamID("1-2-3"))
    }
}

final class JoinInputTests: XCTestCase {
    func testCodesAreNormalisedTheCrockfordWay() {
        XCTAssertEqual(JoinInput.parse("abcd2345", inviteHost: "photon.example"), .code("ABCD2345"))
        XCTAssertEqual(JoinInput.parse(" abcd-2345 ", inviteHost: "photon.example"), .code("ABCD2345"))
        XCTAssertEqual(JoinInput.parse("O1L1I0KM", inviteHost: "photon.example"), .code("011110KM"))
        XCTAssertNil(JoinInput.parse("ABC", inviteHost: "photon.example"))
        XCTAssertNil(JoinInput.parse("ABCD234U", inviteHost: "photon.example"))  // U is not Crockford
    }

    func testInviteLinks() {
        let token = "AbCdEfGhIjKlMnOpQrStUv"
        XCTAssertEqual(JoinInput.parse("https://photon.example/j/\(token)", inviteHost: "photon.example"), .inviteToken(token))
        XCTAssertNil(JoinInput.parse("https://evil.example/j/\(token)", inviteHost: "photon.example"))
        XCTAssertNil(JoinInput.parse("http://photon.example/j/\(token)", inviteHost: "photon.example"))
        XCTAssertNil(JoinInput.parse("https://photon.example/x/\(token)", inviteHost: "photon.example"))
        XCTAssertNil(JoinInput.parse("https://photon.example/j/short", inviteHost: "photon.example"))
    }
}

final class ConfigTests: XCTestCase {
    func testWebSocketURLAndCallbacks() {
        XCTAssertEqual(testConfig.webSocketURL.absoluteString, "wss://api.test/v1/ws")
        var dev = testConfig
        dev.apiBase = URL(string: "http://192.168.1.10:8000")!
        XCTAssertEqual(dev.webSocketURL.absoluteString, "ws://192.168.1.10:8000/v1/ws")
        XCTAssertEqual(testConfig.authCallback, .customScheme("photon"))
        var prod = testConfig
        prod.redirectURI = "https://photon.example/auth/cb"
        XCTAssertEqual(prod.authCallback, .https(host: "photon.example", path: "/auth/cb"))
    }
}

/// The generated code must match proto/photon.proto field for field: these bytes are what
/// the Python server reads and writes.
final class WireFormatTests: XCTestCase {
    func testHelloFrameEncoding() throws {
        var hello = PbHello()
        hello.accessToken = "t"
        hello.sessionID = "s"
        hello.lastEventID = "1-0"
        var frame = PbClientFrame()
        frame.hello = hello
        let bytes: Data = try frame.serializedBytes()
        // ClientFrame.hello = field 1 (len) containing Hello{1:"t", 2:"s", 3:"1-0"}.
        XCTAssertEqual([UInt8](bytes), [0x0A, 0x0B, 0x0A, 0x01, 0x74, 0x12, 0x01, 0x73, 0x1A, 0x03, 0x31, 0x2D, 0x30])
        var ping = PbClientFrame()
        ping.ping = 5
        XCTAssertEqual([UInt8](try ping.serializedBytes() as Data), [0x10, 0x05])
    }

    func testErrorAndAnnounceFieldNumbers() throws {
        var e = PbError()
        e.code = "X"
        e.retryable = true
        e.retryAfterS = 2
        e.missingChunks = [1, 3]
        // code=1, retryable=3, retry_after_s=4, missing_chunks=5 (packed).
        XCTAssertEqual([UInt8](try e.serializedBytes() as Data), [0x0A, 0x01, 0x58, 0x18, 0x01, 0x20, 0x02, 0x2A, 0x02, 0x01, 0x03])

        var a = PbAnnounceRequest()
        a.sessionID = "s"
        XCTAssertEqual([UInt8](try a.serializedBytes() as Data), [0x2A, 0x01, 0x73])   // session_id = 5

        var server = PbServerFrame()
        server.readyCursor = "7-1"
        let decoded = try PbServerFrame(serializedBytes: try server.serializedBytes() as Data)
        XCTAssertEqual(decoded.readyCursor, "7-1")
        XCTAssertEqual(PbAnnounceResponse.Result.inProgress.rawValue, 2)
        XCTAssertEqual(PbPhotoEvent.TypeEnum.sessionEnded.rawValue, 4)
        XCTAssertEqual(PbPhotoEvent.TypeEnum.hostChanged.rawValue, 5)
    }
}

final class ChunkPlanTests: XCTestCase {
    func testRangesAndMissing() {
        let plan = ChunkPlan(fileSize: 2_500_000, chunkSize: 1 << 20)
        XCTAssertEqual(plan.count, 3)
        XCTAssertEqual(plan.range(of: 0), 0..<1_048_576)
        XCTAssertEqual(plan.range(of: 2), 2_097_152..<2_500_000)
        XCTAssertEqual(plan.missing(received: [0, 2]), [1])
        XCTAssertEqual(plan.missing(received: []), [0, 1, 2])
        XCTAssertEqual(ChunkPlan(fileSize: 1, chunkSize: 1 << 20).count, 1)
        XCTAssertEqual(ChunkPlan(fileSize: 2 << 20, chunkSize: 1 << 20).count, 2)
    }

    func testChunkSizeChoice() {
        // No measurement: the announce hint.
        XCTAssertEqual(ChunkPlan.chooseChunkSize(fileSize: 10 << 20, measuredBytesPerSecond: nil, hint: 1 << 20), 1 << 20)
        // About 2 seconds per chunk at the measured rate, in 64 KiB steps.
        XCTAssertEqual(ChunkPlan.chooseChunkSize(fileSize: 10 << 20, measuredBytesPerSecond: 1_000_000, hint: nil), 1_966_080)
        // Clamped to [256 KiB, 8 MiB].
        XCTAssertEqual(ChunkPlan.chooseChunkSize(fileSize: 10 << 20, measuredBytesPerSecond: 10_000, hint: nil), 256 * 1024)
        XCTAssertEqual(ChunkPlan.chooseChunkSize(fileSize: 10 << 20, measuredBytesPerSecond: 100_000_000, hint: nil), 8 << 20)
        // Never more than 4096 chunks.
        var t = PhotonTuning()
        t.maxChunkCount = 4
        XCTAssertEqual(ChunkPlan.chooseChunkSize(fileSize: 4 << 20, measuredBytesPerSecond: nil, hint: 256 * 1024, tuning: t), 1 << 20)
    }

    func testThroughputEstimatorIgnoresTinySamples() {
        var est = ThroughputEstimator()
        est.record(bytes: 1000, milliseconds: 10)
        XCTAssertNil(est.bytesPerSecond)
        est.record(bytes: 1_000_000, milliseconds: 1000)
        XCTAssertEqual(est.bytesPerSecond, 1_000_000)
        est.record(bytes: 2_000_000, milliseconds: 1000)
        XCTAssertEqual(est.bytesPerSecond!, 1_300_000, accuracy: 1)
    }

    func testChunkFilesSliceAndChecksum() throws {
        let dir = tempDirectory()
        let source = dir.appendingPathComponent("copy")
        let data = testBytes(300_000)
        try data.write(to: source)
        let dest = dir.appendingPathComponent("chunks/1")
        let (crc, length) = try ChunkFiles.prepare(source: source, range: 100_000..<200_000, destination: dest)
        let slice = data.subdata(in: 100_000..<200_000)
        XCTAssertEqual(length, 100_000)
        XCTAssertEqual(try Data(contentsOf: dest), slice)
        XCTAssertEqual(crc, CRC32C.headerValue(CRC32C.checksum(slice)))
    }
}

final class FileStoreTests: XCTestCase {
    func testPersistsAcrossInstances() throws {
        let url = tempDirectory().appendingPathComponent("state.json")
        let a = FileStore(fileURL: url)
        try a.write { s in
            s.session = makeSession()
            s.uploadJobs["L1"] = UploadJob(id: "L1", sessionID: "sid1", takenAtMs: 5, nowMs: 6)
            s.settings.stripLocation = false
        }
        let b = FileStore(fileURL: url)
        XCTAssertEqual(b.read { $0.session?.sessionID }, "sid1")
        XCTAssertEqual(b.read { $0.uploadJobs["L1"]?.takenAtMs }, 5)
        XCTAssertEqual(b.read { $0.settings.stripLocation }, false)
    }

    func testCorruptFileIsMovedAside() throws {
        let url = tempDirectory().appendingPathComponent("state.json")
        try Data("not json".utf8).write(to: url)
        let store = FileStore(fileURL: url)
        XCTAssertNil(store.read { $0.session })
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.appendingPathExtension("corrupt").path))
    }

    func testFailedWriteLeavesStateUnchanged() {
        let store = FileStore(fileURL: nil)
        struct Boom: Error {}
        XCTAssertThrowsError(try store.write { s -> Void in
            s.session = makeSession()
            throw Boom()
        })
        XCTAssertNil(store.read { $0.session })
    }
}

final class ReceivePolicyTests: XCTestCase {
    func testDecisionsByNetwork() {
        let always = ReceivePolicy(originals: .always)
        let wifiOnly = ReceivePolicy(originals: .wifiOrCharging)
        let lowData = NetworkStatus(isConnected: true, isExpensive: true, isConstrained: true, isCharging: false)
        let cellCharging = NetworkStatus(isConnected: true, isExpensive: true, isConstrained: false, isCharging: true)

        XCTAssertTrue(always.fetchThumbnail(.cellular))
        XCTAssertFalse(always.fetchThumbnail(.offline))

        XCTAssertTrue(always.fetchPreview(.wifi, measuredBitsPerSecond: nil))
        XCTAssertFalse(always.fetchPreview(.cellular, measuredBitsPerSecond: nil))
        XCTAssertFalse(always.fetchPreview(.cellular, measuredBitsPerSecond: 4_000_000))
        XCTAssertTrue(always.fetchPreview(.cellular, measuredBitsPerSecond: 6_000_000))
        XCTAssertFalse(always.fetchPreview(lowData, measuredBitsPerSecond: 50_000_000))

        XCTAssertTrue(always.fetchOriginal(.cellular))
        XCTAssertFalse(always.fetchOriginal(lowData))
        XCTAssertTrue(wifiOnly.fetchOriginal(.wifi))
        XCTAssertFalse(wifiOnly.fetchOriginal(.cellular))
        XCTAssertTrue(wifiOnly.fetchOriginal(cellCharging))
        XCTAssertFalse(wifiOnly.fetchOriginal(.offline))
    }
}

final class EligibilityTests: XCTestCase {
    func testReconcileFilters() async throws {
        let store = FileStore(fileURL: nil)
        let session = makeSession(joinTimeMs: 1000)
        try store.write { s in
            s.session = session
            s.receivedAssets["saved-by-us"] = ReceivedAsset(localID: "saved-by-us", sha256Hex: "aa", sessionID: "sid1", recordedAtMs: 0)
            s.uploadJobs["queued"] = UploadJob(id: "queued", sessionID: "sid1", takenAtMs: 1500, nowMs: 0)
        }
        let library = FakeLibrary()
        library.add("before-join", data: Data([1]), createdAtMs: 999)
        library.add("at-join", data: Data([1]), createdAtMs: 1000)
        library.add("screenshot", data: Data([1]), createdAtMs: 2000, screenshot: true)
        library.add("saved-by-us", data: Data([1]), createdAtMs: 2000)
        library.add("in-album", data: Data([1]), createdAtMs: 2000)
        library.add("queued", data: Data([1]), createdAtMs: 2000)
        library.add("new", data: Data([1]), createdAtMs: 3000)
        library.album = ["in-album"]
        library.assets.append(LibraryAsset(localID: "no-date", creationDateMs: nil, isScreenshot: false))

        let reconciler = Reconciler(store: store, library: library, clock: TestClock())
        let added = try await reconciler.scan()
        XCTAssertEqual(added, 2)
        XCTAssertEqual(Set(store.read { $0.uploadJobs.keys }), ["queued", "at-join", "new"])
        XCTAssertEqual(store.read { $0.uploadJobs["new"]?.state }, .pending)
        XCTAssertEqual(store.read { $0.session?.scanCursorMs }, 3000)
        // Idempotent.
        let again = try await reconciler.scan()
        XCTAssertEqual(again, 0)

        // Paused: nothing is queued.
        try store.write { $0.session?.paused = true }
        library.add("while-paused", data: Data([1]), createdAtMs: 4000)
        let whilePaused = try await reconciler.scan()
        XCTAssertEqual(whilePaused, 0)
    }
}
