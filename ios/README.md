# PHOTON iOS client

The iOS client of PHOTON ([Design.md](../Design.md), [implementation.md](../implementation.md) step 8.6). It talks to the same server as the Android app and uses the same wire format, generated from [proto/photon.proto](../proto/photon.proto) with swift-protobuf.

The code is split so the logic can be built and tested without Xcode:

| Part | What it is | Verified |
| --- | --- | --- |
| [PhotonKit/](PhotonKit/) | Swift package with no UIKit, Photos or AuthenticationServices: protobuf models, API client, PKCE, CRC32C, upload engine, event sync, receive pipeline, reconcile rules | Built and tested on Linux (Swift 6.3.3, Swift 6 language mode, 90 XCTest tests) |
| [PhotonApp/](PhotonApp/) | SwiftUI app implementing PhotonKit's platform protocols with Keychain, PhotoKit, ImageIO, Network.framework, ASWebAuthenticationSession, APNs and a background URLSession | **Not compiled**: there is no Xcode on the build machine. Every file parses; the app's use of PhotonKit was type-checked against the built module through a mirror file |

```
ios/
├── Scripts/
│   ├── swiftpm.sh            # runs `swift` in PhotonKit with every cache kept under .build/
│   └── generate-proto.sh     # regenerates the models (plugin built from the pinned swift-protobuf)
├── PhotonKit/
│   ├── Package.swift         # swift-protobuf 1.38.x, iOS 17 / macOS 14
│   ├── Sources/PhotonKit/
│   │   ├── Proto/photon.pb.swift          # generated, never edited (types prefixed Pb)
│   │   ├── Net/                           # HTTP transport, APIClient, error contract, WebSocket
│   │   ├── Auth/                          # PKCE, TokenManager (single-flight refresh), AuthFlow
│   │   ├── Crypto/                        # CRC32C, SHA-256
│   │   ├── Upload/                        # ChunkPlan, ChunkUploader, UploadEngine
│   │   ├── Sync/                          # EventProcessor, EventSync, Reconciler, JoinInput
│   │   ├── Receive/                       # ReceivePolicy, ReceivePipeline
│   │   ├── Storage/FileStore.swift        # PhotonStore over one JSON file
│   │   ├── Platform/PlatformServices.swift# the protocols the app implements
│   │   └── PhotonClient.swift             # the facade the app talks to
│   └── Tests/PhotonKitTests/              # fakes for the server, library, network, sockets
└── PhotonApp/
    ├── project.yml                        # XcodeGen spec (no .xcodeproj checked in)
    ├── Config/{Shared,Debug,Release}.xcconfig
    ├── Resources/{Info.plist,PhotonApp.entitlements}
    └── Sources/{App,Platform,Views}/
```

## Building and testing PhotonKit

On Linux, with the toolchain installed at `.devtools/swift` (Swift 6.3.3 for Ubuntu 24.04 from swift.org, signature checked):

```bash
ios/Scripts/swiftpm.sh build
ios/Scripts/swiftpm.sh test                      # 90 tests, about 30 s
ios/Scripts/swiftpm.sh test --filter UploadEngineTests
```

`swiftpm.sh` keeps SwiftPM, Foundation and clang caches under `PhotonKit/.build/.local-state`, so a run writes nothing to `$HOME` or `/tmp`. On a Mac, `cd ios/PhotonKit && swift test` works as usual (Xcode 26 or later: the generated code uses `nonisolated struct`, which needs Swift 6.2).

Regenerating the models after a proto change (needs `protoc`; `brew install protobuf` on macOS):

```bash
ios/Scripts/generate-proto.sh            # rewrites Proto/photon.pb.swift
ios/Scripts/generate-proto.sh --check    # fails if the checked-in file is stale (for CI)
```

The plugin is built from the same swift-protobuf checkout the package depends on, so generator and runtime always match.

## Building the app

