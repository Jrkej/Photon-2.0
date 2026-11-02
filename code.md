# PHOTON code guide

A map of the repository: what each directory and file is for, and how the parts fit together. For *why* things work this way, read [Design.md](Design.md). For the build plan and every decision made along the way, read [implementation.md](implementation.md). To run things, see [README.md](README.md).

## The path of one photo

Following one photo through the code is the quickest way to learn it:

1. **The phone notices a new photo.**
   - On Android, `SyncService` holds a gallery observer, and a content-URI WorkManager job covers the times the process is dead. Both only queue the photo.
   - `UploadWorker` copies the photo, hashes it, and announces it.
2. **The orchestrator reserves it.** [routes/photos.py](src/photon/orchestrator/routes/photos.py) calls the Lua function `announce` in [photon.lua](src/photon/orchestrator/lua/photon.lua). That function checks the session, membership, limits and quota, and reserves the hash under a new `upload_id`. The app gets a short-lived upload token.
3. **The app uploads chunks to the image server.** [imageserver/pipeline.py](src/photon/imageserver/pipeline.py) stores each chunk and refreshes the upload's lease in Redis.
4. **`complete` verifies it.** The pipeline assembles the chunks while hashing them, compares the hash with the announced one, and validates and thumbnails the image in a sandboxed subprocess ([decode_worker.py](src/photon/imageserver/decode_worker.py)). It then writes a pending report into the [outbox](src/photon/imageserver/outbox.py).
5. **The orchestrator accepts it.** The outbox reports `stored` to [routes/internal.py](src/photon/orchestrator/routes/internal.py). The Lua function `report_stored` applies the report only if the `upload_id` is still the live one (fencing), marks the photo `STORED`, and appends a `PHOTO_STORED` event to the session's Redis stream.
6. **Members hear about it.** Each member's WebSocket ([routes/events.py](src/photon/orchestrator/routes/events.py)) reads that stream and pushes the event. Phones download the thumbnail, preview and original from [imageserver/routes.py](src/photon/imageserver/routes.py) and save the original to the gallery.
7. **It expires.** Thirty minutes after storage, the orchestrator's sweeper ([jobs.py](src/photon/orchestrator/jobs.py)) marks the photo `EXPIRED` and asks the image server to delete it. The image server's own [sweeper.py](src/photon/imageserver/sweeper.py) deletes it anyway, even if Redis lost track of it.

## Top level

| Path | What it is |
| --- | --- |
| [Design.md](Design.md) | The system design: architecture, protocols, data model, security, guarantees |
| [implementation.md](implementation.md) | The build plan by phase, the build status, and every decision made while building |
| [README.md](README.md) | How to install, test, run, deploy and operate |
| `code.md` | This file |
| [proto/photon.proto](proto/photon.proto) | The wire format shared by every part. Only add fields and messages; never renumber or reuse a field |
| [src/photon/](src/photon/) | The Python backend, the reference client and the dev tooling |
| [tests/](tests/) | The Python test suite (unit, integration, end to end) |
| [migrations/](migrations/), [alembic.ini](alembic.ini) | Postgres schema migrations (Alembic) |
| [tools/](tools/) | Command-line tools: key generator, script client, load test |
| [scripts/](scripts/) | Shell helpers: build Redis, make certificates, run on a phone |
| [deploy/](deploy/), [Dockerfile](Dockerfile) | Production deployment: Compose, Caddy, Redis, Prometheus |
| [android/](android/) | The Android app (Kotlin) |
| [ios/](ios/) | The iOS app (Swift) and its platform-independent core |
| [pyproject.toml](pyproject.toml) | Python package, dependencies, and lint, type and test settings |
| [.github/workflows/ci.yml](.github/workflows/ci.yml) | CI: lint, types, protobuf freshness, tests, migrations, proto breaking-change check, Android build |

## proto/

`photon.proto` defines every HTTP body and WebSocket frame: auth, sessions, announce and upload, reports between the services, events, snapshots, and the `Error` that carries the design's error contract. Python code is generated into `src/photon/common/pb/`, Kotlin by Wire at Android build time, and Swift by `ios/Scripts/generate-proto.sh`. CI fails if the Python output is out of date.

## src/photon/: the backend

The backend is three services, Auth, the orchestrator and the image server. All three are FastAPI apps sharing one package, and all keep their state in Redis and Postgres, so each can be scaled out. A service starts with `python -m photon.<service>`.

### common/: shared by every service

