import Foundation

/// The eligibility rules for uploading a library asset, applied by the reconcile scan
/// (and therefore by the PhotoKit observer, which only triggers a scan):
/// - created at or after the join time;
/// - not a screenshot;
/// - not saved by this app (echo layer 1) and not in the PHOTON album (echo layer 2);
/// - not already queued or synced.
public enum Eligibility {
    public static func isEligible(_ asset: LibraryAsset, session: SessionState, state: PhotonState,
                                  photonAlbum: Set<String>) -> Bool {
        guard let created = asset.creationDateMs, created >= session.joinTimeMs else { return false }
        if asset.isScreenshot { return false }
        if state.receivedAssets[asset.localID] != nil { return false }
        if photonAlbum.contains(asset.localID) { return false }
        if state.uploadJobs[asset.localID] != nil { return false }
        return true
    }
}

/// The reconcile scan: on launch, on return to the foreground, on resume from pause and
/// whenever the PhotoKit observer fires. iOS gives no promise while the app is suspended,
/// so this scan is what picks up photos taken meanwhile.
public struct Reconciler: Sendable {
    let store: PhotonStore
    let library: PhotoLibrary
    let clock: PhotonClock
    let onChange: ChangeHandler

    public init(store: PhotonStore, library: PhotoLibrary, clock: PhotonClock, onChange: @escaping ChangeHandler = { _ in }) {
        self.store = store
        self.library = library
        self.clock = clock
        self.onChange = onChange
    }

    /// Queues every eligible asset. Returns how many were added.
    @discardableResult
    public func scan() async throws -> Int {
        guard let session = store.read({ $0.session }), !session.paused else { return 0 }
        let assets = try await library.candidateAssets(createdAtOrAfterMs: session.joinTimeMs)
        let album = try await library.photonAlbumAssetIDs()
        let now = clock.nowMs()
        let added = try store.write { s -> Int in
            guard var sess = s.session, sess.sessionID == session.sessionID, !sess.paused else { return 0 }
            var count = 0
            for asset in assets where Eligibility.isEligible(asset, session: sess, state: s, photonAlbum: album) {
                s.uploadJobs[asset.localID] = UploadJob(id: asset.localID, sessionID: sess.sessionID,
                                                        takenAtMs: asset.creationDateMs ?? now, nowMs: now)
                count += 1
            }
            if let newest = assets.compactMap(\.creationDateMs).max(), newest > sess.scanCursorMs {
                sess.scanCursorMs = newest
                s.session = sess
            }
            return count
        }
        if added > 0 { onChange(.uploads) }
        return added
    }
}
