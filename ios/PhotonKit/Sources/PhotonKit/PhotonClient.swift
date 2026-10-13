import Foundation

/// Everything PhotonKit needs from the platform.
public struct PhotonServices: Sendable {
    public var secureStore: SecureStore
    public var store: PhotonStore
    public var library: PhotoLibrary
    public var network: NetworkMonitor
    public var authenticator: WebAuthenticator
    public var files: FileLayout
    public var transport: HTTPTransport
    public var chunkUploader: ChunkUploader
    public var sockets: WebSocketFactory
    public var clock: PhotonClock

    public init(secureStore: SecureStore, store: PhotonStore, library: PhotoLibrary, network: NetworkMonitor,
                authenticator: WebAuthenticator, files: FileLayout, transport: HTTPTransport,
                chunkUploader: ChunkUploader, sockets: WebSocketFactory, clock: PhotonClock = SystemClock()) {
        self.secureStore = secureStore
        self.store = store
        self.library = library
        self.network = network
        self.authenticator = authenticator
        self.files = files
        self.transport = transport
        self.chunkUploader = chunkUploader
        self.sockets = sockets
        self.clock = clock
    }
}

/// What an opened URL turned out to be.
public enum OpenedLink: Sendable, Equatable {
    case loggedIn(userID: String)
    /// An invite link: show the consent text, then call `join`.
    case invite(JoinInput)
    case unknown
}

