import Foundation

/// The live event connection (Android 7.8). Each round:
/// 1. catch up over HTTP (`/events?since=` until a short page; the snapshot when there is
///    no cursor yet or the server answers 410 RESYNC_REQUIRED);
/// 2. open `/v1/ws`, send `hello{access_token, session_id, last_event_id}`, ping every 30 s;
/// 3. handle events in order until the socket closes, then act on the close code:
///
/// | Code | Action |
/// | --- | --- |
/// | 4001 | refresh the access token, reconnect at once from the last id |
/// | 4400 | bad hello: reconnect with backoff |
/// | 4401 | token invalid: log in again |
/// | 4403 | not a member: stop syncing |
/// | 4409 | load the snapshot, reconnect from its cursor |
/// | 4410 | session ended: stop syncing |
/// | 1012 and anything else | reconnect with backoff and jitter |
public actor EventSync {
    public enum Stop: Sendable, Equatable {
        case cancelled
        case noSession
        case sessionGone(SessionGoneReason)
        case loginRequired
    }

    public enum CloseAction: Sendable, Equatable {
        case refreshAndReconnect
        case reconnectWithBackoff
        case loginRequired
        case stop(SessionGoneReason)
        case resyncAndReconnect
    }

    /// The close-code table above.
    public static func action(forCloseCode code: Int) -> CloseAction {
        switch code {
        case 4001: return .refreshAndReconnect
        case 4401: return .loginRequired
        case 4403: return .stop(.removed)
        case 4409: return .resyncAndReconnect
        case 4410: return .stop(.ended)
        default: return .reconnectWithBackoff   // 4400, 1012, 1006, ...
        }
    }

    private let api: APIClient
    private let store: PhotonStore
    private let processor: EventProcessor
    private let sockets: WebSocketFactory
    private let clock: PhotonClock
    private let tuning: PhotonTuning
    private let onChange: ChangeHandler
    private let log = PhotonLog("sync")

    private var failures = 0
    private var lastFrameAtMs: Int64 = 0

    public init(api: APIClient, store: PhotonStore, processor: EventProcessor, sockets: WebSocketFactory, clock: PhotonClock,
                tuning: PhotonTuning = PhotonTuning(), onChange: @escaping ChangeHandler = { _ in }) {
        self.api = api
        self.store = store
        self.processor = processor
        self.sockets = sockets
        self.clock = clock
        self.tuning = tuning
        self.onChange = onChange
    }

    // MARK: - Catch-up

    /// Brings the local state up to the server's: snapshot if needed, then every retained
    /// event after `last_event_id`. Used before each connect, on app start and on a push.
    /// Returns `.sessionGone` when an event ended the session for this user.
    @discardableResult
    public func catchUp() async throws -> EventProcessor.Outcome {
        var resyncs = 0
        while true {
            guard let session = store.read({ $0.session }) else { throw PhotonError.noSession }
            if session.lastEventID == nil {
                // First join, or the cursor was dropped: the snapshot provides one.
                try await loadSnapshot(session)
            }
            do {
                while true {
                    let cursor = store.read { $0.session?.lastEventID } ?? StreamID.zero.description
                    let batch = try await api.events(sessionID: session.sessionID, since: cursor, limit: tuning.eventsPageLimit)
                    for event in batch.events {
                        if case .sessionGone(let r) = await processor.handle(event) { return .sessionGone(r) }
                    }
                    if batch.events.count < tuning.eventsPageLimit { return .handled }
                }
            } catch let error where error.apiError?.code == ErrorCode.resyncRequired && resyncs < 2 {
                resyncs += 1
                log.info("resync required; loading snapshot")
                try await loadSnapshot(session)
            }
        }
    }

    private func loadSnapshot(_ session: SessionState) async throws {
        let snapshot = try await api.snapshot(sessionID: session.sessionID)
        await processor.apply(snapshot: snapshot)
        // The snapshot has no host id; refresh role and host (best effort).
        if let info = try? await api.sessionInfo(sessionID: session.sessionID) {
            await processor.apply(info: info)
        }
    }

    // MARK: - Connection loop

    /// Runs until cancelled or until the session is gone for this user.
    public func run() async -> Stop {
        failures = 0
        var resyncsInARow = 0
        while !Task.isCancelled {
            guard let session = store.read({ $0.session }) else { return .noSession }
            var usedToken: String?
            do {
                onChange(.connection(.connecting))
                if case .sessionGone(let r) = try await catchUp() { return .sessionGone(r) }
                let token = try await api.tokens.validAccessToken(minValidityMs: 60_000)
                usedToken = token
                let socket = try await sockets.connect(url: api.config.webSocketURL)
                if let stop = try await pump(socket, session: session, token: token) { return stop }
            } catch let closed as WebSocketClosed {
                log.info("socket closed \(closed.code)")
                switch Self.action(forCloseCode: closed.code) {
                case .refreshAndReconnect:
                    do {
                        _ = try await api.tokens.refresh(rejected: usedToken)
                        failures = 0
                        continue
                    } catch PhotonError.loginRequired {
                        return .loginRequired
                    } catch {
                        // Refresh failed for a network reason: back off below.
                    }
                case .loginRequired:
                    await api.tokens.clear()
                    return .loginRequired
                case .stop(let reason):
                    return .sessionGone(reason)
                case .resyncAndReconnect:
                    resyncsInARow += 1
                    if resyncsInARow <= 3 {
                        do {
                            try await loadSnapshot(session)
                            continue
                        } catch {
                            if let stop = stopFor(error) { return stop }
                        }
                    }
                case .reconnectWithBackoff:
                    break
                }
            } catch {
                if let stop = stopFor(error) { return stop }
                log.info("sync round failed: \(error)")
            }
            if Task.isCancelled { break }
            let delay = tuning.reconnectBackoff.delayMs(attempt: failures)
            failures += 1
            onChange(.connection(.waiting(retryInMs: delay)))
            do { try await clock.sleep(ms: delay) } catch { break }
            if failures > 0 && resyncsInARow > 3 { resyncsInARow = 0 }
        }
        onChange(.connection(.idle))
        return .cancelled
    }

    private func stopFor(_ error: Error) -> Stop? {
        if error is CancellationError { return .cancelled }
        if let p = error as? PhotonError {
            if p == .loginRequired { return .loginRequired }
            if p == .noSession { return .noSession }
        }
        if let e = error.apiError {
            if e.status == 410 && e.code == ErrorCode.sessionEnded { return .sessionGone(.ended) }
            if e.status == 403 && e.code == ErrorCode.notAMember { return .sessionGone(.removed) }
        }
        return nil
    }

    /// Sends hello, then reads frames and pings until the socket closes (throws
    /// `WebSocketClosed`) or an event ends the session (returns the stop).
    private func pump(_ socket: WebSocketConnection, session: SessionState, token: String) async throws -> Stop? {
        var hello = PbHello()
        hello.accessToken = token
        hello.sessionID = session.sessionID
        hello.lastEventID = store.read { $0.session?.lastEventID } ?? StreamID.zero.description
        var frame = PbClientFrame()
        frame.hello = hello
        lastFrameAtMs = clock.nowMs()
        do {
            try await socket.send(try frame.serializedBytes())
        } catch {
            socket.close(code: 1000)
            throw error
        }
        return try await withThrowingTaskGroup(of: Stop?.self) { group -> Stop? in
            group.addTask { try await self.readLoop(socket) }
            group.addTask { try await self.pingLoop(socket) }
            // Whichever loop ends first decides; closing the socket also wakes a receive()
            // that would not notice cancellation, so the group can finish.
            do {
                let first = try await group.next() ?? nil
                socket.close(code: 1000)
                group.cancelAll()
                return first
            } catch {
                socket.close(code: 1000)
                group.cancelAll()
                throw error
            }
        }
    }

    private func readLoop(_ socket: WebSocketConnection) async throws -> Stop? {
        while true {
            let data = try await socket.receive()
            lastFrameAtMs = clock.nowMs()
            guard let frame = try? PbServerFrame(serializedBytes: data) else {
                log.error("undecodable server frame")
                continue
            }
            switch frame.f {
            case .readyCursor:
                failures = 0
                onChange(.connection(.live))
            case .event(let event):
                if case .sessionGone(let reason) = await processor.handle(event) {
                    return .sessionGone(reason)
                }
            case .error(let e):
                // The server closes right after; the close code decides what happens.
                log.info("server error frame \(e.code)")
            case .pong, .none:
                break
            }
        }
    }

    private func pingLoop(_ socket: WebSocketConnection) async throws -> Stop? {
        var counter: UInt64 = 0
        while true {
            try await clock.sleep(ms: tuning.pingIntervalMs)
            if clock.nowMs() - lastFrameAtMs > tuning.socketSilenceTimeoutMs {
                throw WebSocketClosed(code: WebSocketClosed.abnormal, reason: "no frames")
            }
            counter += 1
            var frame = PbClientFrame()
            frame.ping = counter
            try await socket.send(try frame.serializedBytes())
        }
    }
}
