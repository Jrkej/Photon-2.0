import Foundation

/// Applies session events exactly once in effect, although delivery is at-least-once:
/// an event at or below `last_event_id` is dropped, and a PHOTO_STORED whose hash this
/// device already holds is dropped. `last_event_id` is written only after the event is
/// handled ("handled" for a photo means its receive record is stored; downloads follow).
public actor EventProcessor {
    public enum Outcome: Sendable, Equatable {
        case handled
        case duplicate
        case sessionGone(SessionGoneReason)
    }

    private let store: PhotonStore
    private let uploads: UploadEngine
    private let receiver: ReceivePipeline
    private let clock: PhotonClock
    private let onChange: ChangeHandler
    private let log = PhotonLog("events")

    public init(store: PhotonStore, uploads: UploadEngine, receiver: ReceivePipeline, clock: PhotonClock,
                onChange: @escaping ChangeHandler = { _ in }) {
        self.store = store
        self.uploads = uploads
        self.receiver = receiver
        self.clock = clock
        self.onChange = onChange
    }

    public func handle(_ event: PbPhotoEvent) async -> Outcome {
        guard let session = store.read({ $0.session }) else { return .duplicate }
        if !event.sessionID.isEmpty && event.sessionID != session.sessionID { return .duplicate }
        let id = StreamID(event.eventID)
        if let id, let last = session.lastEventID.flatMap(StreamID.init), id <= last {
            return .duplicate
        }

        var outcome = Outcome.handled
        switch event.type {
        case .photoStored:
            await photoStored(event, session: session)
        case .photoRecalled:
            await receiver.recall(sha256Hex: event.sha256.hexString)
        case .memberJoined:
            updateMembers { if !$0.contains(event.memberID) { $0.append(event.memberID) } }
        case .memberLeft:
            if event.memberID == session.userID {
                outcome = .sessionGone(.removed)
            } else {
                updateMembers { $0.removeAll { $0 == event.memberID } }
            }
        case .sessionEnded:
            outcome = .sessionGone(.ended)
        case .hostChanged:
            // The host left; the role passed to the longest-present member.
            setHost(event.memberID)
        case .UNRECOGNIZED(let v):
            // A type added after this build: skip it, but still advance the cursor so it
            // is not fetched again forever.
            log.info("ignoring unknown event type \(v)")
        }

        if let id {
            _ = try? store.write { s in
                guard var sess = s.session, sess.sessionID == session.sessionID else { return }
                if sess.lastEventID.flatMap(StreamID.init).map({ id > $0 }) ?? true {
                    sess.lastEventID = id.description
                    s.session = sess
                }
            }
        }
        return outcome
    }

    /// Applies a snapshot (on join, on 4409 and on 410 RESYNC_REQUIRED): recalled hashes
    /// first, then live photos (deduplicated by hash; they carry no event id), the member
    /// list, and finally the snapshot's cursor, which replaces the local one.
    public func apply(snapshot: PbSnapshot) async {
        guard let session = store.read({ $0.session }) else { return }
        for sha in snapshot.recalledSha256 {
            await receiver.recall(sha256Hex: sha.hexString)
        }
        let recalled = Set(snapshot.recalledSha256.map(\.hexString))
        for photo in snapshot.livePhotos where !recalled.contains(photo.sha256.hexString) {
            await photoStored(photo, session: session)
        }
        let cursor = snapshot.cursor.isEmpty ? StreamID.zero.description : snapshot.cursor
        _ = try? store.write { s in
            guard var sess = s.session, sess.sessionID == session.sessionID else { return }
            sess.memberIDs = snapshot.memberIds
            sess.lastEventID = cursor
            s.session = sess
        }
        onChange(.session)
    }

    /// Applies `SessionInfo` (role, host, members, invite) to the stored session. Used after
    /// a snapshot, which carries no host: a HOST_CHANGED trimmed from the stream before a
    /// resync would otherwise leave a stale role.
    public func apply(info: PbSessionInfo) {
        _ = try? store.write { s in
            guard var sess = s.session, sess.sessionID == info.sessionID else { return }
            if !info.role.isEmpty { sess.role = info.role == "host" ? .host : .member }
            if !info.hostID.isEmpty { sess.hostID = info.hostID }
            if !info.memberIds.isEmpty { sess.memberIDs = info.memberIds }
            if !info.joinCode.isEmpty { sess.joinCode = info.joinCode }
            if !info.inviteURL.isEmpty { sess.inviteURL = info.inviteURL }
            if !info.qrPayload.isEmpty { sess.qrPayload = info.qrPayload }
            if info.expiresAtMs > 0 { sess.expiresAtMs = info.expiresAtMs }
            s.session = sess
        }
        onChange(.session)
    }

    private func setHost(_ hostID: String) {
        guard !hostID.isEmpty else { return }
        _ = try? store.write { s in
            guard var sess = s.session else { return }
            sess.hostID = hostID
            sess.role = hostID == sess.userID ? .host : .member
            if !sess.memberIDs.contains(hostID) { sess.memberIDs.append(hostID) }
            s.session = sess
        }
        onChange(.session)
    }

    private func photoStored(_ event: PbPhotoEvent, session: SessionState) async {
        let hex = event.sha256.hexString
        guard event.sha256.count == 32 else { return }
        // Whoever uploaded it, a local job waiting on these bytes is now synced.
        await uploads.photoStored(sha256Hex: hex)
        let now = clock.nowMs()
        let created = (try? store.write { s -> Bool in
            if event.uploaderID == session.userID {
                // Our own upload coming back.
                if s.knownHashes[hex] == nil {
                    s.knownHashes[hex] = KnownHash(sha256Hex: hex, source: .uploaded, sessionID: session.sessionID, recordedAtMs: now)
                }
                return false
            }
            if s.knownHashes[hex] != nil || s.receivedPhotos[hex] != nil { return false }
            if s.uploadJobs.values.contains(where: { $0.sha256Hex == hex }) { return false }
            if event.expiresAtMs > 0 && event.expiresAtMs <= now { return false }
            s.receivedPhotos[hex] = ReceivedPhoto(sha256Hex: hex, sessionID: session.sessionID, uploaderID: event.uploaderID,
                                                  size: Int64(event.size), takenAtMs: event.takenAtMs,
                                                  expiresAtMs: event.expiresAtMs > 0 ? event.expiresAtMs : now + 30 * 60_000,
                                                  receivedAtMs: now)
            return true
        }) ?? false
        if created { onChange(.received) }
    }

    private func updateMembers(_ body: (inout [String]) -> Void) {
        _ = try? store.write { s in
            guard var sess = s.session else { return }
            body(&sess.memberIDs)
            s.session = sess
        }
        onChange(.session)
    }
}
