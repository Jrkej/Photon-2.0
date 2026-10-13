import Foundation
import PhotonKit

/// `ChunkUploader` over a background URLSession: each chunk is uploaded from its own file
/// by nsurlsessiond, so a transfer keeps going while the app is suspended. When the app is
/// relaunched or woken for the session's events, the upload engine asks the image server
/// which chunks arrived (`GET /v1/uploads/{id}`) and finishes with `complete`.
///
/// A chunk still in flight from an earlier launch is awaited, not sent again.
final class BackgroundChunkUploader: NSObject, ChunkUploader, URLSessionDataDelegate, @unchecked Sendable {
    static let identifier = "com.photon.app.chunks"
    static let shared = BackgroundChunkUploader()

    private var session: URLSession!
    private let lock = NSLock()
    private var waiters: [String: [UUID: CheckedContinuation<Void, Error>]] = [:]   // transferKey -> waiters
    private var bodies: [Int: Data] = [:]                                            // taskIdentifier -> body

    /// From `application(_:handleEventsForBackgroundURLSession:completionHandler:)`.
    var backgroundCompletionHandler: (() -> Void)?
    /// Runs when all background events are delivered (finish uploads with `complete`),
    /// before the system completion handler is called.
    var onBackgroundEventsFinished: (@Sendable () async -> Void)?

    private override init() {
        super.init()
        let config = URLSessionConfiguration.background(withIdentifier: Self.identifier)
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        config.allowsCellularAccess = true
        config.timeoutIntervalForResource = 6 * 3600
        config.httpCookieStorage = nil
        config.urlCache = nil
        session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }

    func upload(_ chunk: ChunkUpload) async throws {
        let key = chunk.transferKey
        let alreadyInFlight = await isInFlight(key)
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                lock.lock()
                waiters[key, default: [:]][id] = cont
                lock.unlock()
                guard !alreadyInFlight else {
                    // If that transfer finished between the check and now, nobody would
                    // resume us: look again and let the engine retry the chunk if so.
                    Task { await self.failIfNotInFlight(key: key, id: id) }
                    return
                }
                let request = chunk.httpRequest
                var urlRequest = URLRequest(url: request.url, timeoutInterval: request.timeout)
                urlRequest.httpMethod = request.method
                for (name, value) in request.headers { urlRequest.setValue(value, forHTTPHeaderField: name) }
                urlRequest.assumesHTTP3Capable = true
                let task = session.uploadTask(with: urlRequest, fromFile: chunk.fileURL)
                task.taskDescription = key
                task.resume()
            }
        } onCancel: {
            // The engine stopped waiting; the transfer itself may still finish, which is
            // harmless (the server stores chunks by index).
            takeWaiter(key: key, id: id)?.resume(throwing: CancellationError())
        }
    }

    private func failIfNotInFlight(key: String, id: UUID) async {
        guard await !isInFlight(key) else { return }
        takeWaiter(key: key, id: id)?.resume(throwing: PhotonError.transport("earlier transfer of \(key) already finished"))
    }

    /// True when a background task for this chunk (maybe from an earlier launch) is still running.
    private func isInFlight(_ key: String) async -> Bool {
        let tasks: [URLSessionTask] = await withCheckedContinuation { cont in
            session.getAllTasks { cont.resume(returning: $0) }
        }
        for task in tasks where task.taskDescription == key {
            if task.state == .running || task.state == .suspended { return true }
        }
        return false
    }

    private func takeWaiter(key: String, id: UUID) -> CheckedContinuation<Void, Error>? {
        lock.lock()
        defer { lock.unlock() }
        return waiters[key]?.removeValue(forKey: id)
    }

    // MARK: URLSessionDataDelegate

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        bodies[dataTask.taskIdentifier, default: Data()].append(data)
        lock.unlock()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let body = bodies.removeValue(forKey: task.taskIdentifier) ?? Data()
        let key = task.taskDescription ?? ""
        let conts = waiters.removeValue(forKey: key).map { Array($0.values) } ?? []
        lock.unlock()

        let result: Result<Void, Error>
        if let error {
            let ns = error as NSError
            result = .failure(PhotonError.transport("\(ns.domain) \(ns.code): \(ns.localizedDescription)"))
        } else if let http = task.response as? HTTPURLResponse {
            if (200..<300).contains(http.statusCode) {
                result = .success(())
            } else {
                var headers: [String: String] = [:]
                for (k, v) in http.allHeaderFields { if let k = k as? String { headers[k] = "\(v)" } }
                let response = HTTPResponse(status: http.statusCode, headers: headers, body: body)
                result = .failure(PhotonError.api(APIError(response: response)))
            }
        } else {
            result = .failure(PhotonError.transport("no response"))
        }
        for cont in conts { cont.resume(with: result) }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        let finish = onBackgroundEventsFinished
        Task {
            if let finish {
                // Bounded: iOS gives a background wake about 30 seconds.
                let work = Task { await finish() }
                let timer = Task { try? await Task.sleep(nanoseconds: 20_000_000_000); work.cancel() }
                await work.value
                timer.cancel()
            }
            await MainActor.run {
                self.backgroundCompletionHandler?()
                self.backgroundCompletionHandler = nil
            }
        }
    }
}