| File | Purpose |
| --- | --- |
| `config.py` | `Settings`: every setting is an environment variable `PHOTON_*`, and the limits default to the design's proposed values |
| `errors.py` | The error contract as one table (code → HTTP status, retryable) and `ApiError` |
| `proto.py` | Parsing protobuf request bodies and building protobuf responses; hash helpers |
| `httpapp.py` | The app base: request ids, an `Error` body for every failure, `/healthz`, `/metrics`, `/readyz`, client IP behind proxies, and the auth dependencies for users and services |
| `jwtutil.py` | Ed25519 JWTs: signing, verification (algorithm pinned, the shared clock), JWKS, service tokens |
| `redisutil.py` | Redis clients (one with no read timeout, for blocking reads), loading the Lua library, calling functions |
| `ratelimit.py` | Fixed-window rate limits in Redis |
| `clock.py` | `now_ms()`, the one clock every service uses; tests move it forward |
| `ids.py` | Session ids, upload ids, join codes, invite tokens |
| `logs.py` | Structured JSON logs and the request-id middleware |
| `metrics.py` | Every Prometheus metric, defined once |
| `pb/` | Generated protobuf code. Don't edit it |

### auth/: sign-in and tokens

| File | Purpose |
| --- | --- |
| `app.py` | Endpoints: the OAuth browser flow (`start`, `callback` with PKCE and single-use state, and `return`, which forwards a fixed-callback provider such as GitHub back to the app), native Google sign-in (`config`, `nonce`, `google`), token refresh, device registration, JWKS, App Link files, and the dev login (dev builds only). Also finds or creates users by `(provider, provider_user_id)`, never by email |
| `providers.py` | Google (OIDC, ID-token checks), GitHub (OAuth App, profile from the API) and the dev provider |
| `tokens.py` | Access JWTs (15 minutes) and refresh tokens with rotation, a 30 s grace window and family revocation, as one Redis script |

### orchestrator/: sessions, photo status, events

| File | Purpose |
| --- | --- |
| `lua/photon.lua` | **The heart of the system.** Every state transition is one Redis function: create, join, leave, kick, end, announce, report stored or rejected, recall, expire, recount, lease checks, snapshot and resync. Each one checks that the session is `ACTIVE` and fences by `upload_id` atomically |
| `keys.py` | Every Redis key name. `{sid}` hash tags keep a session in one slot |
| `svc.py` | The shared service object: Redis, the one-session-per-user pointer, the activity throttle, deletes, clean-up after a session ends |
| `routes/sessions.py` | Create, join, leave, end, kick, rotate invites, session info, the current session |
| `routes/photos.py` | Announce (mints the upload token), recall, report a photo |
| `routes/events.py` | Snapshot, `/events?since=` catch-up, and the WebSocket: one `XREAD` loop from the client's cursor, so no event is lost between catching up and going live |
| `routes/internal.py` | The image server's fenced `stored` and `rejected` reports; the upload-token JWKS |
| `events.py` | Turns stream entries into `PhotoEvent` messages |
| `upload_tokens.py` | Mints upload JWTs (audience: the image server) |
| `jobs.py` | Background jobs: the expiry sweeper (deletes until the image server acknowledges), the idle-session sweeper, the quota recount |
| `push.py` | Push hints to members without a live socket: FCM for Android, APNs for iOS, at most one per user every 30 s |
| `app.py` | Builds the app and loads the Lua library at start-up |

### imageserver/: the bytes

| File | Purpose |
| --- | --- |
| `routes.py` | Chunk PUT, upload status, `complete`, downloads (`thumb`, `preview`, `full` with Range, checked on every request), and the orchestrator's DELETE |
| `pipeline.py` | Chunk writes (CRC32C, disk budget) and the idempotent `complete`: assemble, hash, validate, stage, thumbnail, report; promotion after the orchestrator accepts |
| `upload_state.py` | The upload state machine in `state.json`, plus per-upload locks |
| `outbox.py` | Pending reports kept on disk and retried until the orchestrator gives a final answer, which is how a crash between saving and reporting is recovered |
| `decode.py`, `decode_worker.py` | Image validation, thumbnails and previews in a fresh subprocess with memory, CPU and time limits |
| `access.py` | Upload-token checks and the Redis reads: session, membership, lease, photo status |
| `store.py` | Disk layout (`tmp/`, `photos/`, `outbox/`, `trash/`) and atomic, fsynced writes |
| `sweeper.py` | Deletes on its own at `expires_at`, plus orphans and stale temporary files. It decides from its own files, never from Redis alone |
| `app.py` | Builds the app; starts the outbox and the sweeper |

