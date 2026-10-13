import Foundation

// Platform services PhotonKit needs but cannot implement portably. PhotonApp implements them
// with Keychain, PhotoKit, Network.framework and AuthenticationServices; the tests use fakes.

// MARK: - Keychain

/// Small secrets: the token pair and the in-flight PKCE verifier. Keychain on iOS.
public protocol SecureStore: Sendable {
    func data(for key: String) throws -> Data?
    func set(_ data: Data, for key: String) throws
    func remove(_ key: String) throws
}

// MARK: - Storage

/// Durable local state. Reads and writes are atomic and may be called from any thread,
/// including synchronously from inside a PhotoKit change block (the received original's
/// localIdentifier must be recorded before that block finishes). The closures must not
/// call the store again: implementations may use a non-reentrant lock.
public protocol PhotonStore: Sendable {
    func read<T>(_ body: (PhotonState) throws -> T) rethrows -> T
    /// Applies `body` and persists the result before returning.
    func write<T>(_ body: (inout PhotonState) throws -> T) throws -> T
}

// MARK: - Photo library

/// What the reconcile scan needs to know about a library asset.
public struct LibraryAsset: Sendable, Equatable {
    public var localID: String
    /// PHAsset.creationDate in Unix ms; nil when the library has none.
    public var creationDateMs: Int64?
    /// mediaSubtypes contains .photoScreenshot.
    public var isScreenshot: Bool

    public init(localID: String, creationDateMs: Int64?, isScreenshot: Bool) {
        self.localID = localID
        self.creationDateMs = creationDateMs
        self.isScreenshot = isScreenshot
    }
}

/// A copy of an original, ready to hash and upload. Location is already removed when asked.
public struct ExportedPhoto: Sendable, Equatable {
    public var fileURL: URL
    /// One of image/jpeg, image/png, image/webp, image/heic, image/heif.
    public var mime: String
    public var takenAtMs: Int64

    public init(fileURL: URL, mime: String, takenAtMs: Int64) {
        self.fileURL = fileURL
        self.mime = mime
        self.takenAtMs = takenAtMs
    }
}

public enum PhotoLibraryError: Error, Sendable, Equatable {
    case accessDenied
    case assetNotFound
    /// The asset's format is not on the server's allow-list (for example RAW).
    case unsupportedFormat(String)
    case exportFailed(String)
    case saveFailed(String)
}

public protocol PhotoLibrary: Sendable {
    /// Still images created at or after `createdAtOrAfterMs`, newest last. The implementation
    /// may pre-filter; PhotonKit applies the full eligibility rules again.
    func candidateAssets(createdAtOrAfterMs: Int64) async throws -> [LibraryAsset]
    /// localIdentifiers of every asset in the PHOTON album (echo layer 2).
    func photonAlbumAssetIDs() async throws -> Set<String>
    /// Writes the original into `directory`, with GPS removed when `stripLocation` is set,
    /// without re-encoding where possible. The caller hashes exactly this file.
    func exportOriginal(localID: String, to directory: URL, stripLocation: Bool) async throws -> ExportedPhoto
    /// Saves a received original into the PHOTON album with its capture time, calling
    /// `recordLocalID` with the new asset's localIdentifier inside the change block, before
    /// the asset becomes visible to observers. Returns that localIdentifier.
    func saveToPhotonAlbum(fileURL: URL, mime: String, takenAtMs: Int64,
                           recordLocalID: @escaping @Sendable (String) throws -> Void) async throws -> String
}

// MARK: - Network

public struct NetworkStatus: Sendable, Equatable {
    public var isConnected: Bool
    /// Cellular or a personal hotspot (NWPath.isExpensive).
    public var isExpensive: Bool
    /// Low Data Mode (NWPath.isConstrained).
    public var isConstrained: Bool
    public var isCharging: Bool

    public init(isConnected: Bool, isExpensive: Bool, isConstrained: Bool, isCharging: Bool) {
        self.isConnected = isConnected
        self.isExpensive = isExpensive
        self.isConstrained = isConstrained
        self.isCharging = isCharging
    }

    public var isUnmetered: Bool { isConnected && !isExpensive && !isConstrained }

    public static let offline = NetworkStatus(isConnected: false, isExpensive: false, isConstrained: false, isCharging: false)
    public static let wifi = NetworkStatus(isConnected: true, isExpensive: false, isConstrained: false, isCharging: false)
    public static let cellular = NetworkStatus(isConnected: true, isExpensive: true, isConstrained: false, isCharging: false)
}

public protocol NetworkMonitor: Sendable {
    var current: NetworkStatus { get }
}

// MARK: - System browser

public protocol WebAuthenticator: Sendable {
    /// Opens `url` in the system browser (ASWebAuthenticationSession, never a web view) and
    /// returns the redirect URL that matched `callback`.
    func authenticate(url: URL, callback: AuthCallback) async throws -> URL
}
