import Foundation
import XCTest
@testable import PhotonKit

/// Fast real-time tunings: the sync loop runs on SystemClock with millisecond backoffs.
func fastTuning() -> PhotonTuning {
    var t = PhotonTuning()
    t.reconnectBackoff = Backoff(baseMs: 1, capMs: 5)
    t.pingIntervalMs = 60_000
    t.socketSilenceTimeoutMs = 600_000
    return t
}

/// Serves /events, /snapshot and /auth/refresh from closures.
final class SyncServer: @unchecked Sendable {
    private let lock = NSLock()
    var eventPages: [String: [PbPhotoEvent]] = [:]   // since -> events
    var snapshotCursor = "0-0"
    var snapshotCount = 0
    var refreshCount = 0
    var resyncOnce = false
    var infoRole = "member"
    var infoHost = "host"

    func handle(_ request: HTTPRequest) -> HTTPResponse {
        lock.withLockValue {
            let path = request.url.path
            if path == "/v1/auth/refresh" {
                refreshCount += 1
                return pbResponse(tokenPair("access-\(refreshCount + 1)", "refresh-\(refreshCount + 1)"))
            }
            if path == "/v1/sessions/sid1" {
                var info = PbSessionInfo()
                info.sessionID = "sid1"
                info.role = infoRole
                info.hostID = infoHost
                info.memberIds = ["host", "me"]
                return pbResponse(info)
            }
            if path.hasSuffix("/snapshot") {
                snapshotCount += 1
                var s = PbSnapshot()
                s.cursor = snapshotCursor
                s.memberIds = ["host", "me"]
                return pbResponse(s)
            }
            if path.hasSuffix("/events") {
                if resyncOnce {
                    resyncOnce = false
                    return errorResponse(410, ErrorCode.resyncRequired)
                }
                let since = URLComponents(url: request.url, resolvingAgainstBaseURL: false)?
                    .queryItems?.first { $0.name == "since" }?.value ?? ""
                var batch = PbEventBatch()
                batch.events = eventPages[since] ?? []
                batch.cursor = batch.events.last?.eventID ?? since
                return pbResponse(batch)
            }
            return errorResponse(404, "NOT_ROUTED")
        }
    }
}

final class EventSyncTests: XCTestCase {
    let shaA = SHA256Hasher.hash(Data("A".utf8))
    let shaB = SHA256Hasher.hash(Data("B".utf8))

    private func make(_ server: SyncServer, tuning: PhotonTuning = fastTuning(),
                      script: @escaping @Sendable (PbClientFrame, FakeSocket) -> Void) -> (ReceiveHarness, EventSync, FakeSocketFactory) {
        let h = ReceiveHarness(clock: SystemClock(), tuning: tuning) { server.handle($0) }
        let sockets = FakeSocketFactory(script: script)
        let sync = EventSync(api: h.api, store: h.store, processor: h.processor, sockets: sockets, clock: h.clock,
                             tuning: tuning, onChange: h.changes.handler)
        return (h, sync, sockets)
    }

    func testCloseCodeTable() {
        XCTAssertEqual(EventSync.action(forCloseCode: 4001), .refreshAndReconnect)
        XCTAssertEqual(EventSync.action(forCloseCode: 4400), .reconnectWithBackoff)
        XCTAssertEqual(EventSync.action(forCloseCode: 4401), .loginRequired)
        XCTAssertEqual(EventSync.action(forCloseCode: 4403), .stop(.removed))
        XCTAssertEqual(EventSync.action(forCloseCode: 4409), .resyncAndReconnect)
        XCTAssertEqual(EventSync.action(forCloseCode: 4410), .stop(.ended))
        XCTAssertEqual(EventSync.action(forCloseCode: 1012), .reconnectWithBackoff)
        XCTAssertEqual(EventSync.action(forCloseCode: 1006), .reconnectWithBackoff)
    }