### client/: the reference client

| File | Purpose |
| --- | --- |
| `api.py` | `PhotonClient`: every endpoint, the error contract, single-flight token refresh, retries on transport errors |
| `uploader.py` | Hash, announce, resume, parallel chunks with CRC32C, then the `complete` loop |
| `listener.py` | Snapshot, then the WebSocket, with dedupe by event id and hash and handling of every close code |
| `cli.py` | `photon-cli`: log in, create, join, upload, listen, recall, download, and more |

### Other modules

| File | Purpose |
| --- | --- |
| `db.py` | The Postgres tables: users, identities, devices, photo reports |
| `migrate.py` | Runs the Alembic migrations from code (dev launcher, tests, the deploy job) |
| `dev/run.py` | `photon-dev`: the whole stack locally with no Docker (Redis, an embedded Postgres, keys, migrations, both servers) |
| `dev/gateway.py` | Serves Auth and the orchestrator from one base URL, as the load balancer does in production |

## tests/

`conftest.py` starts a real Redis, an embedded Postgres and all three services on random ports, in the test's event loop. The `env` fixture gives each test an empty world, and `login` gives signed-in clients.

| Folder or file | Covers |
| --- | --- |
| `unit/` | The error contract, ids, one Redis slot per function call, JWT checks, the decoder against hostile files, push routing |
| `integration/test_auth.py`, `test_native_signin.py` | OAuth state and PKCE, redirect matching, refresh rotation and reuse, Google ID tokens, nonces, dev login |
| `integration/test_sessions.py` | Capacity, one session per user, host hand-over, kick, joins racing the end, idle expiry |
| `integration/test_upload.py` | Chunks, idempotent `complete`, hash mismatch, crash recovery, thumbnail retries, the disk budget |
| `integration/test_fencing.py`, `test_model.py` | Dedupe, quota races, stale reports, Redis-loss recovery; a model-based test of every transition |
| `integration/test_events.py` | Gap-free delivery across reconnects, resync, close codes, downloads |
| `integration/test_retention.py` | Expiry, both sweepers, retried deletes, orphans, the recount |
| `integration/test_limits.py`, `test_acl.py` | Rate limits, reports, push; the Redis ACL file |
| `e2e/test_e2e.py` | Two users sharing a photo, end to end |
| `bench/` | Benchmarks, skipped by default; run with `scripts/bench.sh`. Results go to `bench/results/latest.md` |

## migrations/

`env.py` is the Alembic environment (async engine, URL from `PHOTON_DATABASE_URL`). In `versions/`, `0001_accounts.py` creates users, identities and devices, and `0002_photo_reports.py` creates photo reports. Migrations only ever add new revisions; never edit an old one.

## tools/ and scripts/

| File | Purpose |
| --- | --- |
| `tools/gen_keys.py` | Writes the four Ed25519 key pairs (`auth-access`, `orch-upload`, `svc-orchestrator`, `svc-imageserver`); `--next` writes keys for a rotation |
| `tools/photon_cli.py` | Wrapper for `photon-cli` |
| `tools/loadtest.py` | Many sessions and members uploading and receiving; reports latencies and delivery counts |
| `scripts/dev-deps.sh` | Builds Redis 7.2 into `.devtools/` |
| `scripts/bench.sh` | Runs the benchmarks on their own stack (about a minute); `-k <name>` runs some |
| `scripts/make-certs.sh` | An internal CA and a TLS certificate for Redis |
| `scripts/phone-backend.sh` | Terminal 1 of the phone setup: runs the dev backend |
| `scripts/phone-install.sh` | Terminal 2: `adb reverse`, then build, install and open the Android app on a USB phone (`--reverse` only restores the port forwarding) |
| `scripts/phone-diagnose.sh` | Read-only report on why the phone isn't sharing: the sync service, battery and background restrictions, scheduled jobs, exit reasons, the app's log (`--watch` follows it live) |

## deploy/

| File | Purpose |
| --- | --- |
| `docker-compose.yml` | One-host production: Redis with a replica, Postgres, a migration job, 2× Auth, 2× orchestrator, the image server, Caddy, Prometheus. Each service mounts only its own private key |
| `Caddyfile` | TLS, HTTP/3 with HTTP/2 fallback, routing to the services, blocks `/internal`, `/metrics` and `/readyz` |
| `redis.conf`, `users.acl.example` | TLS, AOF every second, one ACL user per service, each limited to what it needs |
| `prometheus.yml`, `alerts.yml` | Scraping and the design's alerts (files past expiry, the report backlog, delete failures, clock skew, and more) |
| `.env.example` | Host names and passwords to fill in |
| `../Dockerfile` | One image for all three services; the command picks the service |

