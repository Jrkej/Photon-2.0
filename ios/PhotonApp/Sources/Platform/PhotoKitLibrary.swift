import Foundation
import ImageIO
import Photos
import PhotonKit
import UniformTypeIdentifiers

/// `PhotoLibrary` over PhotoKit.
final class PhotoKitLibrary: PhotoLibrary, @unchecked Sendable {
    static let albumTitle = "PHOTON"
    private let albumIDKey = "photon.album.localIdentifier"
    private let defaults = UserDefaults.standard

    static var authorization: PHAuthorizationStatus { PHPhotoLibrary.authorizationStatus(for: .readWrite) }

    /// Full access is needed: with limited access iOS hides newly taken photos from the app.
    static var hasFullAccess: Bool { authorization == .authorized }

    // MARK: Reconcile scan

    func candidateAssets(createdAtOrAfterMs: Int64) async throws -> [LibraryAsset] {
        guard Self.authorization == .authorized || Self.authorization == .limited else { throw PhotoLibraryError.accessDenied }
        let options = PHFetchOptions()
        let since = Date(timeIntervalSince1970: Double(createdAtOrAfterMs) / 1000)
        options.predicate = NSPredicate(format: "mediaType == %d AND creationDate >= %@",
                                        PHAssetMediaType.image.rawValue, since as NSDate)
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: true)]
        // The user's own library only: not shared albums or iTunes-synced photos.
        options.includeAssetSourceTypes = [.typeUserLibrary]
        let result = PHAsset.fetchAssets(with: options)
        var assets: [LibraryAsset] = []
        assets.reserveCapacity(result.count)
        result.enumerateObjects { asset, _, _ in
            assets.append(LibraryAsset(
                localID: asset.localIdentifier,
                creationDateMs: asset.creationDate.map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) },
                isScreenshot: asset.mediaSubtypes.contains(.photoScreenshot)))
        }
        return assets
    }

    func photonAlbumAssetIDs() async throws -> Set<String> {
        guard let album = findAlbum() else { return [] }
        var ids = Set<String>()
        PHAsset.fetchAssets(in: album, options: nil).enumerateObjects { asset, _, _ in ids.insert(asset.localIdentifier) }
        return ids
    }

    // MARK: Export for upload

    func exportOriginal(localID: String, to directory: URL, stripLocation: Bool) async throws -> ExportedPhoto {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [localID], options: nil).firstObject else {
            throw PhotoLibraryError.assetNotFound
        }
        let resources = PHAssetResource.assetResources(for: asset)
        // The camera's original. For a Live Photo this is its still image; the video part is
        // not shared (Live Photos are a non-goal).
        guard let resource = resources.first(where: { $0.type == .photo })
            ?? resources.first(where: { $0.type == .fullSizePhoto }) else {
            throw PhotoLibraryError.exportFailed("no photo resource")
        }
        let uti = resource.uniformTypeIdentifier
        guard let mime = Self.mime(forUTI: uti) else { throw PhotoLibraryError.unsupportedFormat(uti) }
        let ext = fileExtension(forMime: mime)

        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let raw = directory.appendingPathComponent("source.\(ext)")
        try? FileManager.default.removeItem(at: raw)
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true   // "Optimize iPhone Storage" may keep it in iCloud
        do {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                PHAssetResourceManager.default().writeData(for: resource, toFile: raw, options: options) { error in
                    if let error { cont.resume(throwing: error) } else { cont.resume() }
                }
            }
        } catch {
            throw PhotoLibraryError.exportFailed(error.localizedDescription)
        }

        let upload: URL
        if stripLocation {
            upload = directory.appendingPathComponent("upload.\(ext)")
            do {
                try LocationStripper.copyWithoutLocation(from: raw, to: upload)
            } catch {
                throw PhotoLibraryError.exportFailed("\(error)")
            }
            try? FileManager.default.removeItem(at: raw)
        } else {
            upload = raw
        }
        let taken = asset.creationDate ?? Date()
        return ExportedPhoto(fileURL: upload, mime: mime, takenAtMs: Int64((taken.timeIntervalSince1970 * 1000).rounded()))
    }

    // MARK: Saving received originals

    func saveToPhotonAlbum(fileURL: URL, mime: String, takenAtMs: Int64,
                           recordLocalID: @escaping @Sendable (String) throws -> Void) async throws -> String {
        guard Self.authorization == .authorized || Self.authorization == .limited else { throw PhotoLibraryError.accessDenied }
        do {
            return try await createAsset(fileURL: fileURL, mime: mime, takenAtMs: takenAtMs, recordLocalID: recordLocalID)
        } catch let error where mime == "image/webp" {
            // Older Photos versions refuse WebP: store a JPEG rendering instead. Layers 1 and 2
            // (asset id, PHOTON album) still recognise it; its hash differs, which is why those
            // layers come before the hash check.
            guard let jpeg = Self.transcodeToJPEG(fileURL) else { throw PhotoLibraryError.saveFailed(error.localizedDescription) }
            defer { try? FileManager.default.removeItem(at: jpeg) }
            return try await createAsset(fileURL: jpeg, mime: "image/jpeg", takenAtMs: takenAtMs, recordLocalID: recordLocalID)
        }
    }

    private final class Box: @unchecked Sendable {
        var localID: String?
        var error: Error?
    }

    private func createAsset(fileURL: URL, mime: String, takenAtMs: Int64,
                             recordLocalID: @escaping @Sendable (String) throws -> Void) async throws -> String {
        let album = try await findOrCreateAlbum()
        let box = Box()
        let uti = Self.uti(forMime: mime)
        do {
            try await PHPhotoLibrary.shared().performChanges {
                let request = PHAssetCreationRequest.forAsset()
                let options = PHAssetResourceCreationOptions()
                options.uniformTypeIdentifier = uti
                options.shouldMoveFile = false
                request.addResource(with: .photo, fileURL: fileURL, options: options)
                // Keep the original capture time so it lands in the right place in the timeline.
                request.creationDate = Date(timeIntervalSince1970: Double(takenAtMs) / 1000)
                guard let placeholder = request.placeholderForCreatedAsset else { return }
                // Echo layer 1: recorded before the change block finishes, so the change
                // observer and the scan already know this asset when it appears.
                do {
                    try recordLocalID(placeholder.localIdentifier)
                    box.localID = placeholder.localIdentifier
                } catch {
                    // Still in the PHOTON album, which the scan skips (echo layer 2).
                    box.error = error
                    box.localID = placeholder.localIdentifier
                }
                PHAssetCollectionChangeRequest(for: album)?.addAssets([placeholder] as NSArray)
            }
        } catch {
            throw PhotoLibraryError.saveFailed(error.localizedDescription)
        }
        guard let id = box.localID else { throw PhotoLibraryError.saveFailed("no placeholder") }
        return id
    }

    private func findAlbum() -> PHAssetCollection? {
        if let id = defaults.string(forKey: albumIDKey),
           let album = PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [id], options: nil).firstObject {
            return album
        }
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "title == %@", Self.albumTitle)
        guard let album = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .albumRegular, options: options).firstObject else {
            return nil
        }
        defaults.set(album.localIdentifier, forKey: albumIDKey)
        return album
    }

    private func findOrCreateAlbum() async throws -> PHAssetCollection {
        if let album = findAlbum() { return album }
        let box = Box()
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: Self.albumTitle)
            box.localID = request.placeholderForCreatedAssetCollection.localIdentifier
        }
        guard let id = box.localID,
              let album = PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [id], options: nil).firstObject else {
            throw PhotoLibraryError.saveFailed("cannot create the PHOTON album")
        }
        defaults.set(id, forKey: albumIDKey)
        return album
    }

    // MARK: Types

    /// The server's allow-list: JPEG, PNG, WebP, HEIC/HEIF. Anything else (RAW, GIF, TIFF)
    /// is not uploaded.
    static func mime(forUTI uti: String) -> String? {
        guard let type = UTType(uti) else { return nil }
        if type.conforms(to: .jpeg) { return "image/jpeg" }
        if type.conforms(to: .png) { return "image/png" }
        if type.conforms(to: .heic) { return "image/heic" }
        if type.conforms(to: .heif) { return "image/heif" }
        if type.conforms(to: .webP) { return "image/webp" }
        return nil
    }

    static func uti(forMime mime: String) -> String {
        switch mime {
        case "image/jpeg": return UTType.jpeg.identifier
        case "image/png": return UTType.png.identifier
        case "image/heic": return UTType.heic.identifier
        case "image/heif": return UTType.heif.identifier
        case "image/webp": return UTType.webP.identifier
        default: return UTType.image.identifier
        }
    }

    static func transcodeToJPEG(_ url: URL) -> URL? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let out = url.deletingPathExtension().appendingPathExtension("jpg")
        guard let dst = CGImageDestinationCreateWithURL(out as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImageFromSource(dst, src, 0, [kCGImageDestinationLossyCompressionQuality: 0.92] as CFDictionary)
        return CGImageDestinationFinalize(dst) ? out : nil
    }
}