    func testCatchUpThenHelloThenLiveEvents() async throws {
        let server = SyncServer()
        server.eventPages["3-0"] = [storedEvent(id: "4-0", sha: shaA)]
        let shaB = self.shaB
        let (h, sync, sockets) = make(server) { frame, socket in
            guard case .hello? = frame.f else { return }
            socket.ready("4-0")
            socket.deliver(event: storedEvent(id: "5-0", sha: shaB))
            socket.serverClose(4410)
        }
        try h.store.write { $0.session?.lastEventID = "3-0" }
        let stop = await sync.run()
        XCTAssertEqual(stop, .sessionGone(.ended))

        let events = h.transport.requests.first { $0.url.path == "/v1/sessions/sid1/events" }
        XCTAssertEqual(events?.url.query, "since=3-0&limit=500")
        let hello = try XCTUnwrap(sockets.sockets.first?.sent.first?.hello)
        XCTAssertEqual(hello.accessToken, "access-1")
        XCTAssertEqual(hello.sessionID, "sid1")
        XCTAssertEqual(hello.lastEventID, "4-0", "hello resumes after the caught-up events")
        XCTAssertNotNil(h.store.read { $0.receivedPhotos[self.shaA.hexString] })
        XCTAssertNotNil(h.store.read { $0.receivedPhotos[shaB.hexString] })
        XCTAssertEqual(h.lastEventID, "5-0")
        XCTAssertTrue(h.changes.all.contains(.connection(.live)))
    }

    func testCloseCode4001RefreshesOnceAndReconnects() async throws {
        let server = SyncServer()
        let (h, sync, sockets) = make(server) { frame, socket in
            guard case .hello(let hello)? = frame.f else { return }
            socket.serverClose(socket.index == 0 ? 4001 : 4410)
            if socket.index == 1 { XCTAssertEqual(hello.accessToken, "access-2") }
        }
        let stop = await sync.run()
        XCTAssertEqual(stop, .sessionGone(.ended))
        XCTAssertEqual(server.refreshCount, 1)
        XCTAssertEqual(sockets.sockets.count, 2)
        XCTAssertEqual(sockets.sockets[1].sent.first?.hello.accessToken, "access-2")
        _ = h
    }

    func testCloseCode4409LoadsSnapshotAndReconnectsFromItsCursor() async throws {
        let server = SyncServer()
        server.snapshotCursor = "99-0"
        let (h, sync, sockets) = make(server) { frame, socket in
            guard case .hello? = frame.f else { return }
            socket.serverClose(socket.index == 0 ? 4409 : 4403)
        }
        let stop = await sync.run()
        XCTAssertEqual(stop, .sessionGone(.removed))
        XCTAssertEqual(server.snapshotCount, 1)
        XCTAssertEqual(sockets.sockets[1].sent.first?.hello.lastEventID, "99-0")
        XCTAssertEqual(h.lastEventID, "99-0")
    }

    /// The snapshot carries no host: after a resync the role comes from SessionInfo, so a
    /// HOST_CHANGED trimmed from the stream is not missed.
    func testResyncRefreshesRoleFromSessionInfo() async throws {
        let server = SyncServer()
        server.infoRole = "host"
        server.infoHost = "me"
        let (h, sync, _) = make(server) { frame, socket in
            guard case .hello? = frame.f else { return }
            socket.serverClose(socket.index == 0 ? 4409 : 4410)
        }
        XCTAssertEqual(h.store.read { $0.session?.role }, .member)
        _ = await sync.run()
        XCTAssertEqual(h.store.read { $0.session?.role }, .host)
        XCTAssertEqual(h.store.read { $0.session?.hostID }, "me")
    }

    func testCloseCode4401RequiresLogin() async throws {
        let (h, sync, _) = make(SyncServer()) { frame, socket in
            if case .hello? = frame.f { socket.serverClose(4401) }
        }
        let stop = await sync.run()
        XCTAssertEqual(stop, .loginRequired)
        let loggedIn = await h.api.tokens.isLoggedIn
        XCTAssertFalse(loggedIn)
    }

    func testRestartAndDropsReconnectWithBackoff() async throws {
        let (h, sync, sockets) = make(SyncServer()) { frame, socket in
            guard case .hello? = frame.f else { return }
            switch socket.index {
            case 0: socket.serverClose(1012)
            case 1: socket.serverClose(WebSocketClosed.abnormal)
            default: socket.serverClose(4410)
            }
        }
        let stop = await sync.run()
        XCTAssertEqual(stop, .sessionGone(.ended))
        XCTAssertEqual(sockets.sockets.count, 3)
        XCTAssertEqual(h.changes.all.filter { if case .connection(.waiting) = $0 { return true } else { return false } }.count, 2)
        // Every reconnect catches up over HTTP first.
        XCTAssertEqual(h.transport.requests.filter { $0.url.path.hasSuffix("/events") }.count, 3)
    }

