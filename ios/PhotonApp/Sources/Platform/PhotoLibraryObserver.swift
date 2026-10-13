import Foundation
import Photos

/// `PHPhotoLibraryChangeObserver`, registered only while the app is alive. Like Android's
/// ContentObserver it only notices: it triggers the reconcile scan (debounced), which does
/// the filtering and queueing. iOS delivers nothing while the app is suspended; the scan on
/// the next launch or foreground picks those photos up.
final class PhotoLibraryObserver: NSObject, PHPhotoLibraryChangeObserver, @unchecked Sendable {
    private let onChange: @Sendable () -> Void
    private let queue = DispatchQueue(label: "com.photon.library-observer")
    private var pending: DispatchWorkItem?
    private var registered = false

    init(onChange: @escaping @Sendable () -> Void) {
        self.onChange = onChange
    }

    /// Registers once the user has granted access (registering earlier can prompt).
    func startIfAuthorized() {
        queue.async {
            guard !self.registered, PhotoKitLibrary.authorization == .authorized || PhotoKitLibrary.authorization == .limited else { return }
            PHPhotoLibrary.shared().register(self)
            self.registered = true
        }
    }

    func stop() {
        queue.async {
            guard self.registered else { return }
            PHPhotoLibrary.shared().unregisterChangeObserver(self)
            self.registered = false
        }
    }

    func photoLibraryDidChange(_ changeInstance: PHChange) {
        queue.async {
            self.pending?.cancel()
            let work = DispatchWorkItem { [onChange] in onChange() }
            self.pending = work
            // A burst (a save plus its album edit, or a burst shot) becomes one scan.
            self.queue.asyncAfter(deadline: .now() + 1, execute: work)
        }
    }
}
