import Foundation
#if canImport(os)
import os
#endif

/// Minimal logging. Never pass tokens, codes, invite tokens, image bytes or EXIF here; hashes
/// are logged by prefix only (`shortHash`).
public struct PhotonLog: Sendable {
    let category: String
    #if canImport(os)
    private let logger: Logger
    #endif

    public init(_ category: String) {
        self.category = category
        #if canImport(os)
        logger = Logger(subsystem: "com.photon.kit", category: category)
        #endif
    }

    public func info(_ message: String) {
        #if canImport(os)
        logger.info("\(message, privacy: .public)")
        #else
        if ProcessInfo.processInfo.environment["PHOTON_LOG"] != nil {
            FileHandle.standardError.write(Data("[\(category)] \(message)\n".utf8))
        }
        #endif
    }

    public func error(_ message: String) {
        #if canImport(os)
        logger.error("\(message, privacy: .public)")
        #else
        if ProcessInfo.processInfo.environment["PHOTON_LOG"] != nil {
            FileHandle.standardError.write(Data("[\(category)] ERROR \(message)\n".utf8))
        }
        #endif
    }
}

/// First 12 hex characters of a hash, the only form in which hashes are logged.
public func shortHash(_ hex: String) -> String { String(hex.prefix(12)) }
