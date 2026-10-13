import Foundation

/// Owns the token pair (kept in the Keychain through `SecureStore`) and refreshes it
/// single-flight: however many requests hit 401 at once, one `POST /v1/auth/refresh` runs
/// and every caller gets its result. That matters because the server rotates the refresh
/// token on every use and treats a late replay as theft.
public actor TokenManager {
    struct Stored: Codable, Equatable {
        var access: String
        var refresh: String
        var accessExpiresAtMs: Int64
        var userID: String
    }

    static let storeKey = "photon.tokens"

    private let store: SecureStore
    private let clock: PhotonClock
    private let refresher: @Sendable (String) async throws -> PbTokenPair
    private var cached: Stored?
    private var loaded = false
    private var inFlight: Task<Stored, Error>?
    /// Bumped by `clear()`, so a refresh that finishes after a logout is discarded.
    private var generation = 0
    private let log = PhotonLog("auth")

    /// `refresher` performs `POST /v1/auth/refresh` with the given refresh token.
    public init(store: SecureStore, clock: PhotonClock, refresher: @escaping @Sendable (String) async throws -> PbTokenPair) {
        self.store = store
        self.clock = clock
        self.refresher = refresher
    }

    private func current() -> Stored? {
        if !loaded {
            loaded = true
            if let data = try? store.data(for: Self.storeKey) {
                cached = try? JSONDecoder().decode(Stored.self, from: data)
            }
        }
        return cached
    }

    public var isLoggedIn: Bool { current() != nil }
    public var userID: String? { current()?.userID }

    public func save(_ pair: PbTokenPair) throws {
        let s = Stored(access: pair.accessToken, refresh: pair.refreshToken,
                       accessExpiresAtMs: pair.accessExpiresAtMs, userID: pair.userID)
        try store.set(try JSONEncoder().encode(s), for: Self.storeKey)
        cached = s
        loaded = true
    }

    public func clear() {
        try? store.remove(Self.storeKey)
        cached = nil
        loaded = true
        generation += 1
        inFlight?.cancel()
        inFlight = nil
    }

    /// An access token valid for at least `minValidityMs`, refreshing first when needed. If a
    /// proactive refresh fails for a transient reason, a still-valid token is returned.
    public func validAccessToken(minValidityMs: Int64 = 30_000) async throws -> String {
        guard let t = current() else { throw PhotonError.loginRequired }
        let now = clock.nowMs()
        if t.accessExpiresAtMs == 0 || t.accessExpiresAtMs - now > minValidityMs {
            return t.access
        }
        do {
            return try await refresh(rejected: t.access)
        } catch let error where error.isTransient && t.accessExpiresAtMs > now {
            return t.access
        }
    }

    /// Refreshes after `rejected` was refused (401). If another caller already replaced
    /// that token, the new one is returned without another refresh. Throws
    /// `PhotonError.loginRequired` when the server refuses the refresh token.
    public func refresh(rejected: String?) async throws -> String {
        guard let t = current() else { throw PhotonError.loginRequired }
        if let rejected, t.access != rejected {
            return t.access
        }
        if let task = inFlight {
            return try await task.value.access
        }
        let refreshToken = t.refresh
        let refresher = self.refresher
        let task = Task<Stored, Error> {
            let pair = try await refresher(refreshToken)
            return Stored(access: pair.accessToken, refresh: pair.refreshToken,
                          accessExpiresAtMs: pair.accessExpiresAtMs, userID: pair.userID.isEmpty ? t.userID : pair.userID)
        }
        inFlight = task
        let startedGeneration = generation
        defer { if generation == startedGeneration { inFlight = nil } }
        do {
            let fresh = try await task.value
            guard generation == startedGeneration else { throw PhotonError.loginRequired }
            try store.set(try JSONEncoder().encode(fresh), for: Self.storeKey)
            cached = fresh
            log.info("access token refreshed")
            return fresh.access
        } catch let error {
            if let api = error.apiError, api.status == 401 || api.status == 403 {
                log.error("refresh refused (\(api.code)); login required")
                clear()
                throw PhotonError.loginRequired
            }
            throw error
        }
    }
}
