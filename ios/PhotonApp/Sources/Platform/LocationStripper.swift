import Foundation
import ImageIO
import PhotonKit
import UniformTypeIdentifiers

/// Removes location from a photo before upload. The upload is hashed after this step, so
/// the hash covers exactly the bytes the server receives.
///
/// First choice: `CGImageDestinationCopyImageSource` with `kCGImageMetadataShouldExcludeGPS`,
/// a lossless copy that rewrites only the metadata (JPEG and HEIC: no re-encode; HEIC depth
/// and gain-map auxiliary data are kept). If ImageIO cannot do that for the format, each
/// image is re-encoded with the GPS dictionary removed. The result is always checked, and
/// a copy that still carries GPS is never returned.
enum LocationStripper {
    enum Failure: Error, CustomStringConvertible {
        case unreadable, unwritable, stillHasLocation
        var description: String {
            switch self {
            case .unreadable: return "cannot read image"
            case .unwritable: return "cannot write image copy"
            case .stillHasLocation: return "location could not be removed"
            }
        }
    }

    static func copyWithoutLocation(from source: URL, to destination: URL) throws {
        guard let src = CGImageSourceCreateWithURL(source as CFURL, nil),
              let type = CGImageSourceGetType(src) else { throw Failure.unreadable }
        let count = max(CGImageSourceGetCount(src), 1)
        try? FileManager.default.removeItem(at: destination)

        // 1. Lossless metadata-only copy. CGImageDestinationFinalize must not be called
        //    after CGImageDestinationCopyImageSource.
        if let dst = CGImageDestinationCreateWithURL(destination as CFURL, type, count, nil) {
            let options: [CFString: Any] = [kCGImageMetadataShouldExcludeGPS: true]
            var error: Unmanaged<CFError>?
            if CGImageDestinationCopyImageSource(dst, src, options as CFDictionary, &error), !hasLocation(destination) {
                return
            }
            error?.release()
        }

        // 2. Re-encode with the GPS dictionary removed (kCFNull deletes a property).
        try? FileManager.default.removeItem(at: destination)
        guard let dst = CGImageDestinationCreateWithURL(destination as CFURL, type, count, nil) else {
            throw Failure.unwritable
        }
        let properties: [CFString: Any] = [
            kCGImagePropertyGPSDictionary: kCFNull as Any,
            kCGImageDestinationLossyCompressionQuality: 0.95,
        ]
        for index in 0..<count {
            CGImageDestinationAddImageFromSource(dst, src, index, properties as CFDictionary)
        }
        guard CGImageDestinationFinalize(dst) else { throw Failure.unwritable }
        guard !hasLocation(destination) else {
            try? FileManager.default.removeItem(at: destination)
            throw Failure.stillHasLocation
        }
    }

    /// True when any image in the file still has a GPS dictionary or GPS XMP tags.
    static func hasLocation(_ url: URL) -> Bool {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return true }
        for index in 0..<max(CGImageSourceGetCount(src), 1) {
            if let props = CGImageSourceCopyPropertiesAtIndex(src, index, nil) as? [CFString: Any],
               props[kCGImagePropertyGPSDictionary] != nil {
                return true
            }
            if let metadata = CGImageSourceCopyMetadataAtIndex(src, index, nil),
               let tags = CGImageMetadataCopyTags(metadata) as? [CGImageMetadataTag] {
                for tag in tags {
                    if let name = CGImageMetadataTagCopyName(tag) as String?, name.hasPrefix("GPS") { return true }
                }
            }
        }
        return false
    }
}
