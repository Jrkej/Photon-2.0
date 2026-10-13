import Foundation
import SwiftProtobuf
import XCTest
@testable import PhotonKit

// MARK: - Clock

/// Virtual time: sleeps return at once and advance the clock, so 2-minute waits cost nothing.
final class TestClock: PhotonClock, @unchecked Sendable {
    private let lock = NSLock()
    private var now: Int64
    private var _sleeps: [Int64] = []

    init(start: Int64 = 1_700_000_000_000) { now = start }

    func nowMs() -> Int64 { lock.lock(); defer { lock.unlock() }; return now }
    func advance(_ ms: Int64) { lock.lock(); now += ms; lock.unlock() }
    var sleeps: [Int64] { lock.lock(); defer { lock.unlock() }; return _sleeps }

    func sleep(ms: Int64) async throws {
        try Task.checkCancellation()
        lock.withLockValue { _sleeps.append(ms); now += max(ms, 0) }
        await Task.yield()
    }
}

// MARK: - Keychain

final class MemorySecureStore: SecureStore, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: Data] = [:]

    func data(for key: String) throws -> Data? { lock.lock(); defer { lock.unlock() }; return values[key] }
    func set(_ data: Data, for key: String) throws { lock.lock(); values[key] = data; lock.unlock() }
    func remove(_ key: String) throws { lock.lock(); values[key] = nil; lock.unlock() }

    /// Pre-loads a token pair as if the user had logged in.
    func seedTokens(access: String = "access-1", refresh: String = "refresh-1", expiresAtMs: Int64 = 0, userID: String = "me") {
        let stored = TokenManager.Stored(access: access, refresh: refresh, accessExpiresAtMs: expiresAtMs, userID: userID)
        try! set(JSONEncoder().encode(stored), for: TokenManager.storeKey)
    }
}

// MARK: - HTTP

final class FakeTransport: HTTPTransport, @unchecked Sendable {
    typealias Handler = @Sendable (HTTPRequest) async throws -> HTTPResponse
    private let lock = NSLock()
    private var handler: Handler
    private var _requests: [HTTPRequest] = []

    init(_ handler: @escaping Handler) { self.handler = handler }

    var requests: [HTTPRequest] { lock.lock(); defer { lock.unlock() }; return _requests }
    func setHandler(_ h: @escaping Handler) { lock.lock(); handler = h; lock.unlock() }

    func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        let h = lock.withLockValue { () -> Handler in
            _requests.append(request)
            return handler
        }
        return try await h(request)
    }

    func requests(path: String) -> [HTTPRequest] { requests.filter { $0.url.path == path } }
}

func pbResponse<M: SwiftProtobuf.Message>(_ message: M, status: Int = 200, headers: [String: String] = [:]) -> HTTPResponse {
    var h = headers
    h["Content-Type"] = protobufContentType
    return HTTPResponse(status: status, headers: h, body: try! message.serializedBytes())
}

func errorResponse(_ status: Int, _ code: String, retryable: Bool = false, retryAfter: UInt32 = 0,
                   missing: [UInt32] = []) -> HTTPResponse {
    var e = PbError()
    e.code = code
    e.retryable = retryable
    e.retryAfterS = retryAfter
    e.missingChunks = missing
    return pbResponse(e, status: status)
}

func body<M: SwiftProtobuf.Message>(_ request: HTTPRequest, as: M.Type) -> M {
    guard case .data(let d) = request.body else { XCTFail("no body"); return M() }
    return try! M(serializedBytes: d)
}

func fileBody(_ request: HTTPRequest) -> Data {
    guard case .file(let url) = request.body else { return Data() }
    return (try? Data(contentsOf: url)) ?? Data()
}

let testConfig = PhotonConfig(apiBase: URL(string: "https://api.test")!, imageBase: URL(string: "https://img.test")!,
                              authProvider: "dev", redirectURI: "photon://auth/cb", inviteHost: "photon.example")

