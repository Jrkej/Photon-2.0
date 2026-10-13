import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum HTTPBody: Sendable, Equatable {
    case none
    case data(Data)
    /// Streamed from disk (chunk files), which is what a background URLSession needs.
    case file(URL)
}

public struct HTTPRequest: Sendable {
    public var method: String
    public var url: URL
    public var headers: [String: String]
    public var body: HTTPBody
    public var timeout: TimeInterval
    /// The image server speaks HTTP/3 behind Caddy; let URLSession try QUIC first. It falls
    /// back to HTTP/2 over TCP by itself when UDP is blocked.
    public var preferHTTP3: Bool

    public init(method: String, url: URL, headers: [String: String] = [:], body: HTTPBody = .none,
                timeout: TimeInterval = 30, preferHTTP3: Bool = false) {
        self.method = method
        self.url = url
        self.headers = headers
        self.body = body
        self.timeout = timeout
        self.preferHTTP3 = preferHTTP3
    }
}

public struct HTTPResponse: Sendable {
    public var status: Int
    /// Header names lowercased.
    public var headers: [String: String]
    public var body: Data

    public init(status: Int, headers: [String: String] = [:], body: Data = Data()) {
        self.status = status
        self.headers = Dictionary(headers.map { ($0.key.lowercased(), $0.value) }, uniquingKeysWith: { a, _ in a })
        self.body = body
    }

    public func header(_ name: String) -> String? { headers[name.lowercased()] }
    public var isSuccess: Bool { (200..<300).contains(status) }

    /// `Retry-After` in seconds (delta-seconds form only; the servers never send a date).
    public var retryAfterSeconds: Double? {
        guard let raw = header("Retry-After"), let s = Double(raw.trimmingCharacters(in: .whitespaces)), s >= 0 else { return nil }
        return s
    }
}

public protocol HTTPTransport: Sendable {
    /// Sends one request. Any HTTP status is a normal return; only a failure to get a
    /// response at all throws (`PhotonError.transport`, or `CancellationError`).
    func send(_ request: HTTPRequest) async throws -> HTTPResponse
}

/// `HTTPTransport` over a foreground URLSession.
public final class URLSessionTransport: HTTPTransport, @unchecked Sendable {
    private let session: URLSession

    public init(session: URLSession? = nil) {
        if let session {
            self.session = session
        } else {
            let config = URLSessionConfiguration.ephemeral
            config.urlCache = nil
            config.requestCachePolicy = .reloadIgnoringLocalCacheData
            config.httpCookieStorage = nil
            config.timeoutIntervalForRequest = 60
            self.session = URLSession(configuration: config)
        }
    }

    public func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        var req = URLRequest(url: request.url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: request.timeout)
        req.httpMethod = request.method
        for (k, v) in request.headers { req.setValue(v, forHTTPHeaderField: k) }
        #if canImport(Darwin)
        if request.preferHTTP3 { req.assumesHTTP3Capable = true }
        #endif
        let box = TaskBox()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<HTTPResponse, Error>) in
                let handler: @Sendable (Data?, URLResponse?, Error?) -> Void = { data, response, error in
                    if let error {
                        let ns = error as NSError
                        if ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled {
                            cont.resume(throwing: CancellationError())
                        } else {
                            cont.resume(throwing: PhotonError.transport("\(ns.domain) \(ns.code): \(ns.localizedDescription)"))
                        }
                        return
                    }
                    guard let http = response as? HTTPURLResponse else {
                        cont.resume(throwing: PhotonError.invalidResponse("not an HTTP response"))
                        return
                    }
                    cont.resume(returning: HTTPResponse(status: http.statusCode,
                                                        headers: Self.headers(of: http),
                                                        body: data ?? Data()))
                }
                let task: URLSessionTask
                switch request.body {
                case .none:
                    task = session.dataTask(with: req, completionHandler: handler)
                case .data(let d):
                    task = session.uploadTask(with: req, from: d, completionHandler: handler)
                case .file(let url):
                    task = session.uploadTask(with: req, fromFile: url, completionHandler: handler)
                }
                box.set(task)
                task.resume()
            }
        } onCancel: {
            box.cancel()
        }
    }

    static func headers(of response: HTTPURLResponse) -> [String: String] {
        var out: [String: String] = [:]
        for (k, v) in response.allHeaderFields {
            if let k = k as? String { out[k.lowercased()] = "\(v)" }
        }
        return out
    }
}

/// Holds a URLSessionTask so a cancellation handler on another thread can cancel it.
final class TaskBox: @unchecked Sendable {
    private let lock = NSLock()
    private var task: URLSessionTask?
    private var cancelled = false

    func set(_ t: URLSessionTask) {
        lock.lock()
        task = t
        let c = cancelled
        lock.unlock()
        if c { t.cancel() }
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let t = task
        lock.unlock()
        t?.cancel()
    }
}
