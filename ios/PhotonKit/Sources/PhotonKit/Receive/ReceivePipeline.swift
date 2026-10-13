import Foundation

/// Receives other members' photos (Android's ReceivePipeline, 7.9). Events only create
/// `ReceivedPhoto` records (durably, before the event's id is committed); this pipeline then
/// works through them in passes, so every thumbnail shows before any original is fetched:
/// 1. thumbnails, into the app cache;
/// 2. previews, by network (503 keeps the thumbnail);
/// 3. originals, by network and setting, saved into the PHOTON album. The hash and the new
///    asset's localIdentifier are recorded before the asset becomes visible (echo layers 4
///    and 1).
/// Recall deletes cached copies; saved originals are never deleted.
public actor ReceivePipeline {
    public enum RunResult: Sendable, Equatable { case finished, alreadyRunning, offline, sessionGone(SessionGoneReason) }

    private let api: APIClient
    private let store: PhotonStore
    private let library: PhotoLibrary
    private let files: FileLayout
    private let network: NetworkMonitor
    private let clock: PhotonClock
    private let tuning: PhotonTuning
    private let onChange: ChangeHandler
    private let log = PhotonLog("receive")

    private var running = false
    private var rerun = false
    private var downlink = ThroughputEstimator()

    public init(api: APIClient, store: PhotonStore, library: PhotoLibrary, files: FileLayout, network: NetworkMonitor,
                clock: PhotonClock, tuning: PhotonTuning = PhotonTuning(), onChange: @escaping ChangeHandler = { _ in }) {
        self.api = api
        self.store = store
        self.library = library
        self.files = files
        self.network = network
        self.clock = clock
        self.tuning = tuning
        self.onChange = onChange
    }

    private enum Stop: Error { case sessionGone(SessionGoneReason) }

    public func run() async -> RunResult {
        if running {
            rerun = true
            return .alreadyRunning
        }
        running = true
        defer { running = false }
        do {
            repeat {
                rerun = false
                guard let session = store.read({ $0.session }) else { return .finished }
                expireDue()
                let status = network.current
                guard status.isConnected else { return .offline }
                let policy = ReceivePolicy(originals: store.read { $0.settings.originals },
                                           previewMinBitsPerSecond: tuning.previewMinBitsPerSecond)
                for hex in due(session, { $0.state == .pending }) where policy.fetchThumbnail(network.current) {
                    try await fetchDerived(hex, session: session, size: .thumb)
                }
                for hex in due(session, { $0.state == .thumb && !$0.previewUnavailable })
                    where policy.fetchPreview(network.current, measuredBitsPerSecond: downlink.bytesPerSecond.map { $0 * 8 }) {
                    try await fetchDerived(hex, session: session, size: .preview)
                }
                for hex in due(session, { ($0.state == .thumb || $0.state == .preview) && $0.savedLocalID == nil })
                    where policy.fetchOriginal(network.current) {
                    try await fetchOriginal(hex, session: session)
                }
            } while rerun && !Task.isCancelled
        } catch Stop.sessionGone(let reason) {
            onChange(.sessionGone(reason))
            return .sessionGone(reason)
        } catch {
            return .finished
        }
        return .finished
    }

    /// When a deferred retry is next due, if any.
    public func nextDueAtMs() -> Int64? {
        let now = clock.nowMs()
        return store.read { s in
            s.receivedPhotos.values.filter { !$0.state.isFinal }.compactMap(\.nextAttemptAtMs).filter { $0 > now }.min()
        }
    }

    /// PHOTO_RECALLED (live or from the snapshot): drop cached copies, stop any pending
    /// download, and remember the recall. A saved original stays: it is the user's photo now.
    public func recall(sha256Hex hex: String) {
        let previous = store.read { $0.receivedPhotos[hex] }
        _ = try? store.write { s in
            guard var p = s.receivedPhotos[hex] else { return }
            if p.state != .saved { p.state = .recalled }
            p.thumbPath = nil
            p.previewPath = nil
            s.receivedPhotos[hex] = p
        }
        files.removeCached(relativePath: previous?.thumbPath)
        files.removeCached(relativePath: previous?.previewPath)
        if previous != nil { onChange(.received) }
    }

    /// Session over: delete every cached thumbnail, preview and partial download. Hashes
    /// and received asset ids are kept (and purged after 24 hours) for echo prevention.
    public func removeAll() {
        let photos = store.read { Array($0.receivedPhotos.values) }
        for p in photos {
            files.removeCached(relativePath: p.thumbPath)
            files.removeCached(relativePath: p.previewPath)
        }
        try? FileManager.default.removeItem(at: files.downloadsDirectory)
        _ = try? store.write { $0.receivedPhotos.removeAll() }
        onChange(.received)
    }

    // MARK: - Steps

    private func due(_ session: SessionState, _ filter: (ReceivedPhoto) -> Bool) -> [String] {
        let now = clock.nowMs()
        return store.read { s in
            s.receivedPhotos.values
                .filter { $0.sessionID == session.sessionID && filter($0) && ($0.nextAttemptAtMs ?? 0) <= now && $0.expiresAtMs > now }
                .sorted { $0.receivedAtMs < $1.receivedAtMs }
                .map(\.sha256Hex)
        }
    }

    /// After `expires_at` no download can succeed: drop the cached copies of every expired
    /// photo and stop trying.
    private func expireDue() {
        let now = clock.nowMs()
        let expired = store.read { s in s.receivedPhotos.values.filter { $0.expiresAtMs <= now && ($0.thumbPath != nil || $0.previewPath != nil || !$0.state.isFinal) } }
        guard !expired.isEmpty else { return }
        for p in expired { expire(p.sha256Hex) }
    }

    private func expire(_ hex: String) {
        let previous = store.read { $0.receivedPhotos[hex] }
        _ = try? store.write { s in
            guard var p = s.receivedPhotos[hex] else { return }
            if !p.state.isFinal { p.state = .expired }
            p.thumbPath = nil
            p.previewPath = nil
            s.receivedPhotos[hex] = p
        }
        files.removeCached(relativePath: previous?.thumbPath)
        files.removeCached(relativePath: previous?.previewPath)
        onChange(.received)
    }

    private func fetchDerived(_ hex: String, session: SessionState, size: APIClient.PhotoSize) async throws {
        let started = clock.nowMs()
        do {
            let (data, _) = try await api.download(sessionID: session.sessionID, sha256Hex: hex, size: size)
            downlink.record(bytes: data.count, milliseconds: clock.nowMs() - started)
            let path = size == .thumb ? FileLayout.thumbPath(hex) : FileLayout.previewPath(hex)
            try files.writeCached(data, relativePath: path)
            let stillWanted = try store.write { s -> Bool in
                guard var p = s.receivedPhotos[hex], p.state != .recalled, p.state != .expired else { return false }
                if size == .thumb {
                    p.thumbPath = path
                    if p.state == .pending { p.state = .thumb }
                } else {
                    p.previewPath = path
                    if p.state == .thumb { p.state = .preview }
                }
                p.attempts = 0
                p.nextAttemptAtMs = nil
                s.receivedPhotos[hex] = p
                return true
            }
            if !stillWanted { files.removeCached(relativePath: path) }
            onChange(.received)
        } catch {
            try handle(error, hex: hex, size: size)
        }
    }

    private func fetchOriginal(_ hex: String, session: SessionState) async throws {
        guard let photo = store.read({ $0.receivedPhotos[hex] }) else { return }
        let started = clock.nowMs()
        do {
            let (data, mime) = try await api.download(sessionID: session.sessionID, sha256Hex: hex, size: .full)
            downlink.record(bytes: data.count, milliseconds: clock.nowMs() - started)
            // The server verified these bytes; check again so a corrupted transfer is never
            // saved into the user's library.
            guard SHA256Hasher.hash(data).hexString == hex else {
                throw PhotonError.transport("original does not match its hash")
            }
            guard store.read({ $0.receivedPhotos[hex].map { $0.state != .recalled && $0.state != .expired } ?? false }) else { return }

            try FileManager.default.createDirectory(at: files.downloadsDirectory, withIntermediateDirectories: true)
            let file = files.downloadsDirectory.appendingPathComponent("\(hex).\(fileExtension(forMime: mime))")
            try data.write(to: file, options: [.atomic])
            defer { try? FileManager.default.removeItem(at: file) }

            let now = clock.nowMs()
            let sid = session.sessionID
            // Echo layer 4 before the asset exists.
            try store.write { s in
                s.knownHashes[hex] = KnownHash(sha256Hex: hex, source: .received, sessionID: sid, recordedAtMs: now)
            }
            let store = self.store
            let localID = try await library.saveToPhotonAlbum(fileURL: file, mime: mime, takenAtMs: photo.takenAtMs) { localID in
                // Echo layer 1, inside the PhotoKit change block.
                try store.write { s in
                    s.receivedAssets[localID] = ReceivedAsset(localID: localID, sha256Hex: hex, sessionID: sid, recordedAtMs: now)
                }
            }
            try store.write { s in
                guard var p = s.receivedPhotos[hex] else { return }
                p.savedLocalID = localID
                p.state = .saved
                p.attempts = 0
                p.nextAttemptAtMs = nil
                s.receivedPhotos[hex] = p
            }
            log.info("saved original \(shortHash(hex))")
            onChange(.received)
        } catch {
            try handle(error, hex: hex, size: .full)
        }
    }

    private func handle(_ error: Error, hex: String, size: APIClient.PhotoSize) throws {
        if error is CancellationError { throw error }
        if let e = error.apiError {
            switch (e.status, e.code) {
            case (410, ErrorCode.photoRecalled): recall(sha256Hex: hex); return
            case (410, ErrorCode.photoExpired): expire(hex); return
            case (410, ErrorCode.sessionEnded): throw Stop.sessionGone(.ended)
            case (403, ErrorCode.notAMember): throw Stop.sessionGone(.removed)
            case (404, _):
                mark(hex) { $0.state = .failed }
                return
            case (503, _) where size == .preview:
                // Preview generation failed on the server: keep the thumbnail.
                mark(hex) { $0.previewUnavailable = true }
                return
            default: break
            }
        }
        if case PhotoLibraryError.accessDenied = error {
            log.error("no photo library access; original \(shortHash(hex)) not saved")
        }
        let now = clock.nowMs()
        let backoff = tuning.jobBackoff
        mark(hex) { p in
            p.attempts += 1
            p.nextAttemptAtMs = now + backoff.delayMs(attempt: p.attempts - 1, retryAfterSeconds: error.retryAfterSeconds)
        }
        log.info("receive \(shortHash(hex)) \(size.rawValue) failed: \(error)")
    }

    private func mark(_ hex: String, _ body: (inout ReceivedPhoto) -> Void) {
        _ = try? store.write { s in
            guard var p = s.receivedPhotos[hex] else { return }
            body(&p)
            s.receivedPhotos[hex] = p
        }
        onChange(.received)
    }
}
