import Foundation

/// Everything the client persists outside the Keychain, as one value. It mirrors the
/// Android plan's Room tables (7.1): session_state, pending_photos, received_assets,
/// hashes and received_photos. A `PhotonStore` reads and writes it atomically.
public struct PhotonState: Codable, Sendable, Equatable {
    public var session: SessionState?
    /// Upload queue, keyed by PHAsset localIdentifier.
    public var uploadJobs: [String: UploadJob] = [:]
    /// Echo layer 4 and receive dedupe, keyed by lowercase sha256 hex.
    public var knownHashes: [String: KnownHash] = [:]
    /// Echo layer 1: assets this app saved, keyed by PHAsset localIdentifier.
    public var receivedAssets: [String: ReceivedAsset] = [:]
    /// Receive pipeline, keyed by lowercase sha256 hex.
    public var receivedPhotos: [String: ReceivedPhoto] = [:]
    public var settings = ClientSettings()

    public init() {}
}

public enum SessionRole: String, Codable, Sendable {
    case host, member
}

public struct SessionState: Codable, Sendable, Equatable {
    public var sessionID: String
    public var role: SessionRole
    public var hostID: String
    public var userID: String
    public var joinCode: String
    public var inviteURL: String
    public var qrPayload: String
    public var expiresAtMs: Int64
    public var memberIDs: [String]
    /// Device time when this user joined; only photos created at or after it are eligible.
    public var joinTimeMs: Int64
    /// Highest event id fully handled. nil means "load the snapshot first".
    public var lastEventID: String?
    /// Latest asset creation time the reconcile scan has seen.
    public var scanCursorMs: Int64
    /// Pause switch: nothing is enqueued or uploaded while set.
    public var paused: Bool

    public init(sessionID: String, role: SessionRole, hostID: String, userID: String, joinCode: String,
                inviteURL: String, qrPayload: String, expiresAtMs: Int64, memberIDs: [String],
                joinTimeMs: Int64, lastEventID: String? = nil, scanCursorMs: Int64 = 0, paused: Bool = false) {
        self.sessionID = sessionID
        self.role = role
        self.hostID = hostID
        self.userID = userID
        self.joinCode = joinCode
        self.inviteURL = inviteURL
        self.qrPayload = qrPayload
        self.expiresAtMs = expiresAtMs
        self.memberIDs = memberIDs
        self.joinTimeMs = joinTimeMs
        self.lastEventID = lastEventID
        self.scanCursorMs = scanCursorMs
        self.paused = paused
    }

    public var isHost: Bool { role == .host }
}

/// The upload queue's states, the same as Android's `pending_photos.state`.
public enum UploadJobState: String, Codable, Sendable {
    /// Found by the observer or the scan; not yet exported or hashed.
    case pending
    /// Exported (GPS removed) and hashed; ready to announce.
    case hashed
    /// Announced with result UPLOAD; chunks and `complete` in progress.
    case uploading
    /// Announce said IN_PROGRESS: another member is uploading the same bytes. Synced only
    /// when a PHOTO_STORED event for the hash arrives; re-announced later otherwise.
    case waiting
    case synced
    /// Not uploaded on purpose: echo of a received photo, recalled, session over.
    case skipped
    /// Permanently refused (422, 413, 415, uploads blocked).
    case failed

    public var isFinal: Bool { self == .synced || self == .skipped || self == .failed }
}

public struct UploadJob: Codable, Sendable, Equatable {
    /// PHAsset localIdentifier.
    public var id: String
    /// Directory name for this job's copy and chunk files (a UUID; local ids contain "/").
    public var key: String
    public var sessionID: String
    public var takenAtMs: Int64
    public var state: UploadJobState
    public var mime: String?
    /// File name of the exported copy inside the job's upload directory. The hash is over
    /// exactly this file, and it is what gets uploaded.
    public var copyFileName: String?
    public var sha256Hex: String?
    public var size: Int64?
    public var uploadID: String?
    /// Chosen once per upload_id and kept, because the server stores chunks by index.
    public var chunkSize: Int?
    public var chunkSizeHint: Int?
    /// Failed runs, for the persistent-queue backoff.
    public var attempts: Int
    /// Current IN_PROGRESS back-off step.
    public var waitDelayMs: Int64?
    /// Not processed before this time (WAITING re-announce or retry backoff).
    public var nextAttemptAtMs: Int64?
    public var lastError: String?
    public var createdAtMs: Int64
    public var updatedAtMs: Int64

