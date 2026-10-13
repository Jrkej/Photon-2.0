import Foundation

/// Runs `body` whenever kicked, and again at the time `body` returns (the next due retry),
/// one run at a time. Kicks during a run coalesce into one more run.
public final class WorkLoop: Sendable {
    private let continuation: AsyncStream<Void>.Continuation
    private let task: Task<Void, Never>

    /// `body` returns when it next wants to run (Unix ms), or nil to wait for a kick.
    public init(clock: PhotonClock, body: @escaping @Sendable () async -> Int64?) {
        let (stream, continuation) = AsyncStream.makeStream(of: Void.self, bufferingPolicy: .bufferingNewest(1))
        self.continuation = continuation
        task = Task {
            var timer: Task<Void, Never>?
            for await _ in stream {
                if Task.isCancelled { break }
                timer?.cancel()
                let due = await body()
                if let due {
                    let wait = max(0, due - clock.nowMs())
                    timer = Task {
                        do { try await clock.sleep(ms: max(wait, 250)) } catch { return }
                        continuation.yield(())
                    }
                }
            }
            timer?.cancel()
        }
    }

    public func kick() { continuation.yield(()) }

    public func stop() {
        continuation.finish()
        task.cancel()
    }
}
