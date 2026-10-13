import Foundation
import Observation
import Photos
import PhotonKit
import SwiftUI

struct AlertItem: Identifiable {
    let id = UUID()
    let title: String
    let message: String
}

/// What the consent sheet is confirming.
struct ConsentRequest: Identifiable {
    enum Kind { case create, join(JoinInput), resume(PbSessionInfo) }
    let id = UUID()
    let kind: Kind
}

/// The server already has this user in a session (409 ALREADY_IN_SESSION on create or
/// join, or found at launch through GET /v1/sessions/current).
struct SessionConflict: Identifiable {
    let id = UUID()
    /// nil when the session could not be looked up.
    let info: PbSessionInfo?
    /// The create or join to try again after leaving; nil when found at launch.
    let retry: ConsentRequest.Kind?
}

/// UI state, reloaded from PhotonKit's store whenever the client reports a change.
@MainActor
@Observable
final class AppModel {
    enum Phase { case starting, loggedOut, home, inSession }

    private(set) var phase: Phase = .starting
    private(set) var loggedIn = false
    private(set) var userID: String?
    private(set) var session: SessionState?
    private(set) var uploads: [UploadJob] = []
    private(set) var received: [ReceivedPhoto] = []
    private(set) var settings = ClientSettings()
    private(set) var connection: ConnectionState = .idle
    private(set) var libraryStatus: PHAuthorizationStatus = PhotoKitLibrary.authorization
    private(set) var busy = false
    var consent: ConsentRequest?
    var conflict: SessionConflict?
    var alert: AlertItem?

    let env: AppEnvironment
    private var client: PhotonClient { env.client }
    private var listening = false

    init(env: AppEnvironment) {
        self.env = env
        Task { await start() }
    }

    private func start() async {
        loggedIn = await client.isLoggedIn
        userID = await client.userID
        listen()
        reload()
        await recoverServerSession()
    }

    /// Local state lost (reinstall, cleared data) but the server still has this user in a
    /// session: offer to rejoin or leave it.
    private func recoverServerSession() async {
        guard loggedIn, session == nil, let info = try? await client.serverSession() else { return }
        conflict = SessionConflict(info: info, retry: nil)
    }

    private func listen() {
        guard !listening else { return }
        listening = true
        let changes = client.changes
        Task { [weak self] in
            for await change in changes {
                self?.handle(change)
            }
        }
    }

    private func handle(_ change: PhotonChange) {
        switch change {
        case .connection(let state):
            connection = state
            return
        case .sessionGone(let reason):
            let (title, message) = Copy.sessionGone(reason.rawValue)
            alert = AlertItem(title: title, message: message)
        case .loginRequired:
            loggedIn = false
            alert = AlertItem(title: "Sign in again", message: "Your login has expired.")
        case .uploadRejected:
            alert = AlertItem(title: "Photo not shared", message: "The server refused one of your photos as damaged or not a valid image.")
        case .session, .uploads, .received:
            break
        }
        reload()
    }

    func reload() {
        let state = client.store.read { $0 }
        session = state.session
        settings = state.settings
        uploads = state.uploadJobs.values
            .filter { $0.sessionID == state.session?.sessionID }
            .sorted { $0.takenAtMs > $1.takenAtMs }
        received = state.receivedPhotos.values
            .filter { $0.sessionID == state.session?.sessionID && $0.state != .failed }
            .sorted { $0.receivedAtMs > $1.receivedAtMs }
        libraryStatus = PhotoKitLibrary.authorization
        phase = !loggedIn ? .loggedOut : (session == nil ? .home : .inSession)
    }

    // MARK: - Actions

    private func run(_ body: @escaping @MainActor () async throws -> Void) {
        busy = true
        Task { @MainActor in
            defer { busy = false; reload() }
            do {
                try await body()
            } catch {
                if let message = Self.message(for: error) {
                    alert = AlertItem(title: "Something went wrong", message: message)
                }
            }
        }
    }

    func login() {
        run {
            _ = try await self.client.login()
            self.loggedIn = true
            self.userID = await self.client.userID
            self.reload()
            await self.recoverServerSession()
        }
    }

    func logout() {
        run {
            await self.client.logout()
            self.loggedIn = false
            self.userID = nil
        }
    }

    func requestCreate() {
        consent = ConsentRequest(kind: .create)
    }

    func requestJoin(_ text: String) {
        guard let input = JoinInput.parse(text, inviteHost: client.config.inviteHost) else {
            alert = AlertItem(title: "Not a join code", message: "Enter the 8-character code or paste the invite link.")
            return
        }
        consent = ConsentRequest(kind: .join(input))
    }