    func testResyncRequiredDuringCatchUpLoadsSnapshot() async throws {
        let server = SyncServer()
        server.resyncOnce = true
        server.snapshotCursor = "7-0"
        let (h, sync, sockets) = make(server) { frame, socket in
            if case .hello? = frame.f { socket.serverClose(4410) }
        }
        try h.store.write { $0.session?.lastEventID = "1-0" }
        _ = await sync.run()
        XCTAssertEqual(server.snapshotCount, 1)
        XCTAssertEqual(sockets.sockets.first?.sent.first?.hello.lastEventID, "7-0")
    }

    func testNoCursorLoadsSnapshotBeforeEvents() async throws {
        let server = SyncServer()
        server.snapshotCursor = "12-0"
        let (h, sync, _) = make(server) { frame, socket in
            if case .hello? = frame.f { socket.serverClose(4410) }
        }
        try h.store.write { $0.session?.lastEventID = nil }
        _ = await sync.run()
        // Snapshot, then SessionInfo (the snapshot has no host), then events from its cursor.
        let paths = h.transport.requests.map { $0.url.path }
        XCTAssertEqual(paths.prefix(3), ["/v1/sessions/sid1/snapshot", "/v1/sessions/sid1", "/v1/sessions/sid1/events"])
        XCTAssertEqual(h.transport.requests[2].url.query, "since=12-0&limit=500")
    }

    func testSessionEndedEventStopsWithoutWaitingForTheClose() async throws {
        let (_, sync, _) = make(SyncServer()) { frame, socket in
            guard case .hello? = frame.f else { return }
            var e = PbPhotoEvent()
            e.eventID = "9-0"
            e.type = .sessionEnded
            socket.ready()
            socket.deliver(event: e)
        }
        let stop = await sync.run()
        XCTAssertEqual(stop, .sessionGone(.ended))
    }

    func testPingsEvery30SecondsAndDeadSocketIsReplaced() async throws {
        var tuning = fastTuning()
        tuning.pingIntervalMs = 20
        tuning.socketSilenceTimeoutMs = 70
        let (_, sync, sockets) = make(SyncServer(), tuning: tuning) { frame, socket in
            switch (socket.index, frame.f) {
            case (0, .hello?):
                socket.ready()               // then silence: no pongs, no events
            case (1, .hello?):
                socket.ready()
            case (1, .ping(let n)?):
                var pong = PbServerFrame()
                pong.pong = n
                socket.deliver(pong)
                if n == 3 { socket.serverClose(4410) }
            default:
                break
            }
        }
        let stop = await sync.run()
        XCTAssertEqual(stop, .sessionGone(.ended))
        XCTAssertEqual(sockets.sockets.count, 2, "the silent socket was dropped and replaced")
        let pings = sockets.sockets[1].sent.compactMap { frame -> UInt64? in
            if case .ping(let n)? = frame.f { return n }
            return nil
        }
        XCTAssertEqual(pings, [1, 2, 3])
    }

    func testCancellationStopsTheLoop() async throws {
        let (_, sync, _) = make(SyncServer()) { frame, socket in
            if case .hello? = frame.f { socket.ready() }
        }
        let task = Task { await sync.run() }
        try await Task.sleep(nanoseconds: 50_000_000)
        task.cancel()
        let stop = await task.value
        XCTAssertEqual(stop, .cancelled)
    }
}