## android/: the Android app (Kotlin)

A Gradle project with one module, `app/`. Kotlin is generated from `../proto` by Wire at build time. The code is in `app/src/main/java/com/photon/app/`:

| Package | Purpose |
| --- | --- |
| `PhotonApp.kt` | The application and its hand-wired dependencies; reacts to app start and foreground |
| `ui/` | Jetpack Compose screens (sign-in, session, photos, settings), dialogs, the QR code, the theme, `AppViewModel` |
| `auth/` | Sign-in flows and PKCE |
| `net/` | OkHttp clients, protobuf over HTTP, the error mapper and retry policy, the auth interceptor with single-flight refresh, `PhotonApi` (API) and `ImageApi` (the image server, Cronet with an OkHttp fallback) |
| `data/` | Room database (session, pending photos, received photos, hashes, assets); tokens encrypted with the Keystore; settings |
| `session/` | `SessionManager` (create, join, leave, kick, end, pause, teardown), invite links, the rules for an existing session, purging old rows |
| `sync/` | `SyncService` (foreground service plus gallery observer), the eligibility filter, the media scan, the reconcile job, background triggers that also work when the process is dead, restart after reboot or update, the work scheduler |
| `upload/` | `UploadWorker`/`Uploader` (copy, strip GPS, hash, announce, resume, parallel CRC32C chunks, `complete`), chunk planning, CRC32C |
| `events/` | The WebSocket session and connection loop, close-code handling, catch-up and snapshot sync, the catch-up worker |
| `receive/` | `ReceivePipeline` (dedupe, recall, expiry), fetching thumbnail, preview and original, saving to `Pictures/PHOTON/` before the photo becomes visible to the gallery observer |
| `push/` | Optional FCM: a push only triggers catch-up |
| `core/` | Small shared pieces: hashes, event-id ordering, backoff, notifications, media permissions, cache directories, the network monitor |

Unit tests are in `app/src/test/` (JVM) and device tests in `app/src/androidTest/`. `app/src/debug/` allows plain HTTP and the dev sign-in in debug builds only. [android/README.md](android/README.md) covers building, configuration and Google sign-in setup.

## ios/: the iOS app (Swift)

| Path | Purpose |
| --- | --- |
| `PhotonKit/` | A Swift package with no Apple UI or photo frameworks, so it also builds and tests on Linux. It holds the client's logic: `Net/` (API client, errors, WebSocket), `Auth/` (PKCE, a token manager actor), `Upload/` (chunk planning and the upload engine), `Sync/` (event processing, catch-up, the reconcile rules, join input), `Receive/` (what to fetch and when), `Model/` and `Storage/` (local state as one atomic file), `Crypto/` (CRC32C, SHA-256), `Proto/` (generated). `PhotonClient.swift` is the facade the app uses; `Platform/PlatformServices.swift` holds the protocols the app implements |
| `PhotonKit/Tests/` | 90 tests with a simulated server, photo library, network and sockets |
| `PhotonApp/` | The SwiftUI app. `Sources/App` holds the entry point, app model and texts. `Sources/Platform` holds the Apple implementations: PhotoKit library and observer, background chunk uploads, Keychain, GPS stripping, web sign-in. `Sources/Views` holds the screens. `project.yml` is the XcodeGen spec, with `Config/` (Debug and Release settings) and `Resources/` (Info.plist, entitlements) |
| `Scripts/` | `swiftpm.sh` (build and test PhotonKit, keeping caches inside the repository) and `generate-proto.sh` |

[ios/README.md](ios/README.md) covers building in Xcode and running on a device.

## Generated and local-only (not source)

| Path | What it is |
| --- | --- |
| `.venv/` | Python virtual environment |
| `.devtools/` | Redis built from source, and the Swift toolchain for Linux (3.3 GB, safe to delete) |
| `.devdata/` | The dev backend's data: Redis, Postgres, keys, stored photos. Deleting it resets everything |
| `received/` | Photos saved by `photon-cli listen --save-dir ./received` during testing |
| `src/photon/common/pb/`, `ios/PhotonKit/Sources/PhotonKit/Proto/` | Generated protobuf code |
| `android/app/build/`, `android/.gradle/`, `ios/PhotonKit/.build/` | Build output |
| `.pytest_cache/`, `.mypy_cache/`, `.ruff_cache/`, `.hypothesis/` | Tool caches |
