import Foundation
import PhotonKit

/// Deployment configuration from Info.plist (filled from Config/*.xcconfig).
enum AppConfig {
    static func load(bundle: Bundle = .main) -> PhotonConfig {
        func value(_ key: String) -> String {
            guard let v = bundle.object(forInfoDictionaryKey: key) as? String, !v.isEmpty, !v.contains("$(") else {
                fatalError("Info.plist key \(key) is not set; see PhotonApp/Config/*.xcconfig")
            }
            return v
        }
        func url(_ key: String) -> URL {
            guard let u = URL(string: value(key)), u.scheme == "https" || u.scheme == "http" else {
                fatalError("Info.plist key \(key) is not an http(s) URL")
            }
            return u
        }
        return PhotonConfig(apiBase: url("PhotonAPIBase"), imageBase: url("PhotonImageBase"),
                            authProvider: value("PhotonAuthProvider"), redirectURI: value("PhotonRedirectURI"),
                            inviteHost: value("PhotonInviteHost"))
    }
}

/// Builds PhotonKit with the Apple implementations of its platform protocols. Created at
/// launch, also for background launches, so the background URLSession is re-attached and
/// its pending events are delivered.
@MainActor
final class AppEnvironment {
    static let shared = AppEnvironment()

    let client: PhotonClient
    let library = PhotoKitLibrary()
    let network = NetworkPathMonitor()
    let observer: PhotoLibraryObserver

    private init() {
        let fm = FileManager.default
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Photon", isDirectory: true)
        let caches = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Photon", isDirectory: true)
        for dir in [support, caches] {
            // Readable after first unlock: a push or background-session wake on a locked
            // phone must still reach the state file and the upload copies.
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true,
                                    attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        }
        // Session-scoped data: never in iCloud or device backups.
        var excluded = URLResourceValues()
        excluded.isExcludedFromBackup = true
        var supportURL = support
        try? supportURL.setResourceValues(excluded)

        let files = FileLayout(dataRoot: support, cacheRoot: caches)
        let transport = URLSessionTransport()
        let services = PhotonServices(
            secureStore: KeychainStore(),
            store: FileStore(fileURL: support.appendingPathComponent("state.json")),
            library: library,
            network: network,
            authenticator: WebAuthSession(),
            files: files,
            transport: transport,
            chunkUploader: BackgroundChunkUploader.shared,
            sockets: URLSessionWebSocketFactory(),
            clock: SystemClock())
        let client = PhotonClient(config: AppConfig.load(), services: services)
        self.client = client

        observer = PhotoLibraryObserver { Task { await client.photoLibraryDidChange() } }
        BackgroundChunkUploader.shared.onBackgroundEventsFinished = { _ = await client.backgroundCatchUp() }
        network.start { Task { await client.networkDidChange() } }
    }
}