    /// The user accepted the consent text.
    func confirm(_ request: ConsentRequest) {
        consent = nil
        run {
            await self.ensureLibraryAccess()
            do {
                try await self.perform(request.kind)
            } catch PhotonError.alreadyInSession(let info) {
                self.conflict = SessionConflict(info: info, retry: request.kind)
            }
        }
    }

    private func perform(_ kind: ConsentRequest.Kind) async throws {
        switch kind {
        case .create: _ = try await client.createSession()
        case .join(let input): _ = try await client.join(input)
        case .resume(let info): _ = try await client.resume(info)
        }
        await client.didBecomeActive()
    }

    /// Leave the session the server still has, then retry the create or join.
    func leaveConflictAndContinue() {
        guard let c = conflict else { return }
        conflict = nil
        run {
            if let sid = c.info?.sessionID { try await self.client.leave(sessionID: sid) }
            if let retry = c.retry { try await self.perform(retry) }
        }
    }

    /// Rejoin the session the server still has, on this phone (after the consent text).
    func rejoinConflict() {
        guard let info = conflict?.info else { return }
        conflict = nil
        consent = ConsentRequest(kind: .resume(info))
    }

    func describe(_ c: SessionConflict) -> String {
        guard let info = c.info else {
            return "Your account is still in another session, which could not be looked up. Try again in a moment."
        }
        let role = info.role == "host" ? "the host" : "a member"
        let code = info.joinCode.isEmpty ? "" : " with code \(info.joinCode)"
        let then = c.retry == nil ? "" : " Leave it to continue."
        return "Your account is \(role) of a session\(code) that this phone is not syncing.\(then) You can also rejoin it here."
    }

    func leave() { run { try await self.client.leave() } }
    func endSession() { run { try await self.client.endSession() } }
    func kick(_ memberID: String) { run { try await self.client.kick(memberID: memberID) } }
    func rotateInvites() { run { _ = try await self.client.rotateInvites() } }
    func recall(_ sha256Hex: String) { run { try await self.client.recall(sha256Hex: sha256Hex) } }

    func report(_ sha256Hex: String, reason: String) {
        run {
            try await self.client.report(sha256Hex: sha256Hex, reason: reason)
            self.alert = AlertItem(title: "Reported", message: "Thank you. The report was sent to the operators.")
        }
    }

    func setPaused(_ paused: Bool) { run { await self.client.setPaused(paused) } }

    func setStripLocation(_ on: Bool) {
        Task { await client.updateSettings { $0.stripLocation = on }; reload() }
    }

    func setOriginals(_ policy: OriginalsPolicy) {
        Task { await client.updateSettings { $0.originals = policy }; reload() }
    }

    /// Universal Links (invites, the OAuth redirect) and the dev redirect.
    func open(_ url: URL) {
        run {
            switch try await self.client.handleOpenURL(url) {
            case .loggedIn:
                self.loggedIn = true
                self.userID = await self.client.userID
            case .invite(let input):
                if self.session == nil {
                    self.consent = ConsentRequest(kind: .join(input))
                } else {
                    self.alert = AlertItem(title: "Already in a session", message: "Leave this session before joining another.")
                }
            case .unknown:
                break
            }
        }
    }

    func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .active:
            env.observer.startIfAuthorized()
            Task {
                await client.didBecomeActive()
                reload()
            }
        case .background:
            Task { await client.didEnterBackground() }
            AppDelegate.scheduleRefresh()
        default:
            break
        }
    }

    func ensureLibraryAccess() async {
        if PhotoKitLibrary.authorization == .notDetermined {
            _ = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        }
        libraryStatus = PhotoKitLibrary.authorization
        env.observer.startIfAuthorized()
    }

    // MARK: - Helpers

    var isHost: Bool { session?.isHost ?? false }

    func canRecall(uploaderID: String) -> Bool {
        isHost || uploaderID == userID
    }

    static func message(for error: Error) -> String? {
        if let p = error as? PhotonError {
            switch p {
            case .loginCancelled: return nil
            case .loginRequired: return "Please sign in again."
            case .transport: return "No connection to PHOTON. Check your network and try again."
            case .alreadyInSession: return "You are already in another session. Leave it first."
            default: break
            }
        }
        if let e = error.apiError {
            switch e.code {
            case ErrorCode.alreadyInSession: return "You are already in another session. Leave it first."
            case ErrorCode.sessionNotFound: return "No session matches that code or link. It may have expired."
            case ErrorCode.sessionEnded: return "That session has ended."
            case ErrorCode.forbidden: return e.message.isEmpty ? "That is not allowed." : e.message
            case ErrorCode.rateLimited: return "Too many attempts. Wait a moment and try again."
            default: return e.message.isEmpty ? e.description : e.message
            }
        }
        return "\(error)"
    }
}