final class PhotonClientTests: XCTestCase {
    /// Join: SessionInfo, then the snapshot, then the session is stored with its join time
    /// and cursor. Leave: the server is told, local state goes, one sessionGone(.left).
    func testJoinLoadsSnapshotAndLeaveCleansUp() async throws {
        let server = SyncServer()
        server.snapshotCursor = "20-0"
        let transport = FakeTransport { request in
            switch request.url.path {
            case "/v1/sessions/join":
                XCTAssertEqual(body(request, as: PbJoinRequest.self).code, "ABCD2345")
                var info = PbSessionInfo()
                info.sessionID = "sid1"
                info.role = "member"
                info.hostID = "host"
                info.joinCode = "ABCD2345"
                return pbResponse(info)
            case "/v1/sessions/leave":
                XCTAssertEqual(body(request, as: PbSessionRef.self).sessionID, "sid1")
                return HTTPResponse(status: 204)
            default:
                return server.handle(request)
            }
        }
        let secure = MemorySecureStore()
        secure.seedTokens()
        let clock = TestClock()
        let root = tempDirectory()
        let library = FakeLibrary()
        library.add("taken-before", data: Data([1]), createdAtMs: clock.nowMs() - 1)
        let services = PhotonServices(secureStore: secure, store: FileStore(fileURL: nil), library: library,
                                      network: FakeNetwork(), authenticator: FakeAuthenticator { $0 },
                                      files: FileLayout(dataRoot: root, cacheRoot: root.appendingPathComponent("c")),
                                      transport: transport, chunkUploader: HTTPChunkUploader(transport: transport),
                                      sockets: FakeSocketFactory { _, _ in }, clock: clock)
        let client = PhotonClient(config: testConfig, services: services, tuning: fastTuning())
        let input = try XCTUnwrap(JoinInput.parse("abcd-2345", inviteHost: "photon.example"))
        let session = try await client.join(input)
        XCTAssertEqual(session.sessionID, "sid1")
        XCTAssertEqual(session.role, .member)
        XCTAssertEqual(session.userID, "me")
        XCTAssertEqual(session.joinTimeMs, clock.nowMs())
        XCTAssertEqual(session.lastEventID, "20-0")
        XCTAssertEqual(server.snapshotCount, 1)
        XCTAssertTrue(services.store.read { $0.uploadJobs.isEmpty }, "photos from before the join are not shared")

        try await client.leave()
        let after = await client.session
        XCTAssertNil(after)
        XCTAssertTrue(transport.requests.contains { $0.url.path == "/v1/sessions/leave" })

        var sawLeft = false
        for await change in client.changes {
            if change == .sessionGone(.left) { sawLeft = true; break }
        }
        XCTAssertTrue(sawLeft)
    }

    /// After a reinstall the server still has the user in a session: find it with
    /// /v1/sessions/current, resume it, and on logout leave it so the next join works.
    func testServerSessionRecoveryAndLogoutLeaves() async throws {
        let server = SyncServer()
        server.infoRole = "host"
        server.infoHost = "me"
        let hasSession = Counter()
        let transport = FakeTransport { request in
            switch request.url.path {
            case "/v1/sessions/current":
                if hasSession.value > 0 { return errorResponse(404, ErrorCode.sessionNotFound) }
                hasSession.increment()
                var info = PbSessionInfo()
                info.sessionID = "sid1"
                info.role = "host"
                return pbResponse(info)
            case "/v1/sessions/leave":
                return HTTPResponse(status: 204)
            default:
                return server.handle(request)
            }
        }
        let secure = MemorySecureStore()
        secure.seedTokens()
        let root = tempDirectory()
        let services = PhotonServices(secureStore: secure, store: FileStore(fileURL: nil), library: FakeLibrary(),
                                      network: FakeNetwork(), authenticator: FakeAuthenticator { $0 },
                                      files: FileLayout(dataRoot: root, cacheRoot: root.appendingPathComponent("c")),
                                      transport: transport, chunkUploader: HTTPChunkUploader(transport: transport),
                                      sockets: FakeSocketFactory { _, _ in }, clock: TestClock())
        let client = PhotonClient(config: testConfig, services: services, tuning: fastTuning())
        let found = try await client.serverSession()
        let info = try XCTUnwrap(found)
        let resumed = try await client.resume(info)
        XCTAssertEqual(resumed.sessionID, "sid1")
        XCTAssertEqual(resumed.role, .host)
        let none = try await client.serverSession()
        XCTAssertNil(none, "a local session exists")

        await client.logout()
        let leave = try XCTUnwrap(transport.requests.first { $0.url.path == "/v1/sessions/leave" })
        XCTAssertEqual(body(leave, as: PbSessionRef.self).sessionID, "sid1")
        let after = await client.session
        XCTAssertNil(after)
        let loggedIn = await client.isLoggedIn
        XCTAssertFalse(loggedIn)

        let fresh = MemorySecureStore()
        fresh.seedTokens()
        let api = APIClient(config: testConfig, transport: transport, secureStore: fresh, clock: TestClock())
        let gone = try await api.currentSession()
        XCTAssertNil(gone, "404 SESSION_NOT_FOUND means no session")
    }

