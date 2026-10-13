import Foundation

/// `PhotonStore` backed by one JSON file, written atomically after every change. The state
/// is small (one session, at most a few hundred photos), so rewriting it whole is cheap and
/// keeps every multi-table update atomic. With `fileURL == nil` it is memory-only (tests).
public final class FileStore: PhotonStore, @unchecked Sendable {
    private let lock = NSLock()
    private var state: PhotonState
    private let fileURL: URL?
    private let log = PhotonLog("store")

    public init(fileURL: URL?) {
        self.fileURL = fileURL
        var loaded = PhotonState()
        if let fileURL, let data = try? Data(contentsOf: fileURL) {
            if let decoded = try? JSONDecoder().decode(PhotonState.self, from: data) {
                loaded = decoded
            } else {
                // Unreadable state: keep it for diagnosis and start clean. The server-side
                // dedupe still prevents double uploads.
                let backup = fileURL.appendingPathExtension("corrupt")
                try? FileManager.default.removeItem(at: backup)
                try? FileManager.default.moveItem(at: fileURL, to: backup)
                PhotonLog("store").error("state file unreadable; moved aside")
            }
        }
        state = loaded
    }

    public func read<T>(_ body: (PhotonState) throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body(state)
    }

    public func write<T>(_ body: (inout PhotonState) throws -> T) throws -> T {
        lock.lock()
        defer { lock.unlock() }
        var copy = state
        let result = try body(&copy)
        if copy != state {
            try persist(copy)
            state = copy
        }
        return result
    }

    private func persist(_ value: PhotonState) throws {
        guard let fileURL else { return }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(value)
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        #if os(iOS)
        // Readable after the first unlock, so a background wake on a locked phone still works.
        try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: fileURL, options: [.atomic])
        #endif
    }
}

/// Where PhotonKit keeps files.
/// - data root (Application Support): upload copies and chunk files, which must survive
///   until the upload finishes;
/// - cache root (Caches): thumbnails, previews and partial downloads, which iOS may purge.
public struct FileLayout: Sendable {
    public let dataRoot: URL
    public let cacheRoot: URL

    public init(dataRoot: URL, cacheRoot: URL) {
        self.dataRoot = dataRoot
        self.cacheRoot = cacheRoot
    }

    public func uploadDirectory(key: String) -> URL {
        dataRoot.appendingPathComponent("uploads", isDirectory: true).appendingPathComponent(key, isDirectory: true)
    }

    public func chunkFile(key: String, index: Int) -> URL {
        uploadDirectory(key: key).appendingPathComponent("chunks", isDirectory: true).appendingPathComponent("\(index)")
    }

    public var downloadsDirectory: URL { cacheRoot.appendingPathComponent("downloads", isDirectory: true) }

    /// Relative paths are stored in `ReceivedPhoto`, so a moved container still resolves.
    public func cacheURL(relativePath: String) -> URL { cacheRoot.appendingPathComponent(relativePath) }

    public static func thumbPath(_ hex: String) -> String { "thumbs/\(hex)" }
    public static func previewPath(_ hex: String) -> String { "previews/\(hex)" }

    public func removeUploadDirectory(key: String) {
        try? FileManager.default.removeItem(at: uploadDirectory(key: key))
    }

    public func removeCached(relativePath: String?) {
        guard let relativePath else { return }
        try? FileManager.default.removeItem(at: cacheURL(relativePath: relativePath))
    }

    public func writeCached(_ data: Data, relativePath: String) throws {
        let url = cacheURL(relativePath: relativePath)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: [.atomic])
    }
}