```bash
brew install xcodegen
cd ios/PhotonApp && xcodegen generate && open Photon.xcodeproj
```

1. Set `DEVELOPMENT_TEAM` in `project.yml` (or in Xcode). The server's `apple-app-site-association` must list `<TEAMID>.com.photon.app` under `applinks` and `webcredentials` (the backend serves it from `PHOTON_IOS_APP_ID`).
2. Enable the capabilities the entitlements declare for the App ID: Associated Domains, Push Notifications, and Background Modes (remote notifications, background fetch).
3. Debug talks to `photon-dev` (API on `http://localhost:8080`, images on `http://localhost:8081`) with the `dev` login provider and the `photon://auth/cb` redirect, which suits the Simulator. For a device, put the Mac's LAN address in `Config/Debug.xcconfig` and start `photon-dev --host 0.0.0.0`.
4. Release uses Google login with the Universal Link `https://photon.example/auth/cb`, and placeholder hosts `api.photon.example` and `img.photon.example`; set the real ones in `Config/Release.xcconfig`.
5. Push: the server needs `PHOTON_APNS_*` set; the app registers its APNs token with `PUT /v1/devices/current` (platform `ios`) after login.

Minimum iOS is 17.4, for `ASWebAuthenticationSession` HTTPS callbacks (the Universal Link redirect).

## How the client follows the design

**Detection, with no promise while suspended.** `PHPhotoLibraryChangeObserver` is registered while the app is alive and only triggers the reconcile scan. The scan runs on launch, on every return to the foreground, on resume from pause and on each observer callback. It queues still images with `creationDate` at or after the join time that are not screenshots, not saved by this app, not in the PHOTON album and not already queued. While the app is suspended nothing is detected or received, and the session screen says so in plain words.

**Upload** (`UploadEngine`, mirroring Android 7.7):
- Export the original's primary photo resource and remove GPS with `CGImageDestinationCopyImageSource` and `kCGImageMetadataShouldExcludeGPS`. This is a lossless metadata rewrite; if ImageIO can't do it for a format, the image is re-encoded without the GPS dictionary, and a copy that still has location is never uploaded.
- Hash that exact copy and announce it.
- `UPLOAD` resumes from `GET /v1/uploads/{id}` and sends the missing chunks, three at a time, each with `X-Chunk-CRC32C` and up to 3 retries. Then `complete` runs in a loop: 202 and 503 honour Retry-After, and a 400 resends the listed chunks.
- `IN_PROGRESS` becomes WAITING. Only a `PHOTO_STORED` event marks the job synced; otherwise it is re-announced after 2 minutes, backing off to 15.
- 409 and 410 `UPLOAD_EXPIRED` re-announce. 422 fails the job. 410 `SESSION_ENDED` and 403 `NOT_A_MEMBER` end the session locally.
- Chunks go through a background `URLSession` from per-chunk files, so a transfer can finish while the app is suspended. `complete` runs at the next launch or background wake. Upload tokens are never persisted: after a restart, announce (idempotent) returns the same `upload_id` with a fresh token.

**Events** (`EventSync`, `EventProcessor`):
- Before every connect, catch up over HTTP with `/events?since=` until a short page. The snapshot loads first when there is no cursor, or after `410 RESYNC_REQUIRED`.
- Then `hello{access_token, session_id, last_event_id}` and a ping every 30 s. A socket silent for 75 s is replaced.
- Dedupe is by `event_id` (compared as `(ms, seq)`) and by hash. The cursor is written only after an event is handled.
- Close codes: 4001 refreshes and reconnects; 4400 and 1012 reconnect with backoff and jitter; 4401 asks for login; 4403 and 4410 stop syncing; 4409 loads the snapshot and reconnects from its cursor.
- `HOST_CHANGED` updates the stored role and host. Since the snapshot carries no host, `SessionInfo` is re-read after every snapshot. Unknown future event types are skipped, and the cursor still advances.

