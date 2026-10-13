import Foundation

/// Deployment configuration. The app reads it from Info.plist (filled from the xcconfig
/// files), tests build it directly.
public struct PhotonConfig: Sendable, Equatable {
    /// Serves /v1/auth/*, /v1/devices/*, /v1/sessions*, /v1/photos/* and /v1/ws.
    public var apiBase: URL
    /// Serves /v1/uploads/* and the downloads /v1/sessions/{sid}/photos/{hash}.
    public var imageBase: URL
    /// OAuth provider name sent to /v1/auth/oauth/start: "google" in production, "dev" locally.
    public var authProvider: String
    /// Registered redirect URI, matched exactly by the server: the Universal Link
    /// https://photon.example/auth/cb in production, photon://auth/cb in development.
    public var redirectURI: String
    /// Host of invite links (https://<inviteHost>/j/<token>).
    public var inviteHost: String

    public init(apiBase: URL, imageBase: URL, authProvider: String, redirectURI: String, inviteHost: String) {
        self.apiBase = apiBase
        self.imageBase = imageBase
        self.authProvider = authProvider
        self.redirectURI = redirectURI
        self.inviteHost = inviteHost
    }

    /// wss://<api>/v1/ws (ws:// for a plain-HTTP development server).
    public var webSocketURL: URL {
        var c = URLComponents(url: apiBase.appendingPathComponent("v1/ws"), resolvingAgainstBaseURL: false)!
        c.scheme = (apiBase.scheme == "http") ? "ws" : "wss"
        return c.url!
    }

    /// How ASWebAuthenticationSession should recognise the redirect.
    public var authCallback: AuthCallback {
        guard let url = URL(string: redirectURI), let scheme = url.scheme else {
            return .customScheme("photon")
        }
        if scheme == "https" {
            return .https(host: url.host ?? inviteHost, path: url.path.isEmpty ? "/" : url.path)
        }
        return .customScheme(scheme)
    }
}

/// The callback the system browser session waits for.
public enum AuthCallback: Sendable, Equatable {
    case customScheme(String)
    case https(host: String, path: String)
}

/// Tunables with the values from the design and the implementation plan.
public struct PhotonTuning: Sendable {
    public var pingIntervalMs: Int64 = 30_000
    /// No frame at all for this long means the socket is dead; reconnect.
    public var socketSilenceTimeoutMs: Int64 = 75_000
    public var eventsPageLimit = 500
    public var reconnectBackoff = Backoff(baseMs: 1_000, capMs: 60_000)
    /// Each chunk is retried up to 3 times (4 attempts) with backoff and jitter.
    public var chunkAttempts = 4
    public var chunkBackoff = Backoff(baseMs: 500, capMs: 8_000)
    public var parallelChunks = 3
    /// IN_PROGRESS: re-announce after 2 minutes, backing off to 15.
    public var waitingFirstDelayMs: Int64 = 120_000
    public var waitingMaxDelayMs: Int64 = 900_000
    /// Persistent-queue retry after a transient failure (network, 5xx, chunk retries spent).
    public var jobBackoff = Backoff(baseMs: 5_000, capMs: 300_000)
    /// `complete` polls (202 VERIFYING/REPORTING, 503 PROCESSING_FAILED) per run.
    public var maxCompletePolls = 30
    /// Re-announces (409 superseded, 410 lease expired, 401 token expired) per run.
    public var maxReannouncesPerRun = 3
    /// Chunk size is aimed at about 2 seconds per chunk, within these bounds.
    public var minChunkSize = 256 * 1024
    public var maxChunkSize = 8 * 1024 * 1024
    public var targetChunkSeconds = 2.0
    /// The image server refuses chunk indexes at or above this.
    public var maxChunkCount = 4096
    /// Preview on a metered network only above this measured downstream rate.
    public var previewMinBitsPerSecond = 5_000_000.0
    /// Hashes and received asset ids are kept this long after leaving, then purged.
    public var historyRetentionMs: Int64 = 24 * 3_600_000

    public init() {}
}