// MARK: - Simulated orchestrator + image server

/// Enough of the server to drive the upload engine: announce, chunk PUT with CRC and token
/// checks, status and complete with real SHA-256 verification. Hooks override answers.
final class FakeServer: @unchecked Sendable {
    struct Upload {
        var id: String
        var sha: String
        var size: Int64
        var token: String
        var chunks: [Int: Data] = [:]
        var stored = false
    }

    private let lock = NSLock()
    var uploads: [String: Upload] = [:]
    var storedHashes: Set<String> = []
    var announceCount = 0
    var completeCount = 0
    var chunkPuts: [(upload: String, index: Int, token: String)] = []
    var tokenSerial = 0
    var inFlight = 0
    var maxInFlight = 0
    var completeChunkCounts: [UInt32] = []

    /// Return a response to override the default announce behaviour.
    var announceHook: (@Sendable (_ call: Int, _ request: PbAnnounceRequest) -> HTTPResponse?)?
    var chunkHook: (@Sendable (_ upload: String, _ index: Int, _ attempt: Int) -> HTTPResponse?)?
    var completeHook: (@Sendable (_ call: Int) -> HTTPResponse?)?
    var statusHook: (@Sendable (_ upload: String) -> HTTPResponse?)?
    private var chunkAttempts: [String: Int] = [:]

    func locked<T>(_ body: (FakeServer) -> T) -> T { lock.lock(); defer { lock.unlock() }; return body(self) }

    func handle(_ request: HTTPRequest) async -> HTTPResponse {
        let path = request.url.path
        if request.url.host == "api.test", path == "/v1/photos/announce" {
            return announce(body(request, as: PbAnnounceRequest.self))
        }
        guard request.url.host == "img.test", path.hasPrefix("/v1/uploads/") else {
            return errorResponse(404, "NOT_ROUTED")
        }
        let parts = path.split(separator: "/").map(String.init)   // v1 uploads {id} [chunks n | complete]
        let id = parts[2]
        let token = request.headers["Authorization"]?.replacingOccurrences(of: "Bearer ", with: "") ?? ""
        if parts.count == 5, parts[3] == "chunks", let n = Int(parts[4]) {
            return await chunk(id: id, index: n, token: token, request: request)
        }
        if parts.count == 4, parts[3] == "complete" {
            return complete(id: id, token: token, request: body(request, as: PbCompleteRequest.self))
        }
        if parts.count == 3, request.method == "GET" {
            if let r = statusHook?(id) { return r }
            return locked { s in
                guard let u = s.uploads[id] else { return errorResponse(404, ErrorCode.unknownUpload) }
                var st = PbUploadStatus()
                st.uploadID = id
                st.state = u.stored ? "STORED" : "RECEIVING"
                st.receivedChunks = u.chunks.keys.sorted().map(UInt32.init)
                st.receivedBytes = UInt64(u.chunks.values.reduce(0) { $0 + $1.count })
                st.serverHasFile = s.storedHashes.contains(u.sha)
                return pbResponse(st)
            }
        }
        return errorResponse(404, "NOT_ROUTED")
    }

    private func announce(_ r: PbAnnounceRequest) -> HTTPResponse {
        let (call, hook) = locked { s -> (Int, (@Sendable (Int, PbAnnounceRequest) -> HTTPResponse?)?) in
            s.announceCount += 1
            return (s.announceCount, s.announceHook)
        }
        if let response = hook?(call, r) { return response }
        return locked { s in
            let hex = r.sha256.hexString
            var resp = PbAnnounceResponse()
            if s.storedHashes.contains(hex) {
                resp.result = .alreadyUploaded
                return pbResponse(resp)
            }
            s.tokenSerial += 1
            let token = "upload-token-\(s.tokenSerial)"
            let existing = s.uploads.values.first { $0.sha == hex && !$0.stored }
            let id = existing?.id ?? "up\(s.uploads.count + 1)"
            if existing == nil {
                s.uploads[id] = Upload(id: id, sha: hex, size: Int64(r.size), token: token)
            } else {
                s.uploads[id]?.token = token
            }
            resp.result = .upload
            resp.uploadID = id
            resp.uploadToken = token
            resp.uploadURL = "https://img.test/v1/uploads/\(id)"
            resp.chunkSizeHint = 1 << 20
            return pbResponse(resp)
        }
    }