    public init(id: String, key: String = UUID().uuidString, sessionID: String, takenAtMs: Int64, nowMs: Int64) {
        self.id = id
        self.key = key
        self.sessionID = sessionID
        self.takenAtMs = takenAtMs
        self.state = .pending
        self.attempts = 0
        self.createdAtMs = nowMs
        self.updatedAtMs = nowMs
    }
}

public enum HashSource: String, Codable, Sendable {
    case received, uploaded
}

public struct KnownHash: Codable, Sendable, Equatable {
    public var sha256Hex: String
    public var source: HashSource
    public var sessionID: String
    public var recordedAtMs: Int64

    public init(sha256Hex: String, source: HashSource, sessionID: String, recordedAtMs: Int64) {
        self.sha256Hex = sha256Hex
        self.source = source
        self.sessionID = sessionID
        self.recordedAtMs = recordedAtMs
    }
}

public struct ReceivedAsset: Codable, Sendable, Equatable {
    public var localID: String
    public var sha256Hex: String
    public var sessionID: String
    public var recordedAtMs: Int64

    public init(localID: String, sha256Hex: String, sessionID: String, recordedAtMs: Int64) {
        self.localID = localID
        self.sha256Hex = sha256Hex
        self.sessionID = sessionID
        self.recordedAtMs = recordedAtMs
    }
}

/// Receive pipeline states (Android `received_photos.state`, plus `pending` and `failed`).
public enum ReceivedPhotoState: String, Codable, Sendable {
    case pending, thumb, preview, saved, recalled, expired, failed

    public var isFinal: Bool { self == .saved || self == .recalled || self == .expired || self == .failed }
}

public struct ReceivedPhoto: Codable, Sendable, Equatable {
    public var sha256Hex: String
    public var sessionID: String
    public var uploaderID: String
    public var size: Int64
    public var takenAtMs: Int64
    public var expiresAtMs: Int64
    public var state: ReceivedPhotoState
    /// Relative to the cache root. Thumbnails and previews never go to the photo library.
    public var thumbPath: String?
    public var previewPath: String?
    /// The PHAsset this app created for the original.
    public var savedLocalID: String?
    /// The server answered 503 for the preview; keep the thumbnail.
    public var previewUnavailable: Bool
    public var attempts: Int
    public var nextAttemptAtMs: Int64?
    public var receivedAtMs: Int64

    public init(sha256Hex: String, sessionID: String, uploaderID: String, size: Int64, takenAtMs: Int64,
                expiresAtMs: Int64, receivedAtMs: Int64) {
        self.sha256Hex = sha256Hex
        self.sessionID = sessionID
        self.uploaderID = uploaderID
        self.size = size
        self.takenAtMs = takenAtMs
        self.expiresAtMs = expiresAtMs
        self.state = .pending
        self.previewUnavailable = false
        self.attempts = 0
        self.receivedAtMs = receivedAtMs
    }
}

/// When received originals are downloaded and saved.
public enum OriginalsPolicy: String, Codable, Sendable, CaseIterable {
    /// On any network except Low Data Mode.
    case always
    /// Only on Wi-Fi (unmetered) or while charging: the user setting from the design.
    case wifiOrCharging
}

public struct ClientSettings: Codable, Sendable, Equatable {
    /// Remove GPS from originals before upload. On by default, as the design proposes.
    public var stripLocation = true
    public var originals = OriginalsPolicy.always

    public init() {}
}
