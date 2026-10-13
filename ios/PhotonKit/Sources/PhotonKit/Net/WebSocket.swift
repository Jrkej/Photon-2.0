import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The socket closed. `code` is the WebSocket close code (4001, 4409, 1012, ...), or
/// `WebSocketClosed.abnormal` when the connection dropped without a close frame.
public struct WebSocketClosed: Error, Sendable, Equatable {
    public static let abnormal = 1006
    public let code: Int
    public let reason: String

    public init(code: Int, reason: String = "") {
        self.code = code
        self.reason = reason
    }
}

/// One open WebSocket carrying binary protobuf frames.
public protocol WebSocketConnection: Sendable {
    func send(_ data: Data) async throws
    /// The next binary message. Throws `WebSocketClosed` when the socket closes.
    func receive() async throws -> Data
    func close(code: Int)
}

public protocol WebSocketFactory: Sendable {
    func connect(url: URL) async throws -> WebSocketConnection
}

#if canImport(Darwin) || canImport(FoundationNetworking)
/// `WebSocketFactory` over URLSessionWebSocketTask. The access token is never put in the
/// URL; it goes in the first frame (`Hello`).
public final class URLSessionWebSocketFactory: NSObject, WebSocketFactory, URLSessionWebSocketDelegate, @unchecked Sendable {
    private var session: URLSession!
    private let lock = NSLock()
    private var closeCodes: [Int: (Int, String)] = [:]

    public override init() {
        super.init()
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.httpCookieStorage = nil
        session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }

    public func connect(url: URL) async throws -> WebSocketConnection {
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        let task = session.webSocketTask(with: request)
        task.maximumMessageSize = 4 << 20
        task.resume()
        return Connection(task: task, factory: self)
    }

    func closeInfo(for task: URLSessionTask) -> (Int, String)? {
        lock.lock(); defer { lock.unlock() }
        return closeCodes[task.taskIdentifier]
    }

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                           didOpenWithProtocol protocol: String?) {}

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                           didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        lock.lock()
        // Custom codes (4001...) are kept: rawValue carries any value the server sent.
        closeCodes[webSocketTask.taskIdentifier] = (closeCode.rawValue, reason.map { String(decoding: $0, as: UTF8.self) } ?? "")
        lock.unlock()
    }

    final class Connection: WebSocketConnection, @unchecked Sendable {
        let task: URLSessionWebSocketTask
        weak var factory: URLSessionWebSocketFactory?

        init(task: URLSessionWebSocketTask, factory: URLSessionWebSocketFactory) {
            self.task = task
            self.factory = factory
        }

        func send(_ data: Data) async throws {
            do {
                try await task.send(.data(data))
            } catch {
                throw await closedError()
            }
        }

        func receive() async throws -> Data {
            while true {
                let message: URLSessionWebSocketTask.Message
                do {
                    message = try await task.receive()
                } catch {
                    throw await closedError()
                }
                switch message {
                case .data(let d): return d
                case .string: continue   // the protocol is binary only
                @unknown default: continue
                }
            }
        }

        func close(code: Int) {
            task.cancel(with: URLSessionWebSocketTask.CloseCode(rawValue: code) ?? .normalClosure, reason: nil)
        }

        /// The close code arrives through the delegate, sometimes just after receive() fails.
        private func closedError() async -> WebSocketClosed {
            for _ in 0..<10 {
                if let info = factory?.closeInfo(for: task), info.0 != 0 {
                    return WebSocketClosed(code: info.0, reason: info.1)
                }
                if task.closeCode.rawValue != 0 {
                    return WebSocketClosed(code: task.closeCode.rawValue)
                }
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
            return WebSocketClosed(code: WebSocketClosed.abnormal)
        }
    }
}
#endif