    private func chunk(id: String, index: Int, token: String, request: HTTPRequest) async -> HTTPResponse {
        let (attempt, hook) = locked { s -> (Int, (@Sendable (String, Int, Int) -> HTTPResponse?)?) in
            let key = "\(id)/\(index)"
            s.chunkAttempts[key, default: 0] += 1
            s.chunkPuts.append((id, index, token))
            s.inFlight += 1
            s.maxInFlight = max(s.maxInFlight, s.inFlight)
            return (s.chunkAttempts[key]!, s.chunkHook)
        }
        // Let other parallel chunks start, so maxInFlight measures real overlap.
        for _ in 0..<5 { await Task.yield() }
        defer { locked { $0.inFlight -= 1 } }
        if let response = hook?(id, index, attempt) { return response }
        let data = fileBody(request)
        let crc = CRC32C.headerValue(CRC32C.checksum(data))
        return locked { s in
            guard var u = s.uploads[id] else { return errorResponse(404, ErrorCode.unknownUpload) }
            guard u.token == token else { return errorResponse(401, ErrorCode.unauthenticated) }
            guard request.headers["X-Chunk-CRC32C"] == crc else {
                return errorResponse(400, ErrorCode.invalidRequest, retryable: true)
            }
            u.chunks[index] = data
            s.uploads[id] = u
            return HTTPResponse(status: 204)
        }
    }

    private func complete(id: String, token: String, request: PbCompleteRequest) -> HTTPResponse {
        let (call, hook) = locked { s -> (Int, (@Sendable (Int) -> HTTPResponse?)?) in
            s.completeCount += 1
            s.completeChunkCounts.append(request.chunkCount)
            return (s.completeCount, s.completeHook)
        }
        if let response = hook?(call) { return response }
        return locked { s in
            guard var u = s.uploads[id] else { return errorResponse(404, ErrorCode.unknownUpload) }
            guard u.token == token else { return errorResponse(401, ErrorCode.unauthenticated) }
            if !s.storedHashes.contains(u.sha) {
                let missing = (0..<Int(request.chunkCount)).filter { u.chunks[$0] == nil }
                if !missing.isEmpty {
                    return errorResponse(400, ErrorCode.invalidRequest, missing: missing.map(UInt32.init))
                }
                var assembled = Data()
                for i in 0..<Int(request.chunkCount) { assembled.append(u.chunks[i]!) }
                guard SHA256Hasher.hash(assembled).hexString == u.sha, Int64(assembled.count) == u.size else {
                    return errorResponse(422, ErrorCode.hashMismatch)
                }
            }
            u.stored = true
            s.uploads[id] = u
            s.storedHashes.insert(u.sha)
            var r = PbCompleteResponse()
            r.state = "STORED"
            r.sha256 = Data(hexString: u.sha)!
            return pbResponse(r)
        }
    }
}

// MARK: - Photo library

final class FakeLibrary: PhotoLibrary, @unchecked Sendable {
    private let lock = NSLock()
    var assets: [LibraryAsset] = []
    var album: Set<String> = []
    var contents: [String: (Data, String)] = [:]
    var exportCalls: [(id: String, strip: Bool)] = []
    var saved: [(data: Data, mime: String, takenAtMs: Int64, localID: String)] = []
    var saveError: Error?
    /// Runs inside the "change block", after recordLocalID, to check what was recorded.
    var duringSave: (@Sendable (String) -> Void)?

    func locked<T>(_ body: (FakeLibrary) -> T) -> T { lock.lock(); defer { lock.unlock() }; return body(self) }