    /// Join refused with 409 ALREADY_IN_SESSION: the error names the session from
    /// /v1/sessions/current; leaving it by id and retrying succeeds.
    func testJoinConflictNamesTheCurrentSessionAndRetrySucceeds() async throws {
        let server = SyncServer()
        let left = Counter()
        let transport = FakeTransport { request in
            switch request.url.path {
            case "/v1/sessions/join":
                if left.value == 0 { return errorResponse(409, ErrorCode.alreadyInSession) }
                var info = PbSessionInfo()
                info.sessionID = "sid1"
                info.role = "member"
                return pbResponse(info)
            case "/v1/sessions/current":
                var info = PbSessionInfo()
                info.sessionID = "old-sid"
                info.joinCode = "OLDC0DE1"
                info.role = "host"
                return pbResponse(info)
            case "/v1/sessions/leave":
                XCTAssertEqual(body(request, as: PbSessionRef.self).sessionID, "old-sid")
                left.increment()
                return HTTPResponse(status: 204)
            default:
                return server.handle(request)
            }
        }
        let secure = MemorySecureStore()
        secure.seedTokens()
        let root = tempDirectory()
        let services = PhotonServices(secureStore: secure, store: FileStore(fileURL: nil), library: FakeLibrary(),
                                      network: FakeNetwork(), authenticator: FakeAuthenticator { $0 },
                                      files: FileLayout(dataRoot: root, cacheRoot: root.appendingPathComponent("c")),
                                      transport: transport, chunkUploader: HTTPChunkUploader(transport: transport),
                                      sockets: FakeSocketFactory { _, _ in }, clock: TestClock())
        let client = PhotonClient(config: testConfig, services: services, tuning: fastTuning())
        do {
            _ = try await client.join(.code("ABCD2345"))
            XCTFail("expected a conflict")
        } catch PhotonError.alreadyInSession(let info) {
            XCTAssertEqual(info?.sessionID, "old-sid")
            XCTAssertEqual(info?.joinCode, "OLDC0DE1")
            try await client.leave(sessionID: "old-sid")
        }
        let session = try await client.join(.code("ABCD2345"))
        XCTAssertEqual(session.sessionID, "sid1")
        XCTAssertEqual(left.value, 1)
    }

    /// A 410 SESSION_ENDED from any component (here the upload queue) ends the session
    /// for the whole client: everything stops and local session state is cleared.
    func testSessionEndedFromUploadsClearsTheSession() async throws {
        let transport = FakeTransport { request in
            if request.url.path == "/v1/photos/announce" { return errorResponse(410, ErrorCode.sessionEnded) }
            if request.url.path.hasSuffix("/events") { return pbResponse(PbEventBatch()) }
            return errorResponse(404, "NOT_ROUTED")
        }
        let secure = MemorySecureStore()
        secure.seedTokens()
        let store = FileStore(fileURL: nil)
        try store.write { $0.session = makeSession(joinTimeMs: 0) }
        let library = FakeLibrary()
        library.add("new-photo", data: testBytes(1000), createdAtMs: 10)
        let root = tempDirectory()
        let services = PhotonServices(secureStore: secure, store: store, library: library, network: FakeNetwork(),
                                      authenticator: FakeAuthenticator { $0 },
                                      files: FileLayout(dataRoot: root, cacheRoot: root.appendingPathComponent("c")),
                                      transport: transport, chunkUploader: HTTPChunkUploader(transport: transport),
                                      sockets: FakeSocketFactory { frame, socket in
                                          if case .hello? = frame.f { socket.ready() }
                                      }, clock: SystemClock())
        let client = PhotonClient(config: testConfig, services: services, tuning: fastTuning())
        await client.didBecomeActive()
        var ended = false
        for await change in client.changes {
            if change == .sessionGone(.ended) { ended = true; break }
        }
        XCTAssertTrue(ended)
        XCTAssertNil(store.read { $0.session })
        XCTAssertTrue(store.read { $0.uploadJobs.isEmpty })
    }
}
