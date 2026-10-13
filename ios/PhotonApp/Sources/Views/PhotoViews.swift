import CoreImage.CIFilterBuiltins
import Photos
import PhotonKit
import SwiftUI

/// A received photo in the grid: the cached preview or thumbnail (never the library).
struct ReceivedThumbnail: View {
    @Environment(AppModel.self) private var model
    let photo: ReceivedPhoto

    var body: some View {
        ZStack {
            Rectangle().fill(.quaternary)
            if let image = CachedImage.load(model.env.client.files, photo.previewPath ?? photo.thumbPath) {
                Image(uiImage: image).resizable().scaledToFill()
            } else if photo.state == .saved, let id = photo.savedLocalID {
                AssetThumbnail(localID: id)
            } else {
                Image(systemName: photo.state == .recalled ? "eye.slash" : "photo")
                    .foregroundStyle(.secondary)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 4))
        .overlay(alignment: .bottomTrailing) {
            if photo.state == .saved {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.white, .green).padding(4)
            }
        }
    }
}

struct ReceivedPhotoView: View {
    @Environment(AppModel.self) private var model
    let photo: ReceivedPhoto
    @State private var confirmRecall = false
    @State private var reporting = false
    @State private var reason = ""

    var body: some View {
        List {
            Section {
                ReceivedThumbnail(photo: photo).listRowInsets(EdgeInsets())
            }
            Section {
                LabeledContent("From", value: photo.uploaderID)
                LabeledContent("Taken") { Text(Date(timeIntervalSince1970: Double(photo.takenAtMs) / 1000), style: .time) }
                LabeledContent("Status", value: statusText)
                if photo.state != .expired && photo.state != .recalled {
                    LabeledContent("Available until") {
                        Text(Date(timeIntervalSince1970: Double(photo.expiresAtMs) / 1000), style: .time)
                    }
                }
            }
            Section {
                Button("Report photo") { reporting = true }
                if model.canRecall(uploaderID: photo.uploaderID) && photo.state != .recalled {
                    Button(Copy.recallAction, role: .destructive) { confirmRecall = true }
                }
            }
        }
        .navigationTitle("Photo")
        .navigationBarTitleDisplayMode(.inline)
        .recallDialog(isPresented: $confirmRecall) { model.recall(photo.sha256Hex) }
        .alert("Report this photo", isPresented: $reporting) {
            TextField("Reason", text: $reason)
            Button("Send report") { model.report(photo.sha256Hex, reason: reason); reason = "" }
            Button("Cancel", role: .cancel) { reason = "" }
        } message: {
            Text("The operators review reported photos.")
        }
    }

    private var statusText: String {
        switch photo.state {
        case .pending: return "Arriving"
        case .thumb, .preview: return "Shown here (original not saved yet)"
        case .saved: return "Saved to your PHOTON album"
        case .recalled: return "Recalled by the uploader or host"
        case .expired: return "Expired on the server"
        case .failed: return "Unavailable"
        }
    }
}

struct MyPhotoView: View {
    @Environment(AppModel.self) private var model
    let job: UploadJob
    @State private var confirmRecall = false

    var body: some View {
        List {
            Section {
                AssetThumbnail(localID: job.id, size: 600)
                    .aspectRatio(1, contentMode: .fit)
                    .listRowInsets(EdgeInsets())
            }
            Section {
                LabeledContent("Status", value: UploadStateText.label(job))
                if let error = job.lastError, job.state != .synced {
                    Text(error).font(.footnote).foregroundStyle(.secondary)
                }
            }
            if job.state == .synced, job.sha256Hex != nil {
                Section {
                    Button(Copy.recallAction, role: .destructive) { confirmRecall = true }
                }
            }
        }
        .navigationTitle("Your photo")
        .navigationBarTitleDisplayMode(.inline)
        .recallDialog(isPresented: $confirmRecall) {
            if let hex = job.sha256Hex { model.recall(hex) }
        }
    }
}

extension View {
    /// The recall confirmation with the design's plain-words explanation.
    func recallDialog(isPresented: Binding<Bool>, action: @escaping () -> Void) -> some View {
        confirmationDialog(Copy.recallTitle, isPresented: isPresented, titleVisibility: .visible) {
            Button(Copy.recallAction, role: .destructive, action: action)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(Copy.recallBody)
        }
    }
}

enum CachedImage {
    static func load(_ files: FileLayout, _ relativePath: String?) -> UIImage? {
        guard let relativePath else { return nil }
        return UIImage(contentsOfFile: files.cacheURL(relativePath: relativePath).path)
    }
}

/// A library asset's thumbnail through PHImageManager.
struct AssetThumbnail: View {
    let localID: String
    var size: CGFloat = 160
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Rectangle().fill(.quaternary)
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            }
        }
        .clipped()
        .task(id: localID) { image = await load() }
    }

    private func load() async -> UIImage? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [localID], options: nil).firstObject else { return nil }
        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        let target = CGSize(width: size * 2, height: size * 2)
        return await withCheckedContinuation { cont in
            PHImageManager.default().requestImage(for: asset, targetSize: target, contentMode: .aspectFill,
                                                  options: options) { image, _ in
                cont.resume(returning: image)
            }
        }
    }
}

struct QRCodeView: View {
    let text: String

    var body: some View {
        if let image = Self.render(text) {
            Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
        }
    }

    static func render(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