    func add(_ id: String, data: Data, mime: String = "image/jpeg", createdAtMs: Int64, screenshot: Bool = false) {
        locked { l in
            l.assets.append(LibraryAsset(localID: id, creationDateMs: createdAtMs, isScreenshot: screenshot))
            l.contents[id] = (data, mime)
        }
    }

    func candidateAssets(createdAtOrAfterMs: Int64) async throws -> [LibraryAsset] {
        locked { $0.assets.filter { ($0.creationDateMs ?? 0) >= createdAtOrAfterMs } }
    }

    func photonAlbumAssetIDs() async throws -> Set<String> { locked { $0.album } }

    func exportOriginal(localID: String, to directory: URL, stripLocation: Bool) async throws -> ExportedPhoto {
        let entry = locked { l -> (Data, String)? in
            l.exportCalls.append((localID, stripLocation))
            return l.contents[localID]
        }
        guard let (data, mime) = entry else { throw PhotoLibraryError.assetNotFound }
        let url = directory.appendingPathComponent("original.\(fileExtension(forMime: mime))")
        try data.write(to: url)
        let taken = locked { $0.assets.first { $0.localID == localID }?.creationDateMs } ?? 0
        return ExportedPhoto(fileURL: url, mime: mime, takenAtMs: taken)
    }

    func saveToPhotonAlbum(fileURL: URL, mime: String, takenAtMs: Int64,
                           recordLocalID: @escaping @Sendable (String) throws -> Void) async throws -> String {
        if let saveError = locked({ $0.saveError }) { throw saveError }
        let id = "received-\(locked { $0.saved.count + 1 })/L0/001"
        try recordLocalID(id)
        locked { $0.duringSave }?(id)
        let data = try Data(contentsOf: fileURL)
        locked { l in
            l.saved.append((data, mime, takenAtMs, id))
            l.album.insert(id)
        }
        return id
    }
}

final class FakeNetwork: NetworkMonitor, @unchecked Sendable {
    private let lock = NSLock()
    private var status: NetworkStatus
    init(_ status: NetworkStatus = .wifi) { self.status = status }
    var current: NetworkStatus { lock.lock(); defer { lock.unlock() }; return status }
    func set(_ s: NetworkStatus) { lock.lock(); status = s; lock.unlock() }
}

final class FakeAuthenticator: WebAuthenticator, @unchecked Sendable {
    private let lock = NSLock()
    var seen: [(URL, AuthCallback)] = []
    var respond: @Sendable (URL) throws -> URL

    init(respond: @escaping @Sendable (URL) throws -> URL) { self.respond = respond }

    func authenticate(url: URL, callback: AuthCallback) async throws -> URL {
        lock.withLockValue { seen.append((url, callback)) }
        return try respond(url)
    }
}

// MARK: - WebSocket

final class FakeSocket: WebSocketConnection, @unchecked Sendable {
    enum Inbound { case frame(Data), closed(Int) }
    let index: Int
    private let lock = NSLock()
    private let stream: AsyncStream<Inbound>
    private let continuation: AsyncStream<Inbound>.Continuation
    private var iterator: AsyncStream<Inbound>.AsyncIterator
    private var _sent: [PbClientFrame] = []
    private var closedCode: Int?
    var onFrame: (@Sendable (PbClientFrame, FakeSocket) -> Void)?

    init(index: Int) {
        self.index = index
        (stream, continuation) = AsyncStream.makeStream(of: Inbound.self)
        iterator = stream.makeAsyncIterator()
    }

    var sent: [PbClientFrame] { lock.lock(); defer { lock.unlock() }; return _sent }

    func send(_ data: Data) async throws {
        let frame = try PbClientFrame(serializedBytes: data)
        let (closed, handler) = lock.withLockValue { () -> (Int?, (@Sendable (PbClientFrame, FakeSocket) -> Void)?) in
            if let code = closedCode { return (code, nil) }
            _sent.append(frame)
            return (nil, onFrame)
        }
        if let closed { throw WebSocketClosed(code: closed) }
        handler?(frame, self)
    }

