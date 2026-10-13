import Foundation

/// Why the client stopped syncing a session.
public enum SessionGoneReason: String, Sendable, Equatable {
    /// The host ended it, it timed out, or the server answered 410 SESSION_ENDED (close 4410).
    case ended
    /// Kicked, or no longer a member (403 NOT_A_MEMBER, close 4403).
    case removed
    /// This user left.
    case left
}

public enum ConnectionState: Sendable, Equatable {
    case idle
    case connecting
    /// The server sent `ready_cursor`; events arrive live.
    case live
    /// Disconnected; reconnecting after the delay.
    case waiting(retryInMs: Int64)
}

/// What changed, so the UI knows what to reload from the store.
public enum PhotonChange: Sendable, Equatable {
    case session
    case uploads
    case received
    case connection(ConnectionState)
    case sessionGone(SessionGoneReason)
    case loginRequired
    /// The image server refused a photo (422 HASH_MISMATCH or INVALID_IMAGE).
    case uploadRejected(localID: String, code: String)
}

public typealias ChangeHandler = @Sendable (PhotonChange) -> Void
