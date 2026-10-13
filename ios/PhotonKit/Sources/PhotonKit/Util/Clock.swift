import Foundation

/// Time source for every component, so tests can drive time and skip real sleeps.
public protocol PhotonClock: Sendable {
    /// Unix time in milliseconds, the unit of every timestamp in PHOTON.
    func nowMs() -> Int64
    /// Suspends for `ms` milliseconds. Throws `CancellationError` when the task is cancelled.
    func sleep(ms: Int64) async throws
}

public struct SystemClock: PhotonClock {
    public init() {}

    public func nowMs() -> Int64 {
        Int64((Date().timeIntervalSince1970 * 1000).rounded())
    }

    public func sleep(ms: Int64) async throws {
        guard ms > 0 else { try Task.checkCancellation(); return }
        try await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000)
    }
}

/// Exponential backoff with jitter. Every retry in the client goes through this, as the
/// design asks ("exponential backoff and jitter").
public struct Backoff: Sendable {
    public var baseMs: Int64
    public var capMs: Int64
    /// Returns a value in [0, 1). Injected so tests are deterministic.
    public var random: @Sendable () -> Double

    public init(baseMs: Int64, capMs: Int64, random: @escaping @Sendable () -> Double = { Double.random(in: 0..<1) }) {
        self.baseMs = baseMs
        self.capMs = capMs
        self.random = random
    }

    /// "Equal jitter": half the exponential step is fixed, half is random. It never retries
    /// immediately (unlike full jitter) yet still spreads clients out.
    /// `attempt` starts at 0 for the first retry.
    public func delayMs(attempt: Int) -> Int64 {
        let shift = min(max(attempt, 0), 30)
        let exp = min(capMs, baseMs.saturatingMultiplied(by: Int64(1) << shift))
        let half = exp / 2
        return half + Int64(Double(exp - half) * random())
    }

    /// The server's `retry_after_s` wins when it is set; otherwise the backoff step.
    public func delayMs(attempt: Int, retryAfterSeconds: Double?) -> Int64 {
        if let s = retryAfterSeconds, s > 0 { return Int64(s * 1000) }
        return delayMs(attempt: attempt)
    }
}

extension Int64 {
    func saturatingMultiplied(by other: Int64) -> Int64 {
        let (r, overflow) = multipliedReportingOverflow(by: other)
        return overflow ? .max : r
    }
}