/// The facade the app talks to. It wires the components together and runs them according
/// to the app's lifecycle:
/// - foreground: reconcile scan, catch-up, the WebSocket, the upload and receive queues;
/// - background: the WebSocket is closed; iOS suspends the rest. Nothing is promised while
///   suspended. Background URLSession chunk transfers may finish, and a silent push or a
///   background refresh may run a short catch-up, but neither is relied on.
public actor PhotonClient {
    public nonisolated let config: PhotonConfig
    public nonisolated let api: APIClient
    public nonisolated let store: PhotonStore
    public nonisolated let files: FileLayout
    /// Change notifications for the UI (single consumer).
    public nonisolated let changes: AsyncStream<PhotonChange>

    nonisolated let auth: AuthFlow
    nonisolated let uploads: UploadEngine
    nonisolated let receiver: ReceivePipeline
    nonisolated let processor: EventProcessor
    nonisolated let sync: EventSync
    nonisolated let reconciler: Reconciler
    private nonisolated let router: ChangeRouter
    private let clock: PhotonClock
    private let tuning: PhotonTuning
    private let log = PhotonLog("client")

    private var foreground = false
    private var uploadLoop: WorkLoop?
    private var receiveLoop: WorkLoop?
    private var syncTask: Task<Void, Never>?
    private var pushToken: String?

    public init(config: PhotonConfig, services: PhotonServices, tuning: PhotonTuning = PhotonTuning()) {
        let (stream, continuation) = AsyncStream.makeStream(of: PhotonChange.self, bufferingPolicy: .bufferingNewest(256))
        let router = ChangeRouter(continuation: continuation)
        let emit: ChangeHandler = { router.route($0) }
        let api = APIClient(config: config, transport: services.transport, secureStore: services.secureStore, clock: services.clock)
        let uploads = UploadEngine(api: api, store: services.store, library: services.library, uploader: services.chunkUploader,
                                   files: services.files, network: services.network, clock: services.clock, tuning: tuning,
                                   onChange: emit)
        let receiver = ReceivePipeline(api: api, store: services.store, library: services.library, files: services.files,
                                       network: services.network, clock: services.clock, tuning: tuning, onChange: emit)
        let processor = EventProcessor(store: services.store, uploads: uploads, receiver: receiver, clock: services.clock,
                                       onChange: emit)
        self.config = config
        self.api = api
        self.store = services.store
        self.files = services.files
        self.changes = stream
        self.router = router
        self.auth = AuthFlow(api: api, secureStore: services.secureStore, authenticator: services.authenticator)
        self.uploads = uploads
        self.receiver = receiver
        self.processor = processor
        self.sync = EventSync(api: api, store: services.store, processor: processor, sockets: services.sockets,
                              clock: services.clock, tuning: tuning, onChange: emit)
        self.reconciler = Reconciler(store: services.store, library: services.library, clock: services.clock, onChange: emit)
        self.clock = services.clock
        self.tuning = tuning
        router.attach { [weak self] change in
            guard let self else { return }
            Task { await self.handleTerminal(change) }
        }
    }

    // MARK: - Account

    public var isLoggedIn: Bool { get async { await api.tokens.isLoggedIn } }
    public var userID: String? { get async { await api.tokens.userID } }

    /// Runs the OAuth + PKCE login in the system browser.
    @discardableResult
    public func login() async throws -> String {
        let userID = try await auth.login()
        await registerDeviceIfPossible()
        if foreground { startActivities() }
        return userID
    }

    /// Leaves the session (best effort), then forgets the tokens and every local trace of
    /// it (not photos already saved to the library). Leaving first matters: otherwise the
    /// server still counts this user as a member and refuses the next join.
    public func logout() async {
        if let s = session {
            try? await api.leave(sessionID: s.sessionID)
        }
        await stopAndClearSession()
        await api.tokens.clear()
    }

    // MARK: - Links

    /// Universal Links and the development redirect: the OAuth callback or an invite link.
    public func handleOpenURL(_ url: URL) async throws -> OpenedLink {
        if auth.isAuthCallback(url) {
            let userID = try await auth.finish(callbackURL: url)
            await registerDeviceIfPossible()
            if foreground { startActivities() }
            return .loggedIn(userID: userID)
        }
        if let token = JoinInput.inviteToken(from: url, inviteHost: config.inviteHost) {
            return .invite(.inviteToken(token))
        }
        return .unknown
    }

    // MARK: - Sessions

    public var session: SessionState? { store.read { $0.session } }

    /// Throws `PhotonError.alreadyInSession` when the server already has this user in a
    /// session: offer `leave(sessionID:)` and try again, or `resume`.
    public func createSession(maxMembers: UInt32 = 0) async throws -> SessionState {
        let info = try await conflictChecked { try await self.api.createSession(maxMembers: maxMembers) }
        return try await startSession(info)
    }

    /// Joins by code or invite token. Show the consent text before calling this. Throws
    /// `PhotonError.alreadyInSession` like `createSession`.
    public func join(_ input: JoinInput) async throws -> SessionState {
        let info = try await conflictChecked { try await self.api.join(input.request) }
        return try await startSession(info)
    }

    /// Turns 409 ALREADY_IN_SESSION into `.alreadyInSession` with the session from
    /// `GET /v1/sessions/current`.
    private func conflictChecked(_ body: () async throws -> PbSessionInfo) async throws -> PbSessionInfo {
        do {
            return try await body()
        } catch let error where error.apiError?.code == ErrorCode.alreadyInSession {
            let current = try? await api.currentSession()
            throw PhotonError.alreadyInSession(current ?? nil)
        }
    }

    private func startSession(_ info: PbSessionInfo) async throws -> SessionState {
        let userID = await api.tokens.userID ?? ""
        let now = clock.nowMs()
        let session = try store.write { s -> SessionState in
            s.uploadJobs = s.uploadJobs.filter { $0.value.sessionID == info.sessionID }
            s.receivedPhotos = s.receivedPhotos.filter { $0.value.sessionID == info.sessionID }
            var next = SessionState(sessionID: info.sessionID, role: info.role == "host" ? .host : .member,
                                    hostID: info.hostID, userID: userID, joinCode: info.joinCode, inviteURL: info.inviteURL,
                                    qrPayload: info.qrPayload, expiresAtMs: info.expiresAtMs, memberIDs: info.memberIds,
                                    joinTimeMs: now)
            if let existing = s.session, existing.sessionID == info.sessionID {
                // Rejoining the same session keeps the join time, cursor and pause switch.
                next.joinTimeMs = existing.joinTimeMs
                next.lastEventID = existing.lastEventID
                next.scanCursorMs = existing.scanCursorMs
                next.paused = existing.paused
            }
            s.session = next
            return next
        }
        router.route(.session)
        log.info("in session as \(session.role.rawValue)")
        // The snapshot first (no cursor yet), then the gallery.
        do {
            if case .sessionGone(let r) = try await sync.catchUp() {
                await sessionGone(r)
                throw PhotonError.noSession
            }
        } catch let error where error.isTransient {
            // Offline right after joining: the connection loop catches up later.
        }
        if foreground { startActivities() }
        _ = try? await reconciler.scan()
        uploadLoop?.kick()
        return store.read { $0.session } ?? session
    }

    /// The session the server has this user in when there is none locally (after a
    /// reinstall, or ALREADY_IN_SESSION on create or join). Offer to resume or leave it.
    public func serverSession() async throws -> PbSessionInfo? {
        guard session == nil else { return nil }
        return try await api.currentSession()
    }

    /// Takes up a session the server already has this user in. Show the consent text
    /// first. The join time is now: photos from before this moment are not shared.
    public func resume(_ info: PbSessionInfo) async throws -> SessionState {
        try await startSession(info)
    }

    /// Leaves a session known only by id (the one `serverSession()` found).
    public func leave(sessionID: String) async throws {
        if session?.sessionID == sessionID {
            try await leave()
            return
        }
        do {
            try await api.leave(sessionID: sessionID)
        } catch let error where [403, 404, 410].contains(error.apiError?.status ?? 0) {
        }
    }

    public func leave() async throws {
        guard let s = session else { return }
        do {
            try await api.leave(sessionID: s.sessionID)
        } catch let error where [403, 404, 410].contains(error.apiError?.status ?? 0) {
            // Already out of it.
        }
        await sessionGone(.left)
    }

    /// Host only.
    public func endSession() async throws {
        guard let s = session else { return }
        do {
            try await api.endSession(sessionID: s.sessionID)
        } catch let error where error.apiError?.status == 410 {
        }
        await sessionGone(.ended)
    }

    /// Host only.
    public func kick(memberID: String) async throws {
        guard let s = session else { throw PhotonError.noSession }
        try await api.kick(sessionID: s.sessionID, memberID: memberID)
        _ = try? store.write { st in st.session?.memberIDs.removeAll { $0 == memberID } }
        router.route(.session)
    }

    /// Host only: new code and invite link; the old ones stop working.
    public func rotateInvites() async throws -> SessionState? {
        guard let s = session else { throw PhotonError.noSession }
        if let info = try await api.rotateInvites(sessionID: s.sessionID) {
            _ = try? store.write { st in
                guard var sess = st.session, sess.sessionID == s.sessionID else { return }
                if !info.joinCode.isEmpty { sess.joinCode = info.joinCode }
                if !info.inviteURL.isEmpty { sess.inviteURL = info.inviteURL }
                if !info.qrPayload.isEmpty { sess.qrPayload = info.qrPayload }
                st.session = sess
            }
            router.route(.session)
        }
        return session
    }

    /// Pause switch: while paused nothing is queued or uploaded. Resuming runs the scan.
    public func setPaused(_ paused: Bool) async {
        _ = try? store.write { $0.session?.paused = paused }
        router.route(.session)
        if !paused {
            _ = try? await reconciler.scan()
            uploadLoop?.kick()
        }
    }

    public func updateSettings(_ body: @Sendable (inout ClientSettings) -> Void) {
        _ = try? store.write { body(&$0.settings) }
        router.route(.session)
        receiveLoop?.kick()
    }

    // MARK: - Photos

    /// Uploader or host: stop distribution of a photo. Members' cached copies go when the
    /// event reaches them; originals already saved cannot be deleted.
    public func recall(sha256Hex: String) async throws {
        guard let s = session else { throw PhotonError.noSession }
        do {
            try await api.recall(sessionID: s.sessionID, sha256Hex: sha256Hex)
        } catch let error where error.apiError?.code == ErrorCode.photoRecalled {
        }
        await receiver.recall(sha256Hex: sha256Hex)
    }

    public func report(sha256Hex: String, reason: String) async throws {
        guard let s = session else { throw PhotonError.noSession }
        try await api.report(sessionID: s.sessionID, sha256Hex: sha256Hex, reason: reason)
    }

    // MARK: - Lifecycle

    /// Launch and every return to the foreground: purge old history, run the reconcile
    /// scan, catch up and connect.
    public func didBecomeActive() async {
        foreground = true
        purgeHistory()
        guard await api.tokens.isLoggedIn, session != nil else { return }
        startActivities()
        _ = try? await reconciler.scan()
        uploadLoop?.kick()
        receiveLoop?.kick()
    }

    /// Closes the WebSocket. Running queue work continues until iOS suspends the app.
    public func didEnterBackground() {
        foreground = false
        syncTask?.cancel()
        syncTask = nil
    }

    /// The PhotoKit observer fired: rescan (the observer itself only notices).
    public func photoLibraryDidChange() async {
        guard session != nil else { return }
        if (try? await reconciler.scan()) ?? 0 > 0 { uploadLoop?.kick() }
    }

    /// Network or charging state changed.
    public func networkDidChange() {
        uploadLoop?.kick()
        receiveLoop?.kick()
    }

    /// A silent push or a background refresh: one bounded catch-up. The caller cancels
    /// the task when the OS time runs out. Returns true when there were new events.
    public func backgroundCatchUp() async -> Bool {
        guard await api.tokens.isLoggedIn, let before = session else { return false }
        do {
            if case .sessionGone(let r) = try await sync.catchUp() {
                await sessionGone(r)
                return true
            }
        } catch {
            await handleError(error)
            return false
        }
        _ = try? await reconciler.scan()
        _ = await receiver.run()
        _ = await uploads.run()
        return session?.lastEventID != before.lastEventID
    }

    /// APNs device token. Registered now if logged in, otherwise after login.
    public func setPushToken(_ token: Data) async {
        pushToken = token.hexString
        await registerDeviceIfPossible()
    }

    // MARK: - Internals

    private func registerDeviceIfPossible() async {
        guard let pushToken, await api.tokens.isLoggedIn else { return }
        do {
            try await api.registerDevice(pushToken: pushToken)
        } catch {
            log.error("device registration failed: \(error)")
        }
    }

    private func startActivities() {
        guard session != nil else { return }
        if uploadLoop == nil {
            let uploads = self.uploads
            uploadLoop = WorkLoop(clock: clock) {
                switch await uploads.run() {
                case .finished, .alreadyRunning: return await uploads.nextDueAtMs()
                case .paused, .offline, .sessionGone, .loginRequired: return nil
                }
            }
        }
        if receiveLoop == nil {
            let receiver = self.receiver
            receiveLoop = WorkLoop(clock: clock) {
                switch await receiver.run() {
                case .finished, .alreadyRunning: return await receiver.nextDueAtMs()
                case .offline, .sessionGone: return nil
                }
            }
        }
        if syncTask == nil, foreground {
            let sync = self.sync
            syncTask = Task { [weak self] in
                let stop = await sync.run()
                await self?.syncStopped(stop)
            }
        }
        uploadLoop?.kick()
        receiveLoop?.kick()
    }

    private func syncStopped(_ stop: EventSync.Stop) async {
        syncTask = nil
        switch stop {
        case .sessionGone(let reason): await sessionGone(reason)
        case .loginRequired: await loginRequired()
        case .cancelled, .noSession: break
        }
    }

    private func stopActivities() {
        syncTask?.cancel()
        syncTask = nil
        uploadLoop?.stop()
        uploadLoop = nil
        receiveLoop?.stop()
        receiveLoop = nil
    }

    /// Terminal changes reported by any component come here exactly once.
    private func handleTerminal(_ change: PhotonChange) async {
        switch change {
        case .sessionGone(let reason): await sessionGone(reason)
        case .loginRequired: await loginRequired()
        default: break
        }
    }

    private func handleError(_ error: Error) async {
        if let p = error as? PhotonError, p == .loginRequired { await loginRequired(); return }
        guard let e = error.apiError else { return }
        if e.status == 410 && e.code == ErrorCode.sessionEnded { await sessionGone(.ended) }
        if e.status == 403 && e.code == ErrorCode.notAMember { await sessionGone(.removed) }
    }

    /// Leave, kick, end, 4403/4410 or a 410 from any call: stop syncing, delete copies,
    /// thumbnails and previews, clear the session. Hashes and received asset ids stay for
    /// 24 hours so a late echo is still recognised.
    private func sessionGone(_ reason: SessionGoneReason) async {
        guard session != nil else { return }
        log.info("session gone: \(reason.rawValue)")
        await stopAndClearSession()
        router.emit(.sessionGone(reason))
    }

    private func stopAndClearSession() async {
        stopActivities()
        await uploads.removeAllJobs()
        await receiver.removeAll()
        _ = try? store.write { $0.session = nil }
        router.emit(.session)
    }

    private func loginRequired() async {
        syncTask?.cancel()
        syncTask = nil
        router.emit(.loginRequired)
    }

    private func purgeHistory() {
        let cutoff = clock.nowMs() - tuning.historyRetentionMs
        let current = session?.sessionID
        _ = try? store.write { s in
            s.knownHashes = s.knownHashes.filter { $0.value.sessionID == current || $0.value.recordedAtMs >= cutoff }
            s.receivedAssets = s.receivedAssets.filter { $0.value.sessionID == current || $0.value.recordedAtMs >= cutoff }
        }
    }
}

/// Forwards ordinary changes to the UI stream and terminal ones (session gone, login
/// required) to the client, which cleans up first and then emits them once.
final class ChangeRouter: @unchecked Sendable {
    private let continuation: AsyncStream<PhotonChange>.Continuation
    private let lock = NSLock()
    private var terminalHandler: (@Sendable (PhotonChange) -> Void)?

    init(continuation: AsyncStream<PhotonChange>.Continuation) {
        self.continuation = continuation
    }

    func attach(_ handler: @escaping @Sendable (PhotonChange) -> Void) {
        lock.lock()
        terminalHandler = handler
        lock.unlock()
    }

    func route(_ change: PhotonChange) {
        switch change {
        case .sessionGone, .loginRequired:
            lock.lock()
            let handler = terminalHandler
            lock.unlock()
            handler?(change)
        default:
            continuation.yield(change)
        }
    }

    func emit(_ change: PhotonChange) {
        continuation.yield(change)
    }
}
