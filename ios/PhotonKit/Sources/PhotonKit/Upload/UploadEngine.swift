import Foundation

/// The upload queue (Android's UploadWorker, 7.7). For each job, in order of capture time:
///
/// 1. **Copy and hash**: export the original with GPS removed and hash exactly that copy.
///    A hash already known locally (received or uploaded) is skipped: echo layer 4.
/// 2. **Announce**: ALREADY_UPLOADED → synced. IN_PROGRESS → WAITING (synced only when a
///    PHOTO_STORED event for the hash arrives; re-announced after 2 minutes, backing off to
///    15). UPLOAD → keep the upload_id and the fresh token.
/// 3. **Resume**: `GET /v1/uploads/{id}`; `server_has_file` goes straight to complete.
/// 4. **Chunks**: the missing ones, 3 in parallel, each with its CRC32C, each retried up to
///    3 times with backoff and jitter; after that the job stays queued and resumes later.
/// 5. **Complete**, in a loop: 202 and 503 wait for Retry-After; 400 sends the listed missing
///    chunks; 200 → synced; 422 → failed; 409 → re-announce; 410 SESSION_ENDED → stop.
///
/// Every step persists the job before moving on, so a kill at any point resumes cleanly.
/// Upload tokens are kept in memory only: after a restart, announce (idempotent while the
/// reservation lives) returns the same upload_id with a fresh token.
public actor UploadEngine {
    public enum RunResult: Sendable, Equatable {
        case finished
        case alreadyRunning
        case paused
        case offline
        case sessionGone(SessionGoneReason)
        case loginRequired
    }

    private let api: APIClient
    private let store: PhotonStore
    private let library: PhotoLibrary
    private let uploader: ChunkUploader
    private let files: FileLayout
    private let network: NetworkMonitor
    private let clock: PhotonClock
    private let tuning: PhotonTuning
    private let onChange: ChangeHandler
    private let log = PhotonLog("upload")

    private var tokens: [String: String] = [:]          // upload_id -> upload token
    private var storedHashes: Set<String> = []           // PHOTO_STORED seen this run
    private var throughput = ThroughputEstimator()
    private var running = false
    private var rerun = false
    private var activeJobID: String?

    public init(api: APIClient, store: PhotonStore, library: PhotoLibrary, uploader: ChunkUploader, files: FileLayout,
                network: NetworkMonitor, clock: PhotonClock, tuning: PhotonTuning = PhotonTuning(),
                onChange: @escaping ChangeHandler = { _ in }) {
        self.api = api
        self.store = store
        self.library = library
        self.uploader = uploader
        self.files = files
        self.network = network
        self.clock = clock
        self.tuning = tuning
        self.onChange = onChange
    }

    // MARK: - Queue

    /// Processes every due job. A call while a run is in progress makes that run go round
    /// once more and returns `.alreadyRunning`.
    public func run() async -> RunResult {
        if running {
            rerun = true
            return .alreadyRunning
        }
        running = true
        defer { running = false }
        repeat {
            rerun = false
            guard let session = store.read({ $0.session }) else { return .finished }
            if session.paused { return .paused }
            if !network.current.isConnected { return .offline }
            let now = clock.nowMs()
            let due = store.read { state in
                state.uploadJobs.values
                    .filter { !$0.state.isFinal && $0.sessionID == session.sessionID && ($0.nextAttemptAtMs ?? 0) <= now }
                    .sorted { ($0.takenAtMs, $0.createdAtMs) < ($1.takenAtMs, $1.createdAtMs) }
                    .map(\.id)
            }
            for id in due {
                if Task.isCancelled { return .finished }
                if store.read({ $0.session?.paused ?? true }) { return .paused }
                switch await runJob(id: id) {
                case .ok: continue
                case .sessionGone(let reason):
                    onChange(.sessionGone(reason))
                    return .sessionGone(reason)
                case .loginRequired:
                    onChange(.loginRequired)
                    return .loginRequired
                case .cancelled:
                    return .finished
                }
            }
        } while rerun
        return .finished
    }

    /// When the queue next has work (a WAITING re-announce or a retry), if ever.
    public func nextDueAtMs() -> Int64? {
        let now = clock.nowMs()
        return store.read { state in
            state.uploadJobs.values.filter { !$0.state.isFinal }.compactMap(\.nextAttemptAtMs).filter { $0 > now }.min()
        }
    }

    /// A PHOTO_STORED event: any unsynced job with this hash is now in the session, whoever
    /// uploaded it. This is what moves WAITING to SYNCED.
    public func photoStored(sha256Hex hex: String) {
        storedHashes.insert(hex)
        let now = clock.nowMs()
        let finished: [UploadJob] = (try? store.write { state in
            var done: [UploadJob] = []
            for (id, job) in state.uploadJobs where job.sha256Hex == hex && !job.state.isFinal && id != activeJobID {
                var j = job
                j.state = .synced
                j.nextAttemptAtMs = nil
                j.updatedAtMs = now
                state.uploadJobs[id] = j
                done.append(j)
            }
            if !done.isEmpty, state.knownHashes[hex] == nil, let sid = done.first?.sessionID {
                state.knownHashes[hex] = KnownHash(sha256Hex: hex, source: .uploaded, sessionID: sid, recordedAtMs: now)
            }
            return done
        }) ?? []
        for job in finished {
            files.removeUploadDirectory(key: job.key)
            if let id = job.uploadID { tokens[id] = nil }
        }
        if !finished.isEmpty { onChange(.uploads) }
    }

    /// Session over: forget every job and delete the copies and chunk files.
    public func removeAllJobs() {
        let jobs = store.read { Array($0.uploadJobs.values) }
        for job in jobs { files.removeUploadDirectory(key: job.key) }
        _ = try? store.write { $0.uploadJobs.removeAll() }
        tokens.removeAll()
        storedHashes.removeAll()
        onChange(.uploads)
    }

    // MARK: - One job

    private enum JobOutcome { case ok, sessionGone(SessionGoneReason), loginRequired, cancelled }

    private enum ErrorAction {
        case reannounce
        case retryLater
        case final(UploadJobState)
        case sessionGone(SessionGoneReason)
        case loginRequired
        case cancelled
    }

    private func runJob(id: String) async -> JobOutcome {
        guard var job = store.read({ $0.uploadJobs[id] }) else { return .ok }
        activeJobID = id
        defer { activeJobID = nil }
        var reannounces = 0
        while true {
            if job.state.isFinal || job.state == .waiting && (job.nextAttemptAtMs ?? 0) > clock.nowMs() { return .ok }
            if let hex = job.sha256Hex, storedHashes.contains(hex) {
                finish(&job, .synced, reason: "stored event")
                return .ok
            }
            do {
                try Task.checkCancellation()
                switch job.state {
                case .pending:
                    try await prepare(&job)
                case .hashed, .waiting:
                    try await announce(&job)
                case .uploading:
                    guard let uploadID = job.uploadID, let token = tokens[uploadID] else {
                        // No token in memory (restart, or it expired): announce again for a
                        // fresh one; the reservation's upload_id comes back unchanged.
                        job.state = .hashed
                        save(job)
                        continue
                    }
                    try await transfer(&job, uploadID: uploadID, token: token)
                case .synced, .skipped, .failed:
                    return .ok
                }
            } catch {
                switch classify(error, job: job) {
                case .reannounce:
                    reannounces += 1
                    if let uploadID = job.uploadID { tokens[uploadID] = nil }
                    if reannounces > tuning.maxReannouncesPerRun {
                        deferJob(&job, error)
                        return .ok
                    }
                    log.info("re-announce \(shortHash(job.sha256Hex ?? "")) after \(error)")
                    job.state = .hashed
                    save(job)
                case .retryLater:
                    deferJob(&job, error)
                    return .ok
                case .final(let state):
                    if case .api(let e) = error as? PhotonError, e.status == 422 {
                        onChange(.uploadRejected(localID: job.id, code: e.code))
                    }
                    finish(&job, state, reason: "\(error)")
                    return .ok
                case .sessionGone(let reason):
                    finish(&job, .skipped, reason: "\(error)")
                    return .sessionGone(reason)
                case .loginRequired:
                    return .loginRequired
                case .cancelled:
                    return .cancelled
                }
            }
        }
    }

    /// Maps an error to what the queue does next, following the error contract.
    private func classify(_ error: Error, job: UploadJob) -> ErrorAction {
        if error is CancellationError { return .cancelled }
        if let p = error as? PhotonError {
            switch p {
            case .loginRequired: return .loginRequired
            case .transport, .invalidResponse: return .retryLater
            case .noSession: return .sessionGone(.ended)
            case .api: break
            default: return .retryLater
            }
        }
        if let lib = error as? PhotoLibraryError {
            switch lib {
            case .assetNotFound, .unsupportedFormat: return .final(.skipped)
            case .accessDenied: return .retryLater
            case .exportFailed, .saveFailed: return job.attempts >= 4 ? .final(.failed) : .retryLater
            }
        }
        guard let e = error.apiError else {
            return job.attempts >= 4 ? .final(.failed) : .retryLater
        }
        switch (e.status, e.code) {
        case (401, _):
            // With an upload token this is the token's 10-minute expiry; announce mints a
            // new one. (User-JWT calls already refreshed once inside APIClient.)
            return job.state == .uploading ? .reannounce : .retryLater
        case (403, ErrorCode.notAMember): return .sessionGone(.removed)
        case (403, _): return .final(.failed)                       // uploads blocked (strikes)
        case (404, ErrorCode.unknownUpload): return .reannounce
        case (404, _): return .final(.skipped)
        case (409, ErrorCode.uploadSuperseded), (409, ErrorCode.uploadFinalized): return .reannounce
        case (410, ErrorCode.sessionEnded): return .sessionGone(.ended)
        // The lease ran out, typically because iOS suspended the app mid-upload. The photo
        // is still unsynced, so announce again rather than dropping it.
        case (410, ErrorCode.uploadExpired): return .reannounce
        case (410, _): return .final(.skipped)                      // recalled or expired
        case (413, _), (415, _), (422, _): return .final(.failed)
        default:
            if e.retryable || e.status == 429 || e.status >= 500 { return .retryLater }
            return .final(.failed)
        }
    }

    // MARK: Steps

    private func prepare(_ job: inout UploadJob) async throws {
        let dir = files.uploadDirectory(key: job.key)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let strip = store.read { $0.settings.stripLocation }
        let exported = try await library.exportOriginal(localID: job.id, to: dir, stripLocation: strip)
        let (digest, size) = try SHA256Hasher.hashFile(at: exported.fileURL)
        guard size > 0 else { throw PhotoLibraryError.exportFailed("empty file") }
        let hex = digest.hexString
        job.copyFileName = exported.fileURL.lastPathComponent
        job.mime = exported.mime
        job.size = size
        job.sha256Hex = hex
        if exported.takenAtMs > 0 { job.takenAtMs = exported.takenAtMs }
        if store.read({ $0.knownHashes[hex] != nil || $0.receivedPhotos[hex] != nil }) {
            // Echo layer 4: a photo this device received or already uploaded.
            finish(&job, .skipped, reason: "hash already known")
            return
        }
        job.state = .hashed
        save(job)
    }

    private func announce(_ job: inout UploadJob) async throws {
        guard let hex = job.sha256Hex, let sha = Data(hexString: hex), let size = job.size, let mime = job.mime else {
            job.state = .pending
            save(job)
            return
        }
        var request = PbAnnounceRequest()
        request.sha256 = sha
        request.size = UInt64(size)
        request.mime = mime
        request.takenAtMs = job.takenAtMs
        request.sessionID = job.sessionID
        let response = try await api.announce(request)
        let now = clock.nowMs()
        switch response.result {
        case .alreadyUploaded:
            finish(&job, .synced, reason: "already uploaded")
        case .inProgress:
            // Someone else is uploading these bytes. Not synced yet: if their upload is
            // rejected (other bytes under this hash), this device must still upload.
            let delay = job.state == .waiting
                ? min((job.waitDelayMs ?? tuning.waitingFirstDelayMs) * 2, tuning.waitingMaxDelayMs)
                : tuning.waitingFirstDelayMs
            job.state = .waiting
            job.waitDelayMs = delay
            job.nextAttemptAtMs = now + delay
            job.updatedAtMs = now
            save(job)
        case .upload:
            guard !response.uploadID.isEmpty, !response.uploadToken.isEmpty else {
                throw PhotonError.invalidResponse("announce UPLOAD without upload_id or token")
            }
            if job.uploadID != response.uploadID {
                if let old = job.uploadID { tokens[old] = nil }
                job.uploadID = response.uploadID
                job.chunkSize = nil
                try? FileManager.default.removeItem(at: files.uploadDirectory(key: job.key).appendingPathComponent("chunks"))
            }
            tokens[response.uploadID] = response.uploadToken
            job.chunkSizeHint = response.chunkSizeHint > 0 ? Int(response.chunkSizeHint) : nil
            job.state = .uploading
            job.waitDelayMs = nil
            job.nextAttemptAtMs = nil
            job.updatedAtMs = now
            save(job)
        case .UNRECOGNIZED(let v):
            throw PhotonError.invalidResponse("announce result \(v)")
        }
    }

    private func transfer(_ job: inout UploadJob, uploadID: String, token: String) async throws {
        guard let name = job.copyFileName, let size = job.size else {
            job.state = .pending
            save(job)
            return
        }
        let copy = files.uploadDirectory(key: job.key).appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: copy.path) else {
            // The copy is gone (storage cleared): export and hash again.
            job.state = .pending
            save(job)
            return
        }
        if job.chunkSize == nil {
            job.chunkSize = ChunkPlan.chooseChunkSize(fileSize: size, measuredBytesPerSecond: throughput.bytesPerSecond,
                                                      hint: job.chunkSizeHint, tuning: tuning)
            save(job)
        }
        let plan = ChunkPlan(fileSize: size, chunkSize: job.chunkSize!)

        let status = try await api.uploadStatus(uploadID: uploadID, token: token)
        if (status.state.isEmpty || status.state == "RECEIVING") && !status.serverHasFile {
            let missing = plan.missing(received: status.receivedChunks)
            if !missing.isEmpty {
                try await sendChunks(missing, plan: plan, job: job, copy: copy, uploadID: uploadID, token: token)
            }
        }
        // Any other state (VERIFYING, REPORTING, STORED, REJECTED, ...) is answered by
        // complete itself, which is idempotent.
        try await completeLoop(&job, plan: plan, copy: copy, uploadID: uploadID, token: token)
    }

    private func completeLoop(_ job: inout UploadJob, plan: ChunkPlan, copy: URL, uploadID: String, token: String) async throws {
        var missingRounds = 0
        for poll in 0..<tuning.maxCompletePolls {
            do {
                switch try await api.complete(uploadID: uploadID, token: token, chunkCount: plan.count) {
                case .stored:
                    finish(&job, .synced, reason: nil)
                    return
                case .pending(let seconds):
                    try await clock.sleep(ms: Int64(max(seconds, 0.5) * 1000))
                }
            } catch let error {
                guard let e = error.apiError else { throw error }
                if e.status == 400, !e.missingChunks.isEmpty {
                    missingRounds += 1
                    guard missingRounds <= 3 else { throw PhotonError.transport("chunks still missing after resend") }
                    let missing = e.missingChunks.map(Int.init).filter { $0 >= 0 && $0 < plan.count }
                    try await sendChunks(missing, plan: plan, job: job, copy: copy, uploadID: uploadID, token: token)
                } else if e.status == 503 || (e.retryable && e.status != 400) {
                    // PROCESSING_FAILED (thumbnail) or BUSY: the next complete resumes there.
                    try await clock.sleep(ms: tuning.jobBackoff.delayMs(attempt: min(poll, 3), retryAfterSeconds: e.retryAfterSeconds))
                } else {
                    throw error
                }
            }
        }
        throw PhotonError.transport("complete still pending")
    }

    /// Sends `indices`, at most `parallelChunks` at a time. After the first chunk that
    /// fails for good, no new chunk starts, but those already in flight finish, so the
    /// next run has as little as possible left to send.
    private func sendChunks(_ indices: [Int], plan: ChunkPlan, job: UploadJob, copy: URL, uploadID: String, token: String) async throws {
        let base = api.config.imageBase.appendingPathComponent(api.uploadPath(uploadID))
        let key = job.key
        let parallel = max(1, tuning.parallelChunks)
        let failure: Error? = await withTaskGroup(of: Error?.self) { group in
            var pending = indices.makeIterator()
            func addNext() -> Bool {
                guard let index = pending.next() else { return false }
                let chunkURL = base.appendingPathComponent("chunks/\(index)")
                let file = files.chunkFile(key: key, index: index)
                let range = plan.range(of: index)
                group.addTask {
                    do {
                        try await self.sendChunk(index: index, range: range, copy: copy, file: file, url: chunkURL,
                                                 uploadID: uploadID, token: token)
                        return nil
                    } catch {
                        return error
                    }
                }
                return true
            }
            for _ in 0..<parallel where !addNext() { break }
            var first: Error?
            while let result = await group.next() {
                if let result, first == nil { first = result }
                if first == nil { _ = addNext() }
            }
            return first
        }
        if let failure { throw failure }
    }

    private nonisolated func sendChunk(index: Int, range: Range<Int64>, copy: URL, file: URL, url: URL,
                                       uploadID: String, token: String) async throws {
        let (crc, length) = try ChunkFiles.prepare(source: copy, range: range, destination: file)
        let chunk = ChunkUpload(uploadID: uploadID, index: index, url: url, token: token, crc32cHeader: crc,
                                fileURL: file, length: length)
        var attempt = 0
        while true {
            try Task.checkCancellation()
            let started = clock.nowMs()
            do {
                try await uploader.upload(chunk)
                await recordThroughput(bytes: length, ms: clock.nowMs() - started)
                try? FileManager.default.removeItem(at: file)
                return
            } catch {
                attempt += 1
                // A CRC mismatch comes back as a retryable 400: resend the same chunk.
                guard error.isTransient, attempt < tuning.chunkAttempts else { throw error }
                try await clock.sleep(ms: tuning.chunkBackoff.delayMs(attempt: attempt - 1,
                                                                      retryAfterSeconds: error.retryAfterSeconds))
            }
        }
    }

    private func recordThroughput(bytes: Int, ms: Int64) {
        throughput.record(bytes: bytes, milliseconds: ms)
    }

    // MARK: Persistence helpers

    private func save(_ job: UploadJob) {
        var j = job
        j.updatedAtMs = clock.nowMs()
        _ = try? store.write { state in
            // A job removed meanwhile (session cleared) stays removed.
            guard state.uploadJobs[j.id] != nil else { return }
            state.uploadJobs[j.id] = j
        }
        onChange(.uploads)
    }

    private func finish(_ job: inout UploadJob, _ state: UploadJobState, reason: String?) {
        let now = clock.nowMs()
        job.state = state
        job.lastError = state == .synced ? nil : reason
        job.nextAttemptAtMs = nil
        job.updatedAtMs = now
        let j = job
        _ = try? store.write { s in
            guard s.uploadJobs[j.id] != nil else { return }
            s.uploadJobs[j.id] = j
            if state == .synced, let hex = j.sha256Hex {
                s.knownHashes[hex] = KnownHash(sha256Hex: hex, source: .uploaded, sessionID: j.sessionID, recordedAtMs: now)
            }
        }
        files.removeUploadDirectory(key: job.key)
        if let id = job.uploadID { tokens[id] = nil }
        log.info("job \(shortHash(job.sha256Hex ?? job.key)) -> \(state.rawValue)\(reason.map { " (\($0))" } ?? "")")
        onChange(.uploads)
    }

    private func deferJob(_ job: inout UploadJob, _ error: Error) {
        job.attempts += 1
        let delay = tuning.jobBackoff.delayMs(attempt: job.attempts - 1, retryAfterSeconds: error.retryAfterSeconds)
        job.nextAttemptAtMs = clock.nowMs() + delay
        job.lastError = "\(error)"
        log.info("job \(shortHash(job.sha256Hex ?? job.key)) retry in \(delay) ms: \(error)")
        save(job)
    }
}