    func receive() async throws -> Data {
        var it = iterator
        let next = await it.next()
        iterator = it
        switch next {
        case .frame(let d): return d
        case .closed(let code): throw WebSocketClosed(code: code)
        case nil: throw WebSocketClosed(code: lock.withLockValue { closedCode } ?? WebSocketClosed.abnormal)
        }
    }

    func close(code: Int) {
        lock.lock()
        if closedCode == nil { closedCode = code }
        lock.unlock()
        continuation.yield(.closed(code))
        continuation.finish()
    }

    // Server side.
    func deliver(_ frame: PbServerFrame) { continuation.yield(.frame(try! frame.serializedBytes())) }
    func deliver(event: PbPhotoEvent) { var f = PbServerFrame(); f.event = event; deliver(f) }
    func ready(_ cursor: String = "0-0") { var f = PbServerFrame(); f.readyCursor = cursor; deliver(f) }
    func serverClose(_ code: Int) {
        lock.lock(); closedCode = code; lock.unlock()
        continuation.yield(.closed(code))
        continuation.finish()
    }
}

extension NSLock {
    func withLockValue<T>(_ body: () -> T) -> T { lock(); defer { unlock() }; return body() }
}

final class FakeSocketFactory: WebSocketFactory, @unchecked Sendable {
    private let lock = NSLock()
    private var _sockets: [FakeSocket] = []
    /// Called with each frame the client sends (the first is hello).
    let script: @Sendable (PbClientFrame, FakeSocket) -> Void

    init(script: @escaping @Sendable (PbClientFrame, FakeSocket) -> Void) { self.script = script }

    var sockets: [FakeSocket] { lock.lock(); defer { lock.unlock() }; return _sockets }

    func connect(url: URL) async throws -> WebSocketConnection {
        let socket = lock.withLockValue { () -> FakeSocket in
            let s = FakeSocket(index: _sockets.count)
            _sockets.append(s)
            return s
        }
        socket.onFrame = script
        return socket
    }
}

// MARK: - Builders

func makeSession(id: String = "sid1", userID: String = "me", joinTimeMs: Int64 = 1_699_999_000_000,
                 lastEventID: String? = "0-0", role: SessionRole = .member) -> SessionState {
    SessionState(sessionID: id, role: role, hostID: "host", userID: userID, joinCode: "ABCD2345",
                 inviteURL: "https://photon.example/j/tok", qrPayload: "https://photon.example/j/tok",
                 expiresAtMs: 0, memberIDs: ["host", userID], joinTimeMs: joinTimeMs, lastEventID: lastEventID)
}

func storedEvent(id: String, sha: Data, uploader: String = "alice", expiresAtMs: Int64 = 1_800_000_000_000,
                 sessionID: String = "sid1") -> PbPhotoEvent {
    var e = PbPhotoEvent()
    e.eventID = id
    e.type = .photoStored
    e.sessionID = sessionID
    e.sha256 = sha
    e.uploaderID = uploader
    e.size = 1000
    e.takenAtMs = 1_700_000_000_000
    e.expiresAtMs = expiresAtMs
    return e
}

func tempDirectory() -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("photonkit-tests-\(UUID().uuidString)")
    try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

/// Deterministic pseudo-random bytes.
func testBytes(_ count: Int, seed: UInt8 = 7) -> Data {
    var x = UInt32(seed) &* 2654435761
    return Data((0..<count).map { _ in x = x &* 1664525 &+ 1013904223; return UInt8(x >> 24) })
}

/// Polls `condition` (real time) until true or the timeout passes.
func eventually(timeout: TimeInterval = 5, _ condition: @escaping @Sendable () async -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if await condition() { return true }
        try? await Task.sleep(nanoseconds: 10_000_000)
    }
    return await condition()
}
