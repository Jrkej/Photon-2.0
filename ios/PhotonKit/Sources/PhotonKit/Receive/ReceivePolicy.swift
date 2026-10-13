import Foundation

/// What to fetch for a received photo on the current network. The thumbnail always (when
/// connected), the preview on an unmetered link or a fast metered one, the original by the
/// user's setting. Only the original is ever saved to the photo library.
public struct ReceivePolicy: Sendable, Equatable {
    public var originals: OriginalsPolicy
    public var previewMinBitsPerSecond: Double

    public init(originals: OriginalsPolicy, previewMinBitsPerSecond: Double = 5_000_000) {
        self.originals = originals
        self.previewMinBitsPerSecond = previewMinBitsPerSecond
    }

    public func fetchThumbnail(_ status: NetworkStatus) -> Bool {
        status.isConnected
    }

    /// Unmetered, or metered but measured at 5 Mbit/s or more (iOS has no bandwidth
    /// estimate like Android's, so the rate comes from recent downloads). Never in Low
    /// Data Mode.
    public func fetchPreview(_ status: NetworkStatus, measuredBitsPerSecond: Double?) -> Bool {
        guard status.isConnected, !status.isConstrained else { return false }
        if !status.isExpensive { return true }
        return (measuredBitsPerSecond ?? 0) >= previewMinBitsPerSecond
    }

    public func fetchOriginal(_ status: NetworkStatus) -> Bool {
        guard status.isConnected else { return false }
        switch originals {
        case .always:
            return !status.isConstrained
        case .wifiOrCharging:
            return status.isUnmetered || (status.isCharging && !status.isConstrained)
        }
    }
}

/// File extension for a received original, so PhotoKit recognises the type.
public func fileExtension(forMime mime: String) -> String {
    switch mime.lowercased() {
    case "image/jpeg", "image/jpg": return "jpg"
    case "image/png": return "png"
    case "image/webp": return "webp"
    case "image/heic": return "heic"
    case "image/heif": return "heif"
    default: return "bin"
    }
}
