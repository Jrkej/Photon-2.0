import Foundation
import SwiftProtobuf

/// The design's error contract: every error body is the `Error` message.
public struct APIError: Error, Sendable, Equatable, CustomStringConvertible {
    public var status: Int
    public var code: String
    public var message: String
    /// Retry only when this is true, as the contract says.
    public var retryable: Bool
    /// `retry_after_s`, or the `Retry-After` header when the body has none.
    public var retryAfterSeconds: Double?
    /// `complete` answers 400 with the chunk indexes it is still missing.
    public var missingChunks: [UInt32]

    public init(status: Int, code: String, message: String = "", retryable: Bool = false,
                retryAfterSeconds: Double? = nil, missingChunks: [UInt32] = []) {
        self.status = status
        self.code = code
        self.message = message
        self.retryable = retryable
        self.retryAfterSeconds = retryAfterSeconds
        self.missingChunks = missingChunks
    }

    public var description: String { "\(status) \(code)\(message.isEmpty ? "" : ": \(message)")" }

    /// Builds the error from a non-2xx response. A body that is not a protobuf `Error`
    /// (a proxy's HTML page, say) becomes `HTTP_<status>`, retryable for 429 and 5xx.
    public init(response: HTTPResponse) {
        let contentType = response.header("Content-Type") ?? ""
        if contentType.contains("protobuf"), let pb = try? PbError(serializedBytes: response.body), !pb.code.isEmpty {
            self.init(status: response.status, code: pb.code, message: pb.message, retryable: pb.retryable,
                      retryAfterSeconds: pb.retryAfterS > 0 ? Double(pb.retryAfterS) : response.retryAfterSeconds,
                      missingChunks: pb.missingChunks)
        } else {
            let transient = response.status == 429 || response.status >= 500
            self.init(status: response.status, code: "HTTP_\(response.status)", retryable: transient,
                      retryAfterSeconds: response.retryAfterSeconds)
        }
    }

    public func `is`(_ code: String) -> Bool { self.code == code }
}

/// Error codes from the contract and the implementation plan's additions.
public enum ErrorCode {
    public static let invalidRequest = "INVALID_REQUEST"
    public static let tokenExpired = "TOKEN_EXPIRED"
    public static let unauthenticated = "UNAUTHENTICATED"
    public static let notAMember = "NOT_A_MEMBER"
    public static let forbidden = "FORBIDDEN"
    public static let unknownUpload = "UNKNOWN_UPLOAD"
    public static let photoNotFound = "PHOTO_NOT_FOUND"
    public static let sessionNotFound = "SESSION_NOT_FOUND"
    public static let alreadyInSession = "ALREADY_IN_SESSION"
    public static let uploadSuperseded = "UPLOAD_SUPERSEDED"
    public static let uploadFinalized = "UPLOAD_FINALIZED"
    public static let sessionEnded = "SESSION_ENDED"
    public static let photoExpired = "PHOTO_EXPIRED"
    public static let photoRecalled = "PHOTO_RECALLED"
    public static let uploadExpired = "UPLOAD_EXPIRED"
    public static let resyncRequired = "RESYNC_REQUIRED"
    public static let tooLarge = "TOO_LARGE"
    public static let unsupportedFormat = "UNSUPPORTED_FORMAT"
    public static let hashMismatch = "HASH_MISMATCH"
    public static let invalidImage = "INVALID_IMAGE"
    public static let rateLimited = "RATE_LIMITED"
    public static let tooManyUploads = "TOO_MANY_UPLOADS"
    public static let quotaExceeded = "QUOTA_EXCEEDED"
    public static let busy = "BUSY"
    public static let processingFailed = "PROCESSING_FAILED"
}

public enum PhotonError: Error, Sendable, Equatable, CustomStringConvertible {
    /// The server answered with an `Error` body.
    case api(APIError)
    /// No HTTP response at all (offline, DNS, TLS, timeout). Always worth retrying later.
    case transport(String)
    /// The refresh token is gone or was refused: the user must log in again.
    case loginRequired
    case invalidResponse(String)
    case noSession
    case loginCancelled
    case loginFailed(String)
    /// Create or join answered 409 ALREADY_IN_SESSION. Carries the session the server has
    /// this user in (from `GET /v1/sessions/current`), when it could be fetched, so the UI
    /// can offer to leave it and try again.
    case alreadyInSession(PbSessionInfo?)

    public var description: String {
        switch self {
        case .api(let e): return e.description
        case .transport(let s): return "network error: \(s)"
        case .loginRequired: return "login required"
        case .invalidResponse(let s): return "invalid response: \(s)"
        case .noSession: return "not in a session"
        case .loginCancelled: return "login cancelled"
        case .loginFailed(let s): return "login failed: \(s)"
        case .alreadyInSession(let info): return "already in session \(info?.sessionID ?? "(unknown)")"
        }
    }
}

extension Error {
    /// The contract error, when this is one.
    public var apiError: APIError? {
        if let p = self as? PhotonError, case .api(let e) = p { return e }
        return nil
    }

    /// Worth retrying later without changing anything: no response, or a server error the
    /// contract marks retryable.
    public var isTransient: Bool {
        if let p = self as? PhotonError, case .transport = p { return true }
        if let e = apiError { return e.retryable }
        return false
    }

    public var retryAfterSeconds: Double? { apiError?.retryAfterSeconds }
}