**Receiving** (`ReceivePipeline`):
- Events only create durable records; downloads then run in passes. Thumbnails come first, always. Previews follow on unmetered networks, or on metered links measured at 5 Mbit/s or more, never in Low Data Mode; a 503 keeps the thumbnail.
- Originals download by the user's setting: any network except Low Data Mode, or Wi-Fi or while charging. Each original is SHA-256-checked, then saved with `PHAssetCreationRequest` into the PHOTON album with its capture time.
- The new asset's `localIdentifier` and the hash are recorded before the change block finishes.
- Recall deletes cached thumbnails and previews; saved originals stay. The recall dialog says what recall cannot do.

**Echo prevention:** asset ids saved by the app, the PHOTON album, then the local hash set, then the server's dedupe. iOS has no owner-package layer.

**Auth:**
- PKCE S256, with the verifier in the Keychain for the duration of the attempt.
- `ASWebAuthenticationSession`, never a web view. The redirect must echo `state`.
- Tokens live in the Keychain (`AfterFirstUnlockThisDeviceOnly`). Refresh is single-flight through an actor on 401 `TOKEN_EXPIRED` or `UNAUTHENTICATED`, and proactive 30 s before expiry.

**Sessions:**
- Consent text before create, join or rejoin.
- A join code is normalised the Crockford way. Invite links arrive as Universal Links (`/j/<token>`); the QR code is the invite link, scanned with the Camera app.
- A 409 `ALREADY_IN_SESSION` names the session from `GET /v1/sessions/current` and offers to leave it and continue, or rejoin it. The same check runs at launch, to recover a session that local state lost.
- Logout leaves the session first.

**Background:** a silent push (`content-available`) and `BGAppRefreshTask` each run one bounded catch-up: events, receive, reconcile, upload queue. Neither is relied on.

## Decisions this client makes

- Live Photos: the still image is uploaded; the video part is not. Formats outside the allow-list (RAW, GIF, TIFF) are skipped, not converted.
- Only `.typeUserLibrary` assets are scanned. iOS can't tell a camera photo from an image saved from Safari or another app, so those are shared if created after the join. Android filters on `DCIM/`.
- 410 `UPLOAD_EXPIRED` re-announces instead of skipping, because on iOS a lease routinely expires while the app is suspended.
- A chunk that fails for good stops new chunks from starting, but in-flight ones finish.
- Originals are fetched on any network except Low Data Mode by default; "Wi-Fi or while charging" is the opt-in setting. Previews on metered links need a measured 5 Mbit/s, since iOS has no bandwidth estimate.
- WebP originals that Photos refuses are saved as JPEG; echo layers 1 and 2 still catch them.
- `upload_url` from announce is not followed; uploads always go to `IMAGE_BASE/v1/uploads/{id}`.
- Local state is one JSON file in Application Support, with `completeUntilFirstUserAuthentication` protection and excluded from backup. Hashes and received asset ids are kept 24 hours after a session ends.
- The app target builds in Swift 5 language mode with targeted concurrency checking; PhotonKit is Swift 6 mode.
- No in-app QR scanner: the Camera app opens invite links.

## Not verified here

- The app target has never been compiled, linked, signed or run. Expect small fixes on first build in Xcode.
- On a device: PhotoKit export and save, GPS removal for JPEG and HEIC, background URLSession relaunches, silent push delivery, BGAppRefresh, Universal Link login, and limited-library behaviour. Design finding 17 calls for a manual test plan on device.
- PhotonKit's CryptoKit SHA-256 path and `URLSessionWebSocketFactory` close-code delivery, which differ between Apple platforms and Linux. Linux tests use the portable SHA-256 and fake sockets.
- End to end against the real backend. The client's contract was checked by reading the backend's code (dev OAuth redirect, AASA, rotate response, 202 Retry-After, close codes, `/v1/sessions/current`, `HOST_CHANGED`, the APNs payload) but not exercised.
