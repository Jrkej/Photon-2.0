# PHOTON — Implementation Plan (Python)

Oct 13, 2026 · derived from [Design.md](Design.md) (v2)

This plan breaks [Design.md](Design.md) into build steps, in order. The server side (Auth, orchestrator, image server), the test client and all tooling are written in Python. The Android and iOS apps stay native (Kotlin and Swift), because the design depends on `MediaStore` observers, foreground services, WorkManager and PhotoKit, and Python can't reach any of them. Their steps are in Phase 7 (Android) and step 8.6 (iOS).

The phases follow the design's build order. The server path is built first and tested with a Python script client, so the app plugs into a path that already works. Each step names the files it touches, says what to build, and ends with a **Done when** check. Where the design leaves a detail open, this plan picks one; every such choice is listed in [Additions to the design](#additions-to-the-design).

## Build status

Oct 13, 2026. Phases 0–6, the server side of Phases 8 and 9, and the Python tooling are built. The 137 tests pass, ruff is clean, and `mypy --strict` passes on `src/`. The [README](README.md) explains how to run everything.

| Phase | State |
| --- | --- |
| 0 Foundation | Built: pyproject, proto and generated code, common library, Redis Functions library, key generator, CI workflow. Redis is built from source by `scripts/dev-deps.sh`, and Postgres comes from the `pgserver` wheel, so no Docker is needed. |
| 1 Auth | Built and tested (17 tests). Google is tested against a mocked provider; it needs a real client id to run. |
| 2 Sessions | Built and tested (11 tests). |
| 3 Image server | Built and tested (17 tests, plus 18 decoder unit tests). |
| 4 Announce and fencing | Built and tested (13 tests, plus the model-based test). |
| 5 Events | Built and tested (13 tests, including 1,200 events across random reconnects). |
| 6 Retention | Built and tested (9 tests). |
| 7 Android | Built ([android/](android/)): the debug APK builds offline, 96 unit tests pass, and lint has 0 errors. Instrumented tests compile but have not run, and the app has not run on a device or emulator. See [android/README.md](android/README.md). |
| 8 Limits, privacy, HTTP/3, push, iOS | Server side built and tested: rate limits, reports, push hints (FCM for Android, APNs for iOS, routed by each device's platform), devices. HTTP/3 is configured in the Caddyfile but not exercised here. iOS ([ios/](ios/)): the PhotonKit core builds and its 90 tests pass on Linux with Swift 6.3. The SwiftUI app target has not been compiled (no Xcode here). See [ios/README.md](ios/README.md). |
| 9 Operations | Metrics, alerts, Dockerfile, compose, Caddy, Redis config and ACLs written. The ACL file was verified on Redis 7.2 (`test_acl.py`). The load test was run on one machine. Docker files were not run here (no Docker access on the build machine). |

## Contents

1. [Stack](#stack)
2. [Repository layout](#repository-layout)
3. [Conventions used in every phase](#conventions-used-in-every-phase)
4. [Phase 0 — Project foundation](#phase-0--project-foundation)
5. [Phase 1 — Auth](#phase-1--auth)
6. [Phase 2 — Orchestrator: sessions](#phase-2--orchestrator-sessions)
7. [Phase 3 — Image server: upload and complete](#phase-3--image-server-upload-and-complete)
8. [Phase 4 — Announce, dedupe, quota and fencing](#phase-4--announce-dedupe-quota-and-fencing)
9. [Phase 5 — Events, WebSocket, snapshot and resync](#phase-5--events-websocket-snapshot-and-resync)
10. [Phase 6 — Retention and deletion](#phase-6--retention-and-deletion)
11. [Phase 7 — Android client (Kotlin)](#phase-7--android-client-kotlin)
12. [Phase 8 — Limits, privacy, HTTP/3, push and iOS](#phase-8--limits-privacy-http3-push-and-ios)
13. [Phase 9 — Observability, deployment and release](#phase-9--observability-deployment-and-release)
14. [Review findings to steps and tests](#review-findings-to-steps-and-tests)
15. [Additions to the design](#additions-to-the-design)
16. [Decided while building](#decided-while-building)

How the phases map to the design's build order: design step 1 is Phases 1 and 2, and steps 2 to 7 are Phases 3 to 8. Phase 0 (foundation) and Phase 9 (operations) are added.

## Stack

| Concern | Choice |
| --- | --- |
| Language and tooling | Python 3.12, `uv` (environment and lockfile), `ruff`, `mypy --strict` |
| HTTP and WebSocket | FastAPI on uvicorn (`uvloop`, `httptools`) |
| HTTP/3 | Caddy in front of the image server: QUIC, `Alt-Svc`, automatic HTTP/2 fallback (Phase 8) |
| Redis | Redis 7.2, `redis.asyncio` (redis-py 5), one Lua library of Redis Functions (`FUNCTION LOAD`, `FCALL`) |
| Postgres | Postgres 16, SQLAlchemy 2 (async) with asyncpg, Alembic migrations |
| Protobuf | `protobuf` 5, code generated with `grpcio-tools`, breaking changes checked with `buf breaking` |
| JWT | PyJWT with `cryptography`, Ed25519 (`EdDSA`) |
| Outbound HTTP | httpx (OAuth providers, service-to-service calls) |
| Images | Pillow and pillow-heif, run in a sandboxed subprocess |
| Checksums | `hashlib` (SHA-256), `google-crc32c` |
| Push | firebase-admin (FCM), aioapns (APNs, Phase 8) |
| Config | pydantic-settings, from environment variables and mounted secret files |
| Logs and metrics | structlog (JSON lines), prometheus-client |
| Tests | pytest, pytest-asyncio, httpx `AsyncClient`, real Redis and Postgres from docker compose, hypothesis for state-machine tests |

## Repository layout

```
photon/
├── pyproject.toml
├── proto/photon.proto                  # every wire message
├── src/photon/
│   ├── common/
│   │   ├── config.py                   # Settings (pydantic-settings)
│   │   ├── errors.py                   # ApiError, the error contract table, handler -> Error proto
│   │   ├── proto.py                    # parse_body(), ProtoResponse
│   │   ├── pb/photon_pb2.py            # generated, never edited
│   │   ├── jwt.py                      # verify access, upload and service tokens against JWKS
│   │   ├── redis.py                    # client factory, Lua script loader
│   │   ├── ratelimit.py                # rl:{scope}:{id} windows
│   │   ├── ids.py                      # session ids, upload ids, join codes, invite tokens
│   │   ├── clock.py                    # now_ms(), replaceable in tests
│   │   ├── logging.py                  # structlog setup, request id middleware
│   │   └── metrics.py                  # Prometheus registry, /metrics
│   ├── auth/
│   │   ├── app.py
│   │   ├── models.py                   # users, identities, devices
│   │   ├── oauth.py                    # /v1/auth/oauth/start and /callback
│   │   ├── providers/{base,google,dev}.py
│   │   ├── tokens.py                   # access JWT, refresh rotation
│   │   └── keys.py                     # signing key, /.well-known/jwks.json
│   ├── orchestrator/
│   │   ├── app.py
│   │   ├── lua/photon.lua              # Redis Functions library: every state transition
│   │   ├── keys.py                     # key names
│   │   ├── events.py                   # stream entry -> PhotoEvent
│   │   ├── scripts.py                  # typed Python wrappers around the Lua scripts
│   │   ├── routes/{sessions,photos,events,internal,devices}.py
│   │   ├── ws.py                       # /v1/ws
│   │   ├── upload_tokens.py            # mints upload JWTs
│   │   ├── imageserver_client.py       # DELETE /internal/v1/photos/...
│   │   ├── push.py
│   │   └── jobs/{expiry_sweeper,session_sweeper,recount}.py
│   └── imageserver/
│       ├── app.py
│       ├── routes/{uploads,downloads,internal}.py
│       ├── store.py                    # paths, atomic writes, fsync helpers
│       ├── upload_state.py             # state.json and its state machine
│       ├── access.py                   # Redis reads: session, membership, lease, photo
│       ├── pipeline.py                 # complete(): assemble, hash, validate, stage, report
│       ├── decode_worker.py            # subprocess entry point: validate, thumb, preview
│       ├── outbox.py                   # pending reports, retries, promotion
│       └── sweeper.py
├── migrations/                         # Alembic
├── tools/
│   ├── photon_cli.py                   # script client: login, create, join, upload, listen, download
│   ├── gen_keys.py
│   └── loadtest.py
├── tests/{unit,integration,e2e}/
└── deploy/{docker-compose.yml,Caddyfile,redis.conf,users.acl,prometheus.yml,alerts.yml}
```

## Conventions used in every phase

- **Time.** Every timestamp is Unix milliseconds. Python passes `now_ms` into each Lua script as an argument, so tests can drive time. NTP (chrony) keeps hosts within tens of milliseconds, which the design requires because the image server and the orchestrator compare `expires_at`.
- **Errors.** Handlers raise `ApiError(code, message=None, retry_after_s=None, missing_chunks=None)`. `errors.py` holds the design's error contract as one table, `code → (status, retryable)`, so a code always maps to the same status. A FastAPI exception handler writes the `Error` protobuf and sets `Retry-After` whenever `retry_after_s` is set. Unknown exceptions become `503 BUSY` with `retryable=true` and are logged with the request id.
- **Protobuf over HTTP.** `parse_body(request, pb.AnnounceRequest)` checks `Content-Type: application/x-protobuf`, caps the body size, and parses. A wrong type or a parse error is `400 INVALID_REQUEST`. `ProtoResponse(msg, status=200)` serialises with that media type. Hashes travel as 32 raw bytes and are lowercase hex in Redis and on disk.
- **Lua scripts.** All state transitions live in one Redis Functions library, `orchestrator/lua/photon.lua` (`#!lua name=photon`). It registers one function per transition (`create_session`, `join`, `leave`, `kick`, `end_session`, `announce`, `report_stored`, `report_rejected`, `recall`, `expire_photo`, `recount`, `touch_session`, `touch_lease`, plus the single-key `ptr_cas`, `ptr_del`, `rotate_refresh` and `rate_limit`) and read-only ones with the `no-writes` flag (`version`, `snapshot`, `events_since`, `upload_check`, `download_check`). Shared local helpers (`release_reservation`, `append_event`, `remove_member`, `do_end`) keep each rule in one place. The orchestrator loads the library; Auth and the image server only call `FCALL` and `FCALL_RO`. Functions are Lua scripts, as the design asks, and they persist in the AOF and replicate. A function's `KEYS` are only `{sid}`-tagged keys. It returns `{"OK", ...}` or `{"ERR", "<CODE>"}`, and `scripts.py` turns the error code into an `ApiError`. Functions never touch cross-slot keys (`user:*`, `code:*`, `invite:*`, `expiry:*`, `sessions:due`, `rl:*`, `refresh:*`). Python handles those before or after the call, with compensation where the design asks for it. A function that builds a key name from data, such as the lease key from an `upload_id`, builds it with the same `{sid}` tag, so the key stays in the session's slot.
- **Identifiers.** `sid`: 16 random bytes as url-safe base64 without padding (22 characters; it never contains `{` or `}`). `upload_id`: UUID4 hex. Join code: 8 Crockford base32 characters. Invite token: 16 random bytes, url-safe base64. `event_id`: the Redis stream id.
- **Request ids.** Middleware accepts `X-Request-Id` or generates one, binds it to the structlog context, and forwards it on every service-to-service call.
- **Configuration.** Every limit in the design's tables is a setting with the proposed default (retention 30 min, 25 MB, 100 MP, 10 members, 1 GB, 3 and 20 live uploads, 20 announces per minute, 5 joins per minute, 3 strikes). Tests override them.

## Phase 0 — Project foundation

**0.1 Repository and CI.** Create `pyproject.toml` with the stack above, a `uv.lock`, ruff and mypy settings, and pytest with `asyncio_mode = "auto"`. CI runs lint, type check, unit tests, then integration tests against Redis 7.2 and Postgres 16 service containers. Add a pre-commit hook for ruff.
*Done when* `uv run pytest` passes on an empty test suite in CI.

**0.2 Local infrastructure.** `deploy/docker-compose.yml` with:
- `redis` started with `deploy/redis.conf`: `appendonly yes`, `appendfsync everysec`, `protected-mode yes`, TLS port only, and an ACL file with one user per service. `orchestrator` gets `~*` with all commands except `FLUSH*`, `KEYS`, `CONFIG`, `DEBUG` and the `FUNCTION` subcommands other than `LOAD`. `imageserver` gets `%R~session:*`, `%RW~upload:*` and `+fcall +fcall_ro +hget +hmget +exists`, so it can read session state and refresh leases but can't change anything else. `auth` gets `~oauth:* ~refresh:* ~rfam:* ~rl:*` with `+fcall`.
- `redis-replica` with `replicaof redis 6379`.
- `postgres:16`.
- dev TLS certificates from `mkcert` in `deploy/certs/`.

*Done when* `docker compose up` brings up Redis, its replica and Postgres, and a TLS connection with each ACL user succeeds and is refused outside its key patterns.

**0.3 Wire format.** Write `proto/photon.proto` with the design's messages (`AnnounceRequest`, `AnnounceResponse`, `PhotoStored`, `PhotoRejected`, `PhotoEvent`, `Snapshot`, `Error`) exactly as specified, then the added messages:

```proto
// Added fields on design messages (new numbers only)
// AnnounceRequest:  string session_id = 5;
// Error:            repeated uint32 missing_chunks = 5;

message OAuthStartRequest   { string provider = 1; string redirect_uri = 2; string code_challenge = 3; }
message OAuthStartResponse  { string authorization_url = 1; string state = 2; }
message OAuthCallbackRequest{ string code = 1; string state = 2; string code_verifier = 3; string redirect_uri = 4; }
message TokenPair           { string access_token = 1; string refresh_token = 2;
                              int64 access_expires_at_ms = 3; string user_id = 4; }
message RefreshRequest      { string refresh_token = 1; }

message CreateSessionRequest{ uint32 max_members = 1; }
message SessionInfo         { string session_id = 1; string join_code = 2; string invite_url = 3;
                              string qr_payload = 4; int64 expires_at_ms = 5; string role = 6; }
message JoinRequest         { oneof by { string code = 1; string invite_token = 2; } }
message SessionRef          { string session_id = 1; }
message KickRequest         { string session_id = 1; string member_id = 2; }

message UploadStatus        { string upload_id = 1; string state = 2; repeated uint32 received_chunks = 3;
                              uint64 received_bytes = 4; bool server_has_file = 5; }
message CompleteRequest     { uint32 chunk_count = 1; }
message CompleteResponse    { string state = 1; bytes sha256 = 2; int64 stored_at_ms = 3; int64 expires_at_ms = 4; }
message ReportAck           { enum Result { APPLIED = 0; NOOP = 1; } Result result = 1; string event_id = 2; }

message EventBatch          { repeated PhotoEvent events = 1; string cursor = 2; }
message Hello               { string access_token = 1; string session_id = 2; string last_event_id = 3; }
message ClientFrame         { oneof f { Hello hello = 1; uint64 ping = 2; } }
message ServerFrame         { oneof f { PhotoEvent event = 1; Error error = 2; uint64 pong = 3; string ready_cursor = 4; } }
message DeviceRegistration  { string platform = 1; string push_token = 2; }
```

Generate `src/photon/common/pb/photon_pb2.py` with `python -m grpc_tools.protoc --python_out --pyi_out`. CI regenerates it and fails on a diff, and runs `buf breaking --against` the main branch, which enforces the design's rule against reusing a field number.
*Done when* CI fails on a hand-made edit that renumbers a field.

**0.4 Common library.** Build `config.py`, `errors.py` (the full error contract table), `proto.py`, `logging.py`, `metrics.py`, `clock.py` and `ids.py`. Each app mounts `/healthz` (process alive), `/readyz` (Redis and Postgres reachable, function library at the expected version) and `/metrics`.
*Done when* unit tests cover every row of the error contract: status, `retryable`, and `Retry-After` when set.

**0.5 Redis client and function library.** `common/redis.py` creates one `redis.asyncio.Redis` per process over TLS with the service's ACL user. It also creates a second pool for blocking reads (`XREAD BLOCK`, Phase 5), sized by `REDIS_BLOCKING_POOL`. At startup the orchestrator calls the library's `version()` function. If the function is missing or returns another version, it runs `FUNCTION LOAD REPLACE` with `photon.lua`; the orchestrator ACL allows `+function|load`. A function whose arguments change gets a new name (`announce_v2`), and the old one stays for one release, so replicas on the old code keep working during a rolling deploy. The image server only calls `FCALL`/`FCALL_RO` and never loads the library. The test fixture uses a separate Redis database, loads the library, and runs `FLUSHDB` between tests (with an admin connection, not the service user).
*Done when* a test calls `version()`, wipes Redis with `FUNCTION FLUSH`, restarts the orchestrator, and calls it again successfully.

**0.6 Keys.** `tools/gen_keys.py` writes Ed25519 key pairs as PEM: `auth-access` (access JWTs), `orch-upload` (upload tokens), and one service key each for `orchestrator` and `imageserver`. A key id is `<purpose>-<yyyymmdd>`. Private keys are mounted as secret files, readable only by their own service. Public keys are published as JWKS: Auth at `/.well-known/jwks.json`, the orchestrator's upload key on an internal URL that the image server reads.
*Done when* each service starts with only its own private key mounted.

**0.7 Token verification and service tokens.** `common/jwt.py`:
- `verify(token, audience, issuer, jwks)` pins `algorithms=["EdDSA"]`, requires `exp`, `iss`, `aud` and `kid`, and looks `kid` up in a JWKS cache refreshed every 10 minutes and once on an unknown `kid`.
- Service tokens: `iss=<service>`, `aud=<target>-internal`, a 60-second `exp`, minted once and reused until 10 seconds before expiry.
- FastAPI dependencies `current_user()` (access JWT from `Authorization: Bearer`; an expired token gives `401 TOKEN_EXPIRED`, any other failure `401 UNAUTHENTICATED`) and `require_service("imageserver")`.

*Done when* tests reject a wrong algorithm, a wrong audience, an unknown `kid`, an expired token, and a token signed with another service's key.

## Phase 1 — Auth

Auth is its own FastAPI app (`photon.auth.app`). It is the only holder of the access-token signing key and is never on the path of an ordinary request: the orchestrator and the image server verify tokens locally against its JWKS.

**1.1 Account tables.** Alembic migration `0001_accounts`:

```sql
CREATE TABLE users (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  display_name text NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE identities (
  provider         text NOT NULL,
  provider_user_id text NOT NULL,
  user_id          uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (provider, provider_user_id)
);
CREATE TABLE devices (
  id         bigserial PRIMARY KEY,
  user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  platform   text NOT NULL CHECK (platform IN ('android', 'ios')),
  push_token text NOT NULL UNIQUE,
  updated_at timestamptz NOT NULL DEFAULT now()
);
```

No email column: the design forbids merging accounts by email, and storing one invites someone to do it later. SQLAlchemy models go in `auth/models.py`. Auth's Postgres role can write all three tables. The orchestrator gets a read-only role on `devices` for push (Phase 8).
*Done when* `alembic upgrade head` and `alembic downgrade base` both run cleanly in CI.

**1.2 Provider interface.** `auth/providers/base.py`:

```python
class Provider(Protocol):
    name: str
    def authorization_url(self, *, state: str, code_challenge: str,
                          redirect_uri: str, nonce: str) -> str: ...
    async def exchange(self, *, code: str, code_verifier: str,
                       redirect_uri: str) -> ProviderTokens: ...
    async def identity(self, tokens: ProviderTokens, *, nonce: str) -> ProviderIdentity: ...
    # ProviderIdentity = (provider_user_id: str, display_name: str)
```

- `google.py` (OIDC). It builds the URL with `response_type=code`, `scope=openid profile`, `code_challenge_method=S256`, `state` and `nonce`, and exchanges the code at Google's token endpoint with the `code_verifier`. It verifies the ID token with `jwt.PyJWKClient` on Google's JWKS: `iss` is `https://accounts.google.com` or `accounts.google.com`, `aud` is our client id, `exp` hasn't passed, and `nonce` equals the stored one. The provider user id is `sub`.
- Providers without OIDC call the profile API with the access token and keep only the provider user id (and a display name).
- `dev.py` is loaded only when `PHOTON_ENV=dev`. Its authorization URL is a local page that redirects straight back with a code naming a test user. The script client and the end-to-end tests log in with it. Startup fails if it is enabled with `PHOTON_ENV=prod`.

*Done when* unit tests with a mocked Google token endpoint and JWKS reject a wrong `iss`, `aud` or `nonce` and an expired ID token.

**1.3 Registered redirect URIs.** In config, map each `(provider, platform)` to one exact redirect URI: the HTTPS App Link (Android) or Universal Link (iOS), for example `https://photon.example/auth/cb`. A custom scheme is allowed only when `PHOTON_ENV=dev`. Comparison is exact string equality: no prefix, wildcard or normalisation. Serve `/.well-known/assetlinks.json` and `/.well-known/apple-app-site-association` from the same host so the links open the app.

**1.4 `POST /v1/auth/oauth/start`.** Body `OAuthStartRequest { provider, redirect_uri, code_challenge }`. The client generates the PKCE verifier, keeps it in secure storage, and sends only the S256 challenge.
1. Rate limit: 20 a minute per IP (`rl:oauth:{ip}`), else `429 RATE_LIMITED`.
2. Unknown provider or a `redirect_uri` that isn't the registered one: `400 INVALID_REQUEST`. A `code_challenge` that isn't 43 base64url characters: `400`.
3. `state = secrets.token_urlsafe(32)`, `nonce = secrets.token_urlsafe(16)`.
4. `SET oauth:{state} <json {provider, code_challenge, redirect_uri, nonce, created_at}> EX 600 NX`. The value is a JSON string rather than a Redis hash, so the callback can read it with `GETDEL`, which works only on strings.
5. Return `OAuthStartResponse { authorization_url, state }`.

**1.5 `POST /v1/auth/oauth/callback`.** Body `OAuthCallbackRequest { code, state, code_verifier, redirect_uri }`.
1. Rate limit 20 a minute per IP.
2. `GETDEL oauth:{state}`. If the state is missing, expired or already used, the answer is `401 UNAUTHENTICATED`. Because of `GETDEL`, a `state` works exactly once, even when two callbacks race.
3. `redirect_uri` must equal both the stored and the registered value.
4. `base64url(sha256(code_verifier))` must equal the stored challenge (`hmac.compare_digest`).
5. `provider.exchange(...)`, then `provider.identity(...)` with the stored nonce. A provider error becomes `401 UNAUTHENTICATED`, and a provider timeout (10 s) `503 BUSY`.
6. Find or create the user in one transaction: `SELECT user_id FROM identities WHERE provider=$1 AND provider_user_id=$2`. If there is none, insert the user, then the identity with `ON CONFLICT DO NOTHING`, and select again, so two first logins racing for the same account end with one user.
7. Issue a `TokenPair` (1.6, 1.7).

*Done when* tests show: a reused state fails; a wrong verifier fails; a different redirect URI fails; the same provider account logs in twice to the same user id; two accounts with the same email on different providers stay two users.

**1.6 Access JWT.** `auth/tokens.py`: header `{"alg": "EdDSA", "kid": ...}`, claims `sub` (user id), `iss=photon-auth`, `aud=photon`, `iat`, and `exp = iat + 15 min`. Nothing about sessions goes in the token; membership is always read from Redis. `/.well-known/jwks.json` publishes the current key and, during rotation, the next and the previous one.
Key rotation: publish the new key, wait at least 10 minutes (the verifiers' JWKS cache), start signing with it, and remove the old key 15 minutes later (the access-token lifetime).

**1.7 Refresh tokens with rotation, reuse detection and the grace window.**
- Token format: `<family>.<secret>`, where `family` is 16 random bytes and `secret` 32 random bytes, both url-safe base64. Only `sha256(secret)` is stored.
- Keys: `refresh:{family}:<sha256 hex>` (hash: `uid`, `rotated_at`, `successor`), with the refresh lifetime of 30 days (proposed), and `rfam:{family}` (hash: `uid`, `revoked`). This differs from the design's `refresh:{token_hash}`: putting the family in the hash tag keeps a token and its family in one slot, so a rotation is one atomic Lua call even on Redis Cluster.
- Rotation function, `KEYS = [refresh:{f}:h, rfam:{f}]`, `ARGV = [new_hash, now, grace_ms=30000, ttl]`:
  1. Family missing or `revoked=1`: `ERR UNAUTHENTICATED`.
  2. Token missing (expired or unknown): `ERR UNAUTHENTICATED`.
  3. `rotated_at` empty: set `rotated_at=now` and `successor=new_hash`, create `refresh:{f}:new_hash` with the TTL, and return OK.
  4. `rotated_at` set and `now - rotated_at <= grace_ms`: a concurrent or retried refresh. Create another token in the same family, the same way, and return OK.
  5. Otherwise (replay outside the window): set `revoked=1` on the family, log `refresh_reuse_detected`, and return `ERR UNAUTHENTICATED`.
- `POST /v1/auth/refresh` with `RefreshRequest`: rate limit 10 a minute per family, call the function, and on OK return a new `TokenPair`.

*Done when* tests show: two refreshes 100 ms apart both succeed; a replay 31 s later revokes the family; every token of a revoked family then fails.

**1.8 Script client: login.** `tools/photon_cli.py login --provider dev --user alice` runs the PKCE flow against the dev provider and stores the token pair in `~/.photon/alice.json` (mode 0600). Every later command uses one shared `ApiClient`. On `401 TOKEN_EXPIRED` it refreshes once, behind an `asyncio.Lock` (the same single-flight rule as the app), then retries. It follows the error contract's `retryable` and `retry_after_s` fields.
*Done when* `photon_cli.py login` works for two users, and an end-to-end test forces an expired access token and sees exactly one refresh request.

## Phase 2 — Orchestrator: sessions

The orchestrator is a stateless FastAPI app (`photon.orchestrator.app`). This phase builds create, join, leave, kick and end, and the shared Lua helpers every later phase uses. It ends with the design's first build step done: sessions in Redis, with the `ACTIVE` check inside every script.

**2.1 Key helpers.** `orchestrator/keys.py` builds every key name. In the design's notation, `{sid}` in a key is the literal hash tag, so `session:{sid}:photos` for the session `aB3x` is `session:{aB3x}:photos`; other placeholders are plain text. Every session function gets the same six keys in this order, so the Lua code can address them by position:

| `KEYS[i]` | Key | Notes |
| --- | --- | --- |
| 1 | `session:{sid}` | hash: `host`, `status`, `created_at`, `expires_at`, `max_members`, `stored_bytes`, `reserved_bytes`, `join_code`, `invite_token`, `last_event_id`, `trimmed_before`, `ended_at` |
| 2 | `session:{sid}:members` | hash: uid → JSON `{role, joined_at, strikes}` |
| 3 | `session:{sid}:photos` | hash: sha256 hex → JSON `{status, uploader, upload_id, size, mime, taken_at, reserved_at, stored_at, expires_at}` |
| 4 | `session:{sid}:live` | hash: upload_id → JSON `{sha, uploader, size, reserved_at}`; one entry per live reservation (added by this plan) |
| 5 | `session:{sid}:events` | stream |
| 6 | `session:{sid}:kicked` | set of kicked uids, refused on rejoin (added by this plan) |

The lease key is built inside Lua as `'upload:{' .. sid .. '}:' .. upload_id`, in the session's slot. The `live` hash makes the live-upload limits (3 per user, 20 per session) and releasing a member's reservations O(live uploads) rather than a scan of every photo. JSON numbers stay below 10^14, which `cjson` encodes exactly (`%.14g`).

**2.2 Shared Lua helpers.** At the top of `photon.lua`:

```lua
#!lua name=photon
local VERSION = '1'
local HOUR = 3600000

local function ok(...) return {'OK', ...} end
local function err(code) return {'ERR', code} end
local function lease_key(sid, upload_id) return 'upload:{' .. sid .. '}:' .. upload_id end

local function append_event(keys, now, retention_ms, fields)
  local minid = (now - retention_ms - HOUR) .. '-0'
  local id = redis.call('XADD', keys[5], 'MINID', '~', minid, '*', unpack(fields))
  redis.call('HSET', keys[1], 'last_event_id', id, 'trimmed_before', minid)
  return id
end

-- Clears one reservation. Bytes are released only if this upload_id still owns the hash,
-- so no byte is ever released twice.
local function release_reservation(keys, sid, sha, upload_id)
  local raw = redis.call('HGET', keys[3], sha)
  if raw then
    local e = cjson.decode(raw)
    if e.status == 'UPLOADING' and e.upload_id == upload_id then
      redis.call('HDEL', keys[3], sha)
      redis.call('HINCRBY', keys[1], 'reserved_bytes', -e.size)
    end
  end
  redis.call('HDEL', keys[4], upload_id)
  redis.call('DEL', lease_key(sid, upload_id))
end

local function touch(keys, now, idle_ms)
  local exp = now + idle_ms
  redis.call('HSET', keys[1], 'expires_at', exp)
  for i = 1, 6 do redis.call('PEXPIREAT', keys[i], exp + HOUR) end   -- backstop TTL
  return exp
end

local function remove_member(keys, sid, uid, now, retention_ms)
  local live = redis.call('HGETALL', keys[4])
  for i = 1, #live, 2 do
    local r = cjson.decode(live[i + 1])
    if r.uploader == uid then release_reservation(keys, sid, r.sha, live[i]) end
  end
  redis.call('HDEL', keys[2], uid)
  append_event(keys, now, retention_ms, {'t', 'MEMBER_LEFT', 'm', uid})
end

-- Ends the session: ENDED, photos tombstoned, reservations released, SESSION_ENDED appended.
-- Returns the hashes whose files must be deleted and the members whose pointers to clear.
local function do_end(keys, sid, now, retention_ms)
  if redis.call('HGET', keys[1], 'status') ~= 'ACTIVE' then return nil end
  redis.call('HSET', keys[1], 'status', 'ENDED', 'ended_at', now,
             'stored_bytes', 0, 'reserved_bytes', 0)
  local to_delete = {}
  local photos = redis.call('HGETALL', keys[3])
  for i = 1, #photos, 2 do
    local sha, e = photos[i], cjson.decode(photos[i + 1])
    if e.status == 'UPLOADING' then
      redis.call('HDEL', keys[3], sha)
      redis.call('DEL', lease_key(sid, e.upload_id))
    else
      if e.status == 'STORED' then
        e.status = 'EXPIRED'; e.expired_at = now
        redis.call('HSET', keys[3], sha, cjson.encode(e))
      end
      table.insert(to_delete, sha)
    end
  end
  redis.call('DEL', keys[4])
  append_event(keys, now, retention_ms, {'t', 'SESSION_ENDED'})
  local members = redis.call('HKEYS', keys[2])
  for i = 1, 6 do redis.call('PEXPIRE', keys[i], HOUR) end   -- ENDED tombstone for 1 hour
  return {to_delete, members}
end
```

Every function argument list starts with `sid, now_ms, retention_ms`, then its own arguments. `ERR` codes that aren't in the error contract are mapped in `scripts.py` (for example `SESSION_FULL` becomes `403 FORBIDDEN` with the message *Session is full*).

**2.3 The one-session pointer.** `user:{uid}:session` lives in another slot, so it is handled in Python around the session function, as the design says. Two single-key functions are added to the library: `ptr_cas(KEYS=[ptr], old, new, ttl)` (set only if the value is still `old`) and `ptr_del(KEYS=[ptr], sid)` (delete only if the value is `sid`).

```python
async def claim_pointer(uid: str, sid: str) -> None:
    key = keys.user_session(uid)
    for _ in range(3):
        if await r.set(key, sid, nx=True, ex=POINTER_TTL_S):
            return
        old = await r.get(key)
        if old == sid:
            return
        if old is None:
            continue                            # expired between SET and GET: try again
        if await is_live_member(old, uid):     # status ACTIVE and uid in members
            raise ApiError("ALREADY_IN_SESSION")
        if await fn.ptr_cas([key], [old, sid, POINTER_TTL_S]):
            return                              # pointer to an ended session overwritten
    raise ApiError("BUSY")
```

`release_pointer(uid, sid)` calls `ptr_del`. `POINTER_TTL_S` is the idle timeout plus one hour, refreshed whenever the session is touched (2.8). A pointer to an ended or missing session is ignored and overwritten, so a crashed client is never stuck.

**2.4 Create: `POST /v1/sessions`.** Body `CreateSessionRequest { max_members }` (default 10, at most 10).
1. Rate limit: 10 creates an hour per user (proposed).
2. `sid = ids.new_sid()`, then `claim_pointer(uid, sid)`.
3. Join code: up to 5 tries of `SET code:<code> sid NX EX 7200`. Invite: `SET invite:<token> sid NX EX 43200`. The lifetimes (2 hours and 12 hours) are proposed values in config.
4. `create_session` function: if `KEYS[1]` exists, `ERR CONFLICT` (retry with a new sid). Otherwise write the session hash (`status=ACTIVE`, counters 0, `last_event_id=0-0`, `trimmed_before=0-0`), add the host to the members hash with `role=host` and `strikes=0`, append `MEMBER_JOINED`, and `touch`.
5. On any failure after step 2: delete the code and invite keys and `release_pointer`.
6. `ZADD sessions:due <expires_at> <sid>` (the idle-expiry index, 2.8).
7. Return `SessionInfo`: `invite_url = https://<host>/j/<token>`, `qr_payload = invite_url` (the QR code only encodes the link), `role = host`.

**2.5 Join: `POST /v1/sessions/join`.** Body `JoinRequest` (code or invite token).
1. Rate limits: 5 a minute per user and 5 a minute per IP (`rl:join:u:<uid>`, `rl:join:ip:<ip>`). The IP comes from `X-Forwarded-For`, trusted only from the configured proxy addresses.
2. Resolve `GET code:<code>` or `GET invite:<token>`. If it's missing, the answer is `404 SESSION_NOT_FOUND` (a code added by this plan, see the additions).
3. `claim_pointer(uid, sid)`.
4. `join` function:

```lua
local function f_join(keys, args)
  local sid, now, retention, uid, idle = args[1], tonumber(args[2]), tonumber(args[3]), args[4], tonumber(args[5])
  local st = redis.call('HMGET', keys[1], 'status', 'max_members')
  if st[1] ~= 'ACTIVE' then return err('SESSION_ENDED') end
  if redis.call('HEXISTS', keys[2], uid) == 1 then return ok('ALREADY_MEMBER') end
  if redis.call('SISMEMBER', keys[6], uid) == 1 then return err('FORBIDDEN') end
  if redis.call('HLEN', keys[2]) >= tonumber(st[2]) then return err('SESSION_FULL') end
  redis.call('HSET', keys[2], uid, cjson.encode({role = 'member', joined_at = now, strikes = 0}))
  append_event(keys, now, retention, {'t', 'MEMBER_JOINED', 'm', uid})
  touch(keys, now, idle)
  return ok('JOINED')
end
```

5. If the function returns `ERR`, `release_pointer(uid, sid)` (the compensation the design asks for) and raise the error.
6. Return `SessionInfo` with the caller's role. The client then loads the snapshot, opens the WebSocket from the snapshot's cursor, and starts watching the gallery (Phase 5).

**2.6 Leave: `POST /v1/sessions/leave`.** Body `SessionRef`. The `leave` function:
1. Session not `ACTIVE`, or the caller not a member: `OK` with nothing to do (leaving is idempotent).
2. `remove_member` (releases the caller's live reservations, appends `MEMBER_LEFT`).
3. If the caller was host and members remain, the member with the smallest `joined_at` becomes host (update their role and `session.host`).
4. If nobody is left, `do_end`.
5. Return whether the session ended, plus `do_end`'s lists.

Then Python runs `release_pointer(uid, sid)`, and if the session ended, the end clean-up of 2.7.

**2.7 End and kick.** Two endpoints added by this plan (the design gives the host these powers but lists no endpoints):
- `POST /v1/sessions/{sid}/end`: the `end_session` function checks the caller is host (`403 FORBIDDEN`), then runs `do_end`. A second call is `OK` with nothing to do.
- `POST /v1/sessions/{sid}/kick` with `KickRequest`: the `kick` function checks the caller is host and the target is another member, then `remove_member(target)` and `SADD kicked target`, in one call. The target's in-flight chunks and `complete` then fail the membership check, and their WebSocket closes when its reader sees `MEMBER_LEFT` for its own uid (Phase 5).
- End clean-up in Python, after the function: delete the code and invite keys (their names are in the session hash), `ZREM sessions:due sid`, `release_pointer` for every member returned, `ZADD expiry:<n> LT <now> <sid>:<sha>` for every hash returned (Phase 6), and start the image-server deletes at once without waiting for them. If the process crashes before this clean-up, nothing breaks: the codes expire, every join checks `ACTIVE`, pointers to an ended session are ignored, and the image server's own sweeper deletes the files at their `expires_at`.
- `POST /v1/sessions/{sid}/invites/rotate` (host): new code and invite token, old ones deleted. This is how the host revokes a leaked code (added by this plan).

**2.8 Idle expiry.** Sessions end after 12 hours without activity (proposed, `SESSION_IDLE_MS`).
- `touch_session` function: if `ACTIVE`, `touch`, and return the new `expires_at`. Python then runs `ZADD sessions:due GT <expires_at> <sid>` and refreshes every member's pointer TTL. An `ActivityTracker` in each process calls it at most once a minute per session, on WebSocket heartbeats, announces and joins.
- `jobs/session_sweeper.py`: every 30 seconds, take `lock:session_sweeper` (`SET NX PX 25000` with a random token, released by compare-and-delete). Read `ZRANGEBYSCORE sessions:due -inf <now> LIMIT 0 100`. For each sid, run `end_session` as `system` only if `session.expires_at <= now`; otherwise re-add the sid with its real `expires_at`. Run the end clean-up of 2.7. A missing session is removed from the set.

**2.9 Session routes and errors.** `routes/sessions.py` wires 2.4–2.8 and maps function errors through `scripts.py`. Every handler that names a `sid` checks that the caller is a member, inside the function, not with a separate read.

**2.10 Script client.** `photon_cli.py create`, `join <code>`, `leave`, `end`, `kick <user>`, `rotate`.

**2.11 Tests (integration, real Redis).**
- Ten users join a session with `max_members=3` at once: exactly 2 joins succeed (plus the host) and every refused user's pointer is released.
- A user creates two sessions at once: one succeeds, the other gets `409 ALREADY_IN_SESSION`, and no orphan code or session remains.
- A pointer to an ended session is overwritten by the next join.
- Joins race `end` (100 iterations of `asyncio.gather`): after the end, members never grow, and every join that ran after it got `410 SESSION_ENDED`.
- The host leaves: the longest-present member becomes host. The last member leaves: the session is `ENDED`.
- A kicked user's rejoin is `403`. Their live reservations are released and `reserved_bytes` drops by exactly their sizes.
- After the end, a join gives `410` during the 1-hour tombstone, then `404 SESSION_NOT_FOUND`.
- The idle sweeper ends a session whose `expires_at` has passed and leaves a touched one alone (time driven through `now_ms`).

## Phase 3 — Image server: upload and complete

The image server (`photon.imageserver.app`) owns the bytes. It never changes session state and never notifies members. It reads session state from Redis, refreshes upload leases, and reports to the orchestrator. Run it as one uvicorn process per host. CPU work goes to a thread pool (hashing, file I/O) or to sandboxed subprocesses (decoding). In this phase the reports go to a fake orchestrator; the real fenced endpoints arrive in Phase 4.

**3.1 Disk layout and write helpers.** `imageserver/store.py`. Everything sits under one root on one filesystem, so every `rename` is atomic:

```
/var/lib/photon/
├── tmp/{upload_id}/
│   ├── state.json            # authoritative upload state (3.4)
│   ├── .lock                 # fcntl.flock target
│   ├── chunks/{n}            # received chunks; {n}.part while being written
│   ├── assembled             # being hashed
│   └── staged/               # verified upload, kept under its upload_id until the orchestrator accepts it
│       ├── original
│       ├── thumb.webp
│       └── meta.json
├── photos/{sid}/{sha256}/    # promoted: original, thumb.webp, preview.webp, meta.json
├── outbox/{upload_id}.json   # pending stored/rejected reports
└── trash/                    # renamed here, then removed in the background
```

`atomic_write(path, data)` writes `path.tmp`, then flushes, `fsync`s, renames, and `fsync`s the directory. Every state change goes through it before the response is sent. Every path for an upload contains its `upload_id` until promotion (3.9), so clean-up of one upload can never touch another.

This plan moves promotion after the orchestrator's answer: the design renames into the final path before the report, then says the files of a fenced-out upload, and only those, are deleted. Keeping the verified upload under `tmp/{upload_id}/staged/` until the orchestrator answers `200` satisfies both: a `409` or `410` deletes exactly `tmp/{upload_id}/`, and the photo directory is only ever created for an accepted upload.

**3.2 Upload token check.** `imageserver/auth.py`: verify the upload JWT against the orchestrator's upload JWKS with `aud=image-server`. Claims: `sid`, `sha256` (hex), `size`, `mime`, `taken_at_ms`, `uploader`, `upload_id`, `exp` (10 minutes). `mime` and `taken_at_ms` are added by this plan, because the image server needs them for type checking and for `PhotoStored`. The `upload_id` in the path must equal the claim, otherwise `404 UNKNOWN_UPLOAD`.

**3.3 Access checks.** `imageserver/access.py`, using the library's functions:
- `upload_check(sid, uid, upload_id)` (`FCALL_RO`): session status, membership, and whether the lease exists. If the lease is gone, it also says whether the photo entry now names another `upload_id`.
- `touch_lease(sid, upload_id, state)`: if the lease exists, `PEXPIRE` it to 15 minutes, set its `state` field, and return 1; otherwise 0.
- A per-process TTL cache of `(sid, uid) → (status, is_member)`, at most 5 seconds for chunks. `complete` never uses it.

Mapping: status not `ACTIVE` → `410 SESSION_ENDED`. Not a member → `403 NOT_A_MEMBER`. Lease gone and the entry names another id → `409 UPLOAD_SUPERSEDED`. Lease gone otherwise → `410 UPLOAD_EXPIRED`.

**3.4 Upload state machine.** `imageserver/upload_state.py`. The design's four states, plus four this plan needs to make `complete` idempotent end to end:

| State | Meaning | A repeated `complete` answers |
| --- | --- | --- |
| `RECEIVING` | Chunks arriving | Runs the checks and starts verifying |
| `VERIFYING` | Assembling and hashing | Never seen under the lock; found after a crash, it resets to `RECEIVING` (3.8 step 2) |
| `VERIFIED` | Hash and format good, original staged, thumbnail missing | Resumes at the thumbnail, no re-hash |
| `REPORTING` | Staged, report in the outbox | `202`, `Retry-After: 2` |
| `STORED` | Orchestrator accepted, promoted | `200` with the same `CompleteResponse` |
| `REJECTED` | Hash mismatch or invalid image | The same `422` |
| `SUPERSEDED` | Orchestrator answered `409` | `409 UPLOAD_SUPERSEDED` |
| `ENDED` | Orchestrator answered `410` | `410 SESSION_ENDED` |

`state.json` holds `upload_id`, `sid`, `sha256`, `size`, `mime`, `taken_at_ms`, `uploader`, `state`, `created_at`, `thumb_attempts`, `lease_missing_since` and `result` (the `stored_at`/`expires_at` or the error code). Each upload has an `asyncio.Lock` (a dict of weak references) plus `fcntl.flock` on `tmp/{upload_id}/.lock`, so a second worker process can't interleave.

**3.5 `PUT /v1/uploads/{upload_id}/chunks/{n}`.** Headers: `Authorization: Bearer <upload token>`, `X-Chunk-CRC32C` (base64 of the big-endian CRC32C), `Content-Length`.
1. Token (3.2). `n` must be below 4096, and `Content-Length` at most `MAX_CHUNK` (8 MiB), else `400 INVALID_REQUEST` or `413 TOO_LARGE`.
2. Load `state.json`, creating it from the claims as `RECEIVING` if absent. Any state other than `RECEIVING`: `409 UPLOAD_FINALIZED`.
3. Cached session and membership check; `touch_lease` (uncached), mapped as in 3.3.
4. Temporary-disk budget: if the bytes under `tmp/` plus `Content-Length` exceed `TMP_DISK_BUDGET`, or no slot is free in the chunk-write semaphore (default 64), answer `503 BUSY` with `Retry-After: 5`. Keep the bytes under `tmp/` in a counter, rebuilt by a directory walk at startup.
5. The sum of the other chunks plus this one must not exceed the token's `size`: `413 TOO_LARGE`.
6. Stream the body to `chunks/{n}.part`, updating CRC32C as it goes. On a CRC mismatch, delete the part and answer `400 INVALID_REQUEST` with `retryable=true` (the client resends that chunk).
7. `fsync`, then rename to `chunks/{n}`. A repeated chunk simply replaces the old one.
8. `204`.

The client chooses one chunk size per upload and keeps it for that upload. The server never assumes a chunk size: it sums what it has.

**3.6 `GET /v1/uploads/{upload_id}`.** Token required. Returns `UploadStatus`: the state, the chunk indexes present, the bytes received, and `server_has_file` (true when `photos/{sid}/{sha256}/meta.json` exists and hasn't expired). If there is no state yet but the lease exists, the answer is `RECEIVING` with no chunks. If there is neither, `404 UNKNOWN_UPLOAD`.

**3.7 Sandboxed decoding.** `imageserver/decode_worker.py` runs as `python -I -m photon.imageserver.decode_worker <mode> <in> <out_dir>`. It is launched with `asyncio.create_subprocess_exec`, `start_new_session=True`, an empty environment, and a `preexec_fn` that sets `RLIMIT_AS` (1.5 GB), `RLIMIT_CPU` (20 s), `RLIMIT_FSIZE` (64 MB) and `RLIMIT_NOFILE` (32). Wall-clock limit: `asyncio.wait_for(proc.wait(), 30)`, then `os.killpg(SIGKILL)`. At most `DECODE_CONCURRENCY` workers run at once (default: cores).
- `validate`: detect the type from magic bytes (JPEG `FF D8 FF`; PNG `89 50 4E 47`; WebP `RIFF....WEBP`; HEIC/HEIF an `ftyp` box with brand `heic`, `heix`, `mif1`, `msf1` or `hevc`). It must be on the allow-list and equal the declared `mime`. Open with Pillow (`pillow_heif.register_heif_opener()`). Check `width × height ≤ 100 000 000` before decoding, with Pillow's own bomb guard turned off in favour of this explicit check. Reject `n_frames > 1` (animated WebP, APNG, multi-image HEIC). Then `img.load()` decodes the whole image. Output: JSON with format, width and height.
- `thumb`: `ImageOps.exif_transpose`, convert to sRGB (through `ImageCms` when an ICC profile is present), longest edge 320 px, save as WebP quality 70 without EXIF, XMP or ICC.
- `preview`: the same, with longest edge 1600 px and quality 80.
- Exit codes: 0 success; 10 invalid image (permanent); 11 transient (`MemoryError`, `ENOSPC`). Death by signal or timeout counts as a limit hit.

How results are read: in `validate`, a limit hit is `INVALID_IMAGE`, as in the design. In `thumb`, a limit hit or a transient error is transient (3.8 step 11).

**3.8 `POST /v1/uploads/{upload_id}/complete`.** Body `CompleteRequest { chunk_count }`. `pipeline.complete()` runs under the upload lock:

1. Token check. No `state.json`: if the lease exists, `400 INVALID_REQUEST` with every index in `missing_chunks`; otherwise `404 UNKNOWN_UPLOAD`.
2. A final or reporting state answers from the table in 3.4. A leftover `VERIFYING` (from a crash) deletes `assembled` and resets to `RECEIVING`. The chunks consumed before the crash are gone, so step 6 lists them as missing and the client resends them.
3. Uncached checks: session `ACTIVE`, uploader a member, lease present (3.3 mapping).
4. **Reuse after Redis loss:** if `photos/{sid}/{sha256}/meta.json` exists and hasn't expired, skip to step 13 with that meta's `stored_at` and `expires_at`, so retention is never extended. Nothing is re-hashed and no second copy is stored.
5. `VERIFIED`: jump to step 11.
6. Chunks `0 … chunk_count-1` must all exist, with no index beyond them, and their sizes must add up to the token's `size`. Otherwise `400 INVALID_REQUEST` with `missing_chunks`.
7. Write `VERIFYING`. In a worker thread, stream the chunks in order into `assembled`, updating SHA-256 in 1 MiB blocks, and delete each chunk once it's consumed (peak extra disk is about one photo). Then `fsync`.
8. Digest ≠ the token's `sha256`: delete `tmp/{upload_id}/` except `state.json`, write `REJECTED` with `HASH_MISMATCH`, write the outbox record `rejected` (3.9), and answer `422 HASH_MISMATCH`.
9. `validate` (3.7). Invalid: the same as step 8 with `INVALID_IMAGE`.
10. Rename `assembled` to `staged/original`, `fsync` the directory, and write `VERIFIED`.
11. Thumbnail: increment and persist `thumb_attempts`, then run `thumb`. Permanent failure, or a transient failure at the third attempt in total: reject as `INVALID_IMAGE` (step 8, and delete `staged/`). Transient failure with attempts left: answer `503 PROCESSING_FAILED` with `Retry-After: 5`; the next `complete` resumes at this step. A photo is never reported without a thumbnail.
12. `staged/meta.json`: `sid`, `sha256`, `size`, `mime`, `uploader`, `upload_id`, `taken_at_ms`, `width`, `height`, `stored_at_ms = now` and `expires_at_ms = now + RETENTION_MS`. `fsync`.
13. Write the outbox record `stored` (a `PhotoStored` with this `upload_id` and the meta's times), then write `REPORTING`. Send the report at once and wait up to 5 seconds:
    - `200` (`APPLIED` or `NOOP`): promote (3.9), write `STORED`, and answer `200 CompleteResponse`.
    - `409`: delete `tmp/{upload_id}/` except `state.json`, write `SUPERSEDED`, and answer `409 UPLOAD_SUPERSEDED`.
    - `410`: the same, with `ENDED` and `410 SESSION_ENDED`.
    - No answer within 5 seconds: `202` with `Retry-After: 2`. The outbox keeps trying, and the client's next `complete` reads the result.

`stored_at` is fixed when the meta is written (step 12), so retention runs from the moment the file is verified and saved, and a retry never extends it.

**3.9 Outbox and promotion.** `imageserver/outbox.py`. A record (`outbox/{upload_id}.json`) holds the kind, the serialised `PhotoStored` or `PhotoRejected`, the attempt count and the next attempt time. It is written with `atomic_write` before the first send.
- One sender task per process. It loads every record at startup (the crash recovery the design asks for), wakes on new records (`asyncio.Event`) and every second, and calls `POST /internal/v1/photos/stored` or `/rejected` with a service token and a 10-second timeout. Failures back off exponentially, 1 s to 60 s with full jitter, with no limit on attempts.
- Final answers are `200`, `409` and `410`. Their effects run under the upload lock, in an order that makes each step safe to repeat: (1) apply the effect, (2) write the state, (3) delete the record.
  - `stored` and `200`: promote by renaming `tmp/{upload_id}/staged` to `photos/{sid}/{sha256}`. If the target already exists (the reuse path or a duplicate), delete `staged` instead. `fsync` the parent directory.
  - `stored` and `409` or `410`: delete `tmp/{upload_id}/` except `state.json`. That upload's files are the only ones touched.
  - `rejected` and any final answer: nothing left to delete, so just remove the record.
- `401` or `403` from the orchestrator (a broken service token) is retried and counted on a metric with an alert. Gauges: pending records and the age of the oldest.

**3.10 A fake orchestrator for this phase.** `tests/fakes/orchestrator.py` is a small FastAPI app that answers the internal endpoints from a script (`APPLIED`, `NOOP`, `409`, `410`, a timeout, or a `500` followed by success). Integration tests start the image server against it with real Redis. Each test seeds the session and lease keys directly with a helper that calls the library functions, so the keys look exactly as they will once Phase 4 exists.

**3.11 Script client.** `photon_cli.py upload-raw --token <upload token> <file>` uploads with three chunks in parallel and follows the `complete` loop (`202` and `503` honoured). It drives Phase 3 before announce exists.

**3.12 Tests.**
- A repeated chunk is harmless. A CRC mismatch gives a retryable `400`. Bytes over `size` give `413`. A missing chunk is listed by `complete`.
- `complete` repeated in every state answers as in the table in 3.4, including the same `422` after a rejection.
- A wrong hash ends in `422 HASH_MISMATCH`, a `rejected` report, and no file in `photos/`.
- A crash between steps 12 and 13 (the test kills the process): on restart the outbox resends, and promotion happens exactly once.
- A crash during step 7: the next `complete` lists the consumed chunks as missing; after they are resent, it succeeds.
- A transient thumbnail failure (worker forced to exit 11) gives `503`. The next `complete` succeeds without hashing again (a counter on the hash function). A third failure gives `INVALID_IMAGE` and the files are deleted.
- Hostile files: a decompression-bomb PNG, a 30 000 × 30 000 header, an animated WebP and an APNG, a truncated JPEG, a PNG declared as JPEG, a HEIC with two primary images, and a file that exceeds `RLIMIT_AS`. Each ends in `INVALID_IMAGE`, and the server process stays healthy. Trailing data after a valid JPEG is accepted (see [Decided while building](#decided-while-building)).
- Disk budget reached gives `503 BUSY`. A session that isn't `ACTIVE` refuses chunks and `complete` with `410`. A chunk after `STORED` gives `409 UPLOAD_FINALIZED`.
- `409` and `410` answers delete exactly `tmp/{upload_id}/` and nothing under `photos/`.

## Phase 4 — Announce, dedupe, quota and fencing

This phase connects the two services and builds the rule that runs through every upload call: a call names its `upload_id`, and the orchestrator applies it only if that id is still the live one for the hash. The concurrency tests in 4.9 are written here, before any client exists, as the design asks.

**4.1 Upload tokens.** `orchestrator/upload_tokens.py` mints an Ed25519 JWT with the `orch-upload` key: `iss=photon-orchestrator`, `aud=image-server`, `sid`, `sha256` (hex), `size`, `mime`, `taken_at_ms`, `uploader`, `upload_id`, and `exp = now + 10 min`. The public key is served at `/internal/v1/jwks.json` for the image server.

**4.2 The `announce` function.** Arguments after `sid, now, retention`: `uid, sha, size, mime, taken_at, new_upload_id, cap_bytes, max_live_user, max_live_session, max_strikes, lease_ms (900000), takeover_ms (60000)`.

```lua
local function f_announce(keys, args)
  local sid, now = args[1], tonumber(args[2])
  local uid, sha, size, mime, taken_at, new_id =
        args[4], args[5], tonumber(args[6]), args[7], tonumber(args[8]), args[9]
  local cap, max_user, max_session, max_strikes =
        tonumber(args[10]), tonumber(args[11]), tonumber(args[12]), tonumber(args[13])
  local lease_ms, takeover_ms = tonumber(args[14]), tonumber(args[15])

  if redis.call('HGET', keys[1], 'status') ~= 'ACTIVE' then return err('SESSION_ENDED') end
  local m = redis.call('HGET', keys[2], uid)
  if not m then return err('NOT_A_MEMBER') end
  if cjson.decode(m).strikes >= max_strikes then return err('UPLOADS_BLOCKED') end

  local raw = redis.call('HGET', keys[3], sha)
  if raw then
    local e = cjson.decode(raw)
    if e.status == 'STORED' or e.status == 'EXPIRED' then return ok('ALREADY_UPLOADED') end
    if e.status == 'RECALLED' then return err('PHOTO_RECALLED') end
    if redis.call('EXISTS', lease_key(sid, e.upload_id)) == 1 then
      if e.uploader == uid then return ok('UPLOAD', e.upload_id) end  -- same id, fresh token
      return ok('IN_PROGRESS')
    end
    if now - e.reserved_at < takeover_ms then return ok('IN_PROGRESS') end
    release_reservation(keys, sid, sha, e.upload_id)                   -- stale: take over
  end

  -- Live-upload limits. Reservations whose lease is gone are stale and released here.
  local live, n_user, n_session = redis.call('HGETALL', keys[4]), 0, 0
  for i = 1, #live, 2 do
    local r = cjson.decode(live[i + 1])
    if redis.call('EXISTS', lease_key(sid, live[i])) == 0 and now - r.reserved_at >= takeover_ms then
      release_reservation(keys, sid, r.sha, live[i])
    else
      n_session = n_session + 1
      if r.uploader == uid then n_user = n_user + 1 end
    end
  end
  if n_user >= max_user or n_session >= max_session then return err('TOO_MANY_UPLOADS') end

  local b = redis.call('HMGET', keys[1], 'stored_bytes', 'reserved_bytes')
  if tonumber(b[1]) + tonumber(b[2]) + size > cap then return err('QUOTA_EXCEEDED') end

  if redis.call('HSETNX', keys[3], sha, cjson.encode({status = 'UPLOADING', uploader = uid,
      upload_id = new_id, size = size, mime = mime, taken_at = taken_at, reserved_at = now})) == 0 then
    return ok('IN_PROGRESS')                                           -- unreachable inside one call
  end
  redis.call('HSET', keys[4], new_id, cjson.encode({sha = sha, uploader = uid, size = size, reserved_at = now}))
  redis.call('HINCRBY', keys[1], 'reserved_bytes', size)
  local lk = lease_key(sid, new_id)
  redis.call('HSET', lk, 'uploader', uid, 'sha256', sha, 'size', size, 'state', 'RECEIVING')
  redis.call('PEXPIRE', lk, lease_ms)
  return ok('UPLOAD', new_id)
end
```

Error mapping in `scripts.py`: `UPLOADS_BLOCKED` → `403 FORBIDDEN` (*Uploads blocked in this session*). `PHOTO_RECALLED` → `410`. `TOO_MANY_UPLOADS` → `429`, `Retry-After: 10`. `QUOTA_EXCEEDED` → `429`, `Retry-After: 60` (quota frees as photos expire).

**4.3 `POST /v1/photos/announce`.** Body `AnnounceRequest` (with the added `session_id`).
1. `current_user()`. Check `sha256` is 32 bytes, `size` is above 0, `taken_at_ms` is plausible (`400` otherwise). `size` over `MAX_PHOTO_BYTES`: `413 TOO_LARGE`. `mime` not in `{image/jpeg, image/png, image/webp, image/heic, image/heif}`: `415 UNSUPPORTED_FORMAT`.
2. Rate limit 20 a minute per user: `429 RATE_LIMITED` with the window's remaining time.
3. `announce` with `new_upload_id = uuid4().hex`.
4. `UPLOAD`: mint a fresh token, even for an existing `upload_id` (the design's idempotent announce), and return `upload_url = <IMAGE_BASE>/v1/uploads/<id>` and `chunk_size_hint = 1 MiB`. `ALREADY_UPLOADED` and `IN_PROGRESS` return just the result.
5. `ActivityTracker.touch(sid)`.

**4.4 The `report_stored` function.** Arguments after `sid, now, retention`: `sha, upload_id, stored_at, expires_at`.

```lua
local function f_report_stored(keys, args)
  local sid, now, retention = args[1], tonumber(args[2]), tonumber(args[3])
  local sha, upload_id, expires_at = args[4], args[5], tonumber(args[7])
  if redis.call('HGET', keys[1], 'status') ~= 'ACTIVE' then return err('SESSION_ENDED') end
  local raw = redis.call('HGET', keys[3], sha)
  if not raw then return err('UPLOAD_SUPERSEDED') end
  local e = cjson.decode(raw)
  if e.upload_id ~= upload_id then return err('UPLOAD_SUPERSEDED') end   -- fencing
  if e.status ~= 'UPLOADING' then return ok('NOOP') end                  -- duplicate of an applied report
  if redis.call('HEXISTS', keys[2], e.uploader) == 0 then
    release_reservation(keys, sid, sha, upload_id)
    return err('UPLOAD_SUPERSEDED')
  end
  e.status, e.stored_at, e.expires_at = 'STORED', tonumber(args[6]), expires_at
  redis.call('HSET', keys[3], sha, cjson.encode(e))
  redis.call('HINCRBY', keys[1], 'reserved_bytes', -e.size)
  redis.call('HINCRBY', keys[1], 'stored_bytes', e.size)
  redis.call('HDEL', keys[4], upload_id)
  redis.call('DEL', lease_key(sid, upload_id))
  local id = append_event(keys, now, retention, {'t', 'PHOTO_STORED', 'sha', sha, 'up', e.uploader,
                          'sz', e.size, 'ta', e.taken_at, 'ex', expires_at})
  return ok('APPLIED', id)
end
```

`STORED` and its event are written in the same call, so `STORED` exists exactly when its event does. A report naming any other `upload_id` changes nothing, so a late `stored` can never revive an old reservation.

**4.5 The `report_rejected` function.** Arguments after `sid, now, retention`: `sha, upload_id, reason`. If the session isn't `ACTIVE`: `ERR SESSION_ENDED`. If the entry is `UPLOADING` with this `upload_id`: `release_reservation`, add one strike to the uploader (if still a member), and return `OK APPLIED`. Otherwise `ERR UPLOAD_SUPERSEDED`, which a repeat of an applied rejection also gets: the reservation is already gone, the image server's action is the same, and the strike is never counted twice. Both 422 reasons (`HASH_MISMATCH`, `INVALID_IMAGE`) count as strikes, as in the error contract.

**4.6 Internal endpoints.** `routes/internal.py`, guarded by `require_service("imageserver")`:
- `POST /internal/v1/photos/stored` with `PhotoStored`: `APPLIED` → `200 ReportAck{APPLIED, event_id}`; `NOOP` → `200 ReportAck{NOOP}`; `UPLOAD_SUPERSEDED` → `409`; `SESSION_ENDED` → `410`.
- After `APPLIED` or `NOOP`: `ZADD expiry:<shard(sid)> <expires_at> <sid>:<sha>` (Phase 6). Running it again on `NOOP` repairs a crash between the function and this call. Then queue a push hint (Phase 8).
- `POST /internal/v1/photos/rejected` with `PhotoRejected`, mapped the same way.
- If `|stored_at - now| > 2 s`, log `clock_skew` and count it on a metric: `expires_at` is compared across hosts, so skew must alert (Phase 9).

**4.7 Recall: `POST /v1/sessions/{sid}/photos/{hash}/recall`.** The `recall` function:
1. Session not `ACTIVE`: `410 SESSION_ENDED`. Caller not a member: `403 NOT_A_MEMBER`.
2. Entry missing or `UPLOADING`: `404 PHOTO_NOT_FOUND`. `EXPIRED`: `410 PHOTO_EXPIRED`. Already `RECALLED`: `OK` (idempotent).
3. The caller must be the uploader or the host: otherwise `403 FORBIDDEN`.
4. Set `RECALLED` with `recalled_at`, subtract the size from `stored_bytes`, and append `PHOTO_RECALLED`.

Python then runs `ZADD expiry:<n> LT <now> <sid>:<sha>` and calls the image server's `DELETE` at once, without waiting for it; the sweeper retries if it fails. Downloads stop immediately, because every download checks for `STORED` (Phase 5). A later announce of the same hash gets `410 PHOTO_RECALLED`.

**4.8 Script client.** `photon_cli.py upload <file>`: hash, announce, then on `UPLOAD` call `GET /v1/uploads/{id}`. If `server_has_file` is set, go straight to `complete`. Otherwise send the missing chunks, three at a time, each retried up to 3 times with backoff and jitter, then loop on `complete`. `IN_PROGRESS` waits for the event (Phase 5) and `ALREADY_UPLOADED` exits. `photon_cli.py recall <hash>`.

**4.9 Concurrency and fencing tests.** Real Redis, real image server, a real orchestrator, time driven through `now_ms`.
- **Concurrent announce:** 10 members announce one hash at once: exactly one `UPLOAD`, nine `IN_PROGRESS`.
- **Idempotent announce:** the same user announces twice and gets the same `upload_id` with two different tokens; both tokens upload chunks.
- **Quota race:** cap 100 MB, live limits raised, 20 parallel announces of 10 MB each: exactly 10 succeed and `stored + reserved` never exceeds the cap.
- **Stale report:** upload A, its lease is deleted and 60 s pass, B takes over. A's `rejected` gets `409` and the entry still names B. A's `stored` gets `409`. B's `stored` is applied, and A's files under `tmp/` are deleted.
- **Report after end:** `stored` after `end` gets `410`, the staged files are deleted, and no event is appended.
- **Announce racing end:** 200 iterations of `gather(end, announce…)`: once `ENDED`, there are no `UPLOADING` entries and both counters are 0.
- **Duplicate report:** `stored` twice: `APPLIED` then `NOOP`, one event.
- **Redis loss after storage:** delete the photo entry. The re-announce gets a new `upload_id`, `GET` shows `server_has_file`, and `complete` re-reports with the original `expires_at`. One file on disk.
- **Kick during upload:** the next chunk gets `403 NOT_A_MEMBER` and `reserved_bytes` drops by the upload's size.
- **Recall:** recall, then announce the same hash: `410 PHOTO_RECALLED`. Recall by a member who is neither uploader nor host: `403`.
- **Strikes:** three mismatches, then `403` on the fourth announce.
- **Model-based test:** a hypothesis `RuleBasedStateMachine` with the rules announce, chunk, complete, stored/rejected report (current or stale `upload_id`), lease expiry, takeover, kick, recall, expire and end. After every step it checks these invariants:
  - `reserved_bytes` = the sum of `UPLOADING` sizes, and `stored_bytes` = the sum of `STORED` sizes;
  - `stored + reserved ≤ cap`;
  - every `STORED` hash has exactly one `PHOTO_STORED` event;
  - an `ENDED` session has no `UPLOADING` entry and no live lease;
  - no two live leases exist for one hash.

## Phase 5 — Events, WebSocket, snapshot and resync

Events come from one durable source, the session's Redis stream. Delivery is at-least-once, and the client's dedupe by `event_id` and hash makes the effect exactly-once. This phase also builds downloads, because a receiver needs them as soon as an event arrives.

**5.1 Stream entries.** `append_event` (2.2) writes compact fields: `t` (type), `sha` (hex), `up` (uploader), `sz`, `ta` (taken at), `ex` (expires at) and `m` (member). It trims by age with `MINID ~ (now - retention - 1h)` and records that threshold as `trimmed_before`. `orchestrator/events.py` turns an entry into a `PhotoEvent`, with the stream id as `event_id` and the hash as raw bytes. Stream ids are compared as `(ms, seq)` tuples, never as strings.

**5.2 The resync rule.** A cursor `c` can be continued only if all three hold:
1. `c >= trimmed_before`;
2. the stream exists, or the session's `last_event_id` is `0-0`;
3. `c <= last_event_id`.

If any fails, the answer is `RESYNC_REQUIRED`. Condition 2 catches a stream missing after a Redis restore. Condition 3 catches a client that saw events Redis has since lost in a failover. Approximate trimming may keep a few entries older than `trimmed_before`; the rule still sends those clients to the snapshot, which is safe.

**5.3 Snapshot: `GET /v1/sessions/{sid}/snapshot`.** The `snapshot` function (`no-writes`, called with `FCALL_RO`):
1. Session not `ACTIVE`: `410 SESSION_ENDED`. Caller not a member: `403 NOT_A_MEMBER`.
2. Read the cursor first: the session's `last_event_id`.
3. Then the state: the member ids; every `STORED` photo with `expires_at > now`, as a `PhotoEvent` of type `PHOTO_STORED` without an `event_id`; and every `RECALLED` hash.

The function is atomic, so the cursor and the state are consistent. Anything stored after the cursor still arrives as an event, and the client drops it by hash. Returns `Snapshot`.

**5.4 Catch-up: `GET /v1/sessions/{sid}/events?since=<id>&limit=500`.** The `events_since` function (`no-writes`) checks membership and status, applies the resync rule (`410 RESYNC_REQUIRED`), and runs `XRANGE events (<since> + COUNT <limit>`, where `(` means exclusive. It returns an `EventBatch` whose `cursor` is the last id returned. The client repeats until a batch is shorter than `limit`. This endpoint is used after a push wake-up and on app start.

**5.5 WebSocket: `/v1/ws`.** `orchestrator/ws.py`. The frames are `ClientFrame` and `ServerFrame` (binary protobuf).
1. Accept, then wait up to 10 s for `ClientFrame.hello`. None: close `4400`.
2. Verify the access token. Expired: close `4001`. Invalid: close `4401`. The token is never read from the URL.
3. Check status and membership, and apply the resync rule to `hello.last_event_id`. An empty `last_event_id` also counts as needing resync, because a client must load the snapshot before connecting. Failures send `ServerFrame.error`, then close: `4403` (not a member), `4410` (session ended), `4409` (resync required).
4. Send `ready_cursor`.
5. **Reader task.** It loops `XREAD BLOCK 15000 COUNT 100 STREAMS session:{sid}:events <last_id>` on the blocking pool, starting from the client's id. Backlog and live events come through this one loop: there is no separate replay phase, so no gap. Each entry becomes an `event` frame, and `last_id` advances. When the reader sees `MEMBER_LEFT` for this socket's own uid, it closes `4403`. On `SESSION_ENDED`, it sends the event and closes `4410`. After each empty `XREAD`, it re-checks status, membership and the resync rule, which catches a stream lost in a Redis restore.
6. **Receiver task.** Handles `ping` → `pong`, and calls `ActivityTracker.touch(sid)` and the presence update (`HSET session:{sid}:online <uid> <now>`, used by push in Phase 8), both throttled to once a minute.
7. **Token timer.** At the access token's `exp`, close `4001`. The client refreshes and reconnects from its last id.
8. When either task ends, cancel the other and return the blocking connection to the pool.

| Close code | Meaning | Client action |
| --- | --- | --- |
| 4001 | Access token expired | Refresh, reconnect with the last event id |
| 4400 | No or bad `hello` | Fix and reconnect |
| 4401 | Token invalid | Log in again |
| 4403 | Not a member (left or kicked) | Stop syncing; rejoin only if the user chooses |
| 4409 | Resync required | Load the snapshot, reconnect from its cursor |
| 4410 | Session ended | Stop syncing, clear session state |
| 1012 | Server restarting | Reconnect with backoff and jitter |

Each open socket holds one blocking Redis connection. That suits version 1 (10 members per session). Size `REDIS_BLOCKING_POOL` to the expected open sockets per process, and alert at 80% use. Load balancers need WebSocket upgrades enabled and an idle timeout above the 30-second ping interval. No sticky routing is needed.

**5.6 Downloads: `GET /v1/sessions/{sid}/photos/{hash}?size=thumb|preview|full`** (image server, `routes/downloads.py`):
1. Verify the user's access JWT (`aud=photon`). An upload token is refused.
2. The `download_check` function (`FCALL_RO`). Membership may come from a cache of at most 2 seconds, but the photo's status and `expires_at` are always read fresh. Results:
   - not a member, or the photo missing or `UPLOADING`: `404 PHOTO_NOT_FOUND` (non-members can't learn that a photo exists);
   - session not `ACTIVE`: `410 SESSION_ENDED`;
   - `EXPIRED`, or `now >= expires_at`: `410 PHOTO_EXPIRED`;
   - `RECALLED`: `410 PHOTO_RECALLED`.
3. Find the files in `photos/{sid}/{sha}/`. During the short window between the orchestrator applying the report and promotion, fall back to `tmp/{upload_id}/staged/` (the entry's `upload_id`). Still missing: `404`.
4. Read `meta.json`. If `now >= meta.expires_at_ms`, the answer is `410 PHOTO_EXPIRED`, whatever Redis says. This is the image server's independent guard.
5. `thumb`: send `thumb.webp`. `preview`: on first request, generate it under a per-photo lock with the `preview` worker and cache it as `preview.webp`; a failure answers `503 PROCESSING_FAILED` and the client falls back. `full`: send `original`, with single-range `Range` support (`206`, `Content-Range`, `416` for a bad range). The range code is written here rather than relying on the framework's version, and tested.
6. Headers: `Cache-Control: private, no-store`, `ETag: "<sha>-<size>"`, `Content-Type` from the meta. There is no URL a non-member could reuse.

**5.7 Script client.** `photon_cli.py listen`: load the snapshot, connect with its cursor, print events, download each new photo's thumbnail, and save the original to a folder. It keeps `last_event_id` on disk, drops an event at or below it, drops a hash it already holds, and handles every close code in 5.5. `photon_cli.py catchup` uses 5.4.

**5.8 Tests.**
- **Gap-free:** a producer appends 2 000 events while a consumer disconnects and reconnects at random points. After dedupe, the consumer has every event exactly once, in order.
- **Trim:** a cursor older than `trimmed_before` gets `RESYNC_REQUIRED` on both the WebSocket and `/events`. Deleting the stream gives the same.
- **Snapshot then stream:** a photo stored between the snapshot and the connect is delivered once after dedupe.
- **Closes:** token expiry closes with `4001`, a kick with `4403`, the session end with `4410` after the `SESSION_ENDED` frame.
- **Downloads:** a non-member gets `404`. A tampered Redis `expires_at` in the future still gets `410` from the meta check. `no-store` is set. `Range` returns the right bytes. A recalled photo gets `410` at once.

## Phase 6 — Retention and deletion

Logical expiry is exact: from `expires_at` no download succeeds, which Phase 5 already enforces in two independent places. This phase makes physical deletion bounded: within 5 minutes after `expires_at`, with an alert at 15. Deletion has two independent paths, the orchestrator's sweeper and the image server's own.

**6.1 Expiry index.** `expiry:<n>` with `n = crc32(sid) % EXPIRY_SHARDS` (16). Members are `<sid>:<sha>`, scored by the time the file must go. Entries are added:
- on `stored` (`APPLIED` or `NOOP`), with `expires_at` (4.6);
- on recall and on session end, with `now`, through `ZADD … LT` so a score only ever moves earlier (4.7, 2.7).

An entry leaves the index only when the image server acknowledges the delete.

**6.2 The `expire_photo` function.** If the entry is `STORED` and `expires_at <= now`: set `EXPIRED`, subtract its size from `stored_bytes`, and return `EXPIRED`. Any other case (already `EXPIRED` or `RECALLED`, session ended, keys gone) returns `GONE` and changes nothing. Bytes are subtracted only on the `STORED` → `EXPIRED` transition, so never twice. No event is appended: clients already know `expires_at`.

**6.3 Orchestrator sweeper.** `jobs/expiry_sweeper.py`. In every orchestrator process, every 60 seconds and for each shard:
1. Take `lock:expiry:<n>` (`SET NX PX 55000` with a random token, released by compare-and-delete), so one process sweeps a shard at a time.
2. `ZRANGEBYSCORE expiry:<n> -inf <now> LIMIT 0 200`.
3. For each entry, call `expire_photo`, then `DELETE /internal/v1/photos/{sid}/{hash}` on the image server (service token, 5-second timeout, at most 16 in parallel).
4. `204` or `404`: `ZREM`. A failure leaves the entry, and retries back off by rewriting its score to `now + min(2^attempts, 300) s`, with the attempt count in `expiry_attempts:<n>`.
5. Metrics: deletes attempted and failed, and the age of the oldest due entry (the deletion backlog).

A photo marked `EXPIRED` while its file still exists is safe, because downloads are refused.

**6.4 Image server delete: `DELETE /internal/v1/photos/{sid}/{hash}`.** Guarded by `require_service("orchestrator")`. Rename `photos/{sid}/{hash}` into `trash/<uuid>` (atomic; the photo is gone for readers at once) and remove the trash in the background. A missing directory still answers `204`, so the call is idempotent. When `meta.json` was readable, observe `deletion_lag_seconds = now - expires_at` (negative for recall and session end, which aren't counted as lag).

This can't race a promotion of the same hash. A promotion needs an `APPLIED` or `NOOP` report, and `report_stored` requires an `ACTIVE` session and an `UPLOADING` entry. An expired or recalled hash only ever answers `ALREADY_UPLOADED` or `410`, so it is never uploaded again.

**6.5 Image server sweeper.** `imageserver/sweeper.py`, every 60 seconds. It decides from its own files, never from Redis alone:
1. `photos/*/*/meta.json` with `now >= expires_at`: move to trash. Observe `deletion_lag_seconds`, and count `files_overdue_total` when the lag is over 15 minutes.
2. A `photos/*/*` directory without `meta.json`, older than 1 hour by mtime: an orphan. Delete it and count `orphans_found_total`.
3. `tmp/{upload_id}/`:
   - skip it while `outbox/{upload_id}.json` exists;
   - delete it when the session is `ENDED` or missing;
   - when the lease is missing, set `lease_missing_since` in `state.json` on the first sweep that sees it, and delete the directory once that is more than 15 minutes ago;
   - keep a directory in a final state (`STORED`, `REJECTED`, `SUPERSEDED`, `ENDED`) for 1 hour, so repeated `complete` calls still get their answer, then delete it.
4. Empty `trash/` and empty `photos/{sid}/` directories.
5. Gauges: files past `expires_at` still on disk, and bytes under `tmp/`.

The sweeper keys every rule on the `upload_id` or the file's own metadata. Losing Redis therefore can't make a valid file vanish early, or leave a dead one forever.

**6.6 Quota recount and stale reservations.** `jobs/recount.py`, every 5 minutes, under a lock, for each sid in `sessions:due`. The `recount` function:
1. Releases `UPLOADING` entries whose lease is missing and whose `reserved_at` is more than 60 s old (the design's stale transition), with `release_reservation`.
2. Marks `STORED` entries with `expires_at <= now` as `EXPIRED`. This repairs a crash between `report_stored` and the expiry `ZADD`. It returns those hashes for `ZADD expiry … LT now`.
3. Recomputes `stored_bytes` (the sum of `STORED` sizes) and `reserved_bytes` (the sum of `UPLOADING` sizes), writes them, and returns the corrections.

Python observes the corrections in `quota_correction_bytes`. A non-zero value is a bug to investigate, not just noise.

**6.7 Session end and tombstones.** Session end queues every file with score `now` (2.7), so the sweeper deletes it within a minute. The orchestrator also fires the deletes immediately. The session keys carry a 1-hour TTL after the end, which removes the tombstones and the remaining photo entries; the expiry entries leave on acknowledgement.

**6.8 Tests.**
- At `expires_at - 1 ms`, a download succeeds; at `expires_at`, `410`. Test once with only Redis advanced and once with only the meta check.
- The file is gone after one sweep past `expires_at`. With the orchestrator sweeper disabled, the image server's sweeper alone deletes it.
- The image server answers `500` to deletes three times: the entry stays in `expiry:<n>` and goes after the fourth try succeeds.
- Orphan rules: a directory without meta older than 1 hour is removed. A `tmp/` directory with its lease gone for 16 minutes is removed, but kept while an outbox record exists. A `tmp/` directory of an `ENDED` session is removed.
- `FLUSHALL` after storing three photos: the image server still deletes them at `expires_at`.
- Inject drift into `stored_bytes` and `reserved_bytes`: the recount restores both and reports the correction.
- Recall: the file is gone within one sweep and the download answers `410` at once.

## Phase 7 — Android client (Kotlin)

This is the only phase not in Python. The design needs `MediaStore`, a foreground service and WorkManager, and only a native app can reach them. The server path is complete and tested by now, and the Python script client stays the reference implementation for every call. Detection is best effort; the server-side guarantees don't depend on it.

**7.1 Project.** Kotlin, `minSdk 26` (needed for `java.util.zip.CRC32C`), `targetSdk` current. Jetpack Compose, OkHttp, protobuf from the same `proto/photon.proto` (Wire, or protobuf-javalite), Room, WorkManager, DataStore, and the AndroidX Security library for Keystore-backed storage. Room tables:

| Table | Columns | Used for |
| --- | --- | --- |
| `session_state` | `session_id`, `role`, `join_time_ms`, `last_event_id`, `scan_cursor_ms`, `paused` | One row while in a session |
| `pending_photos` | `media_id` (PK), `uri`, `taken_at_ms`, `state`, `sha256`, `copy_path`, `upload_id`, `chunk_size`, `attempts` | The upload queue; `state` ∈ `PENDING, HASHED, UPLOADING, WAITING, SYNCED, SKIPPED, FAILED` |
| `received_assets` | `media_id` (PK), `sha256`, `session_id` | Echo layer 1 |
| `hashes` | `sha256` (PK), `source` (`RECEIVED`/`UPLOADED`), `session_id` | Echo layer 4 and receive dedupe |
| `received_photos` | `sha256` (PK), `uploader`, `taken_at_ms`, `expires_at_ms`, `state`, `thumb_path`, `preview_path` | Receive pipeline; `state` ∈ `THUMB, PREVIEW, SAVED, RECALLED, EXPIRED` |

**7.2 Network layer.**
- One `OkHttpClient`. An interceptor adds the access token.
- An `Authenticator` refreshes on `401 TOKEN_EXPIRED` behind a `Mutex` (single-flight), then retries once. A failed refresh sends the user to login.
- `ApiError` is parsed from the `Error` body. One `RetryPolicy` reads `retryable` and `retry_after_s` and is used by every worker.

**7.3 Login.**
1. Generate the PKCE verifier and keep it in encrypted storage.
2. Call `oauth/start` with the challenge and open the URL in Custom Tabs, never a WebView.
3. Receive the redirect on the verified App Link (`autoVerify="true"`; the backend serves `assetlinks.json`, 1.3).
4. Call `oauth/callback` with the code, the state and the verifier, then delete the verifier.

Tokens are stored with Keystore-backed encryption.

**7.4 Sessions UI.**
- Create: show the code and a QR code of the invite link (ZXing).
- Join by code, by QR scan, or by invite App Link. Before the join call, show the consent text: photos taken during the session are shared with its members.
- Leave. For the host: kick, end, and rotate invites.
- While syncing: a persistent indicator and a pause switch.
- After a join: load the snapshot, store its cursor and `join_time_ms`, start the foreground service (7.5), and run the reconcile scan (7.6).

**7.5 Detection: foreground service and observer.**
- `SyncService` runs while a session is active, with a visible notification that names the session and offers *Pause* and *Leave*.
- It registers a `ContentObserver` on `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`. On a change it queries rows newer than `scan_cursor_ms` that pass the eligibility filter (7.6), inserts them into `pending_photos` (`INSERT OR IGNORE` on `media_id`), and enqueues unique work `upload-queue` with `KEEP`. The observer never hashes and never touches the network.
- The service also holds the WebSocket (7.8).
- **Foreground service type:** Android 14 requires a declared type. `dataSync` is limited to 6 hours in 24 on Android 15, shorter than a 12-hour session. Use `specialUse` with a Play Console justification. If it must be `dataSync`, handle `onTimeout` by stopping the service and relying on the reconcile scan when the app is opened. This is listed as a decision to confirm.
- **Permissions:** `READ_MEDIA_IMAGES` (13+), `READ_EXTERNAL_STORAGE` (≤ 12), `POST_NOTIFICATIONS` (13+) and `FOREGROUND_SERVICE_SPECIAL_USE`. On Android 14, partial access (`READ_MEDIA_VISUAL_USER_SELECTED`) hides new photos from the app. Detect it and ask for full access, with an explanation. Do **not** request `ACCESS_MEDIA_LOCATION` while GPS stripping is on (7.7).

**7.6 Reconcile scan.** `ReconcileWorker` runs on session start, app start, service restart and `BOOT_COMPLETED` (when a session is still stored). It queries `MediaStore` for images where:
- `DATE_TAKEN` (falling back to `DATE_ADDED × 1000`) is at least `join_time_ms` and before the session's end;
- the row isn't pending (`IS_PENDING = 0`);
- `RELATIVE_PATH` starts with `DCIM/`, is not `Pictures/PHOTON/`, and is not `Screenshots`;
- `OWNER_PACKAGE_NAME` is not this app;
- `media_id` is not in `received_assets` or `pending_photos`.

It inserts the matches as `PENDING`, advances `scan_cursor_ms`, and enqueues `upload-queue`. Camera folder names differ between manufacturers, so test the filter on Pixel, Samsung and Xiaomi devices.

**7.7 Upload worker.** `UploadWorker` (a `CoroutineWorker`, network constraint, expedited when allowed, exponential backoff). It does nothing while the session is paused. For each `PENDING` or `UPLOADING` row:
1. **Copy and hash.** Copy the image through `ContentResolver.openInputStream` into the app's cache, and compute SHA-256 over that copy. Without `ACCESS_MEDIA_LOCATION`, Android redacts location from the stream, so the copy has no GPS. The hash is over exactly the bytes uploaded. Verify this redaction for both JPEG and HEIC on test devices. If HEIC isn't redacted, strip GPS from the copy, or skip HEIC until it is decided. If the hash is in `hashes`, mark the row `SKIPPED` (echo layer 4).
2. **Announce.**
   - `ALREADY_UPLOADED`: record the hash and mark the row `SYNCED`.
   - `IN_PROGRESS`: mark it `WAITING`. Another member's upload is live; a `PHOTO_STORED` event for the hash marks the row `SYNCED`. Without that event, re-announce after 2 minutes, backing off to 15. Never mark the row `SYNCED` on `IN_PROGRESS` alone: if the other upload is rejected (for example, someone announced this hash with other bytes), this device must still upload the real photo.
   - `UPLOAD`: store `upload_id` and continue.
   - `410 PHOTO_RECALLED` or `410 SESSION_ENDED`: `SKIPPED`.
3. **Resume.** `GET /v1/uploads/{id}`. If `server_has_file` is set, go to step 5.
4. **Chunks.** The chunk size is chosen once per upload from the measured throughput (aim for about 2 seconds per chunk, between 256 KiB and 8 MiB) and stored in `chunk_size`. Send the missing chunks, three in parallel, each with `X-Chunk-CRC32C`, each retried up to 3 times with backoff and jitter. After that, return `Result.retry()`: the row and its copy persist, so the next run sends only what is missing.
5. **Complete,** in a loop: `202` and `503` wait for `Retry-After`; `200` marks `SYNCED` and records the hash as `UPLOADED`; `422` marks `FAILED` and shows a notification; `409 UPLOAD_SUPERSEDED` or `UPLOAD_FINALIZED` re-announces if the row isn't `SYNCED`; `410` marks `SKIPPED`.
6. Delete the cached copy once the row reaches a final state.

**7.8 Event connection.** Inside `SyncService`, and in the app while it is in the foreground:
- Connect to `/v1/ws` with `hello { access_token, session_id, last_event_id }` and ping every 30 seconds.
- Process events in order. Write `last_event_id` to Room after each event is handled, not before.
- Close codes as in 5.5: `4001` refreshes and reconnects; `4409` loads the snapshot, applies it, and reconnects from its cursor; `4403` and `4410` stop syncing.
- Other drops reconnect with exponential backoff and jitter. On every reconnect, app start and push hint (Phase 8), run `/events?since=` first.

**7.9 Receiving.** `ReceivePipeline`:
1. **Dedupe.** Drop an event whose id is at or below `last_event_id`, and a `PHOTO_STORED` whose hash is already in `hashes` or `received_photos`.
2. **Thumbnail.** Fetch at once, keep it in the app cache only, and show it.
3. **Preview.** Fetch on an unmetered network or one with `getLinkDownstreamBandwidthKbps() >= 5000`; on `503`, keep the thumbnail.
4. **Original.** Fetch at once on an unmetered network. With the setting *Originals on Wi-Fi or while charging*, enqueue two unique works, one requiring unmetered and one requiring charging, and let whichever runs first save and cancel the other.
5. **Save the original only.** Insert into `MediaStore` with `RELATIVE_PATH = Pictures/PHOTON/`, `IS_PENDING = 1` and `DATE_TAKEN = taken_at_ms`, then write the bytes. **Record `media_id` in `received_assets` and the hash in `hashes` before setting `IS_PENDING = 0`.** The observer fires when the row becomes visible, and by then the row is already known. Thumbnails and previews never go to the gallery.
6. `PHOTO_RECALLED`: delete the cached thumbnail and preview, cancel a pending original download, and mark the photo `RECALLED`. Saved originals are never deleted.
7. `expires_at` passed: drop cached files and cancel pending downloads; the server answers `410` anyway.

**7.10 Echo prevention, layer by layer.**
1. Asset identity: `received_assets`, checked by the observer and the scan.
2. Location: received photos go to `Pictures/PHOTON/`, which both skip.
3. Owner: rows whose `OWNER_PACKAGE_NAME` is this app are skipped.
4. Hash set: the `hashes` table, checked after hashing.
5. Server: the session-wide dedupe gives `ALREADY_UPLOADED`.

Layers 1–3 come before hashing, because Android may re-encode a saved copy and change its bytes.

**7.11 Recall and privacy UI.**
- *Recall* on the uploader's and the host's photos. The dialog says plainly that recall stops distribution and removes cached copies, but cannot delete originals that members already saved, or reach a member who never reconnects.
- A pause switch: it stops enqueuing and uploading, and resuming runs the reconcile scan.
- A setting for GPS stripping, on by default (if turned off, request `ACCESS_MEDIA_LOCATION`).
- *Report photo* (Phase 8).

**7.12 Leaving, kicks and session end.** On leave, `4403`, `SESSION_ENDED` or `410 SESSION_ENDED` from any call: stop `SyncService`, cancel the session's work, delete cached copies and thumbnails, and clear `session_state`. Keep `hashes` and `received_assets` for 24 hours, then delete them.

**7.13 Tests.**
- Unit: the error mapper, chunk planning, eligibility filters, dedupe.
- Instrumented:
  - a photo taken by a camera intent reaches `pending_photos`;
  - a received photo saved to the gallery is never enqueued (the echo test);
  - killing the process mid-upload resumes without resending finished chunks;
  - airplane mode during an upload, then reconnect;
  - reboot during a session runs the reconcile scan;
  - partial media access shows the prompt.
- End-to-end: two emulators and the Python script client in one session.

## Phase 8 — Limits, privacy, HTTP/3, push and iOS

**8.1 Rate limits, completed.** `common/ratelimit.py` implements a fixed window as one function: `INCR`, `PEXPIRE` on the first hit, and return the count and the remaining TTL. Over the limit: `429 RATE_LIMITED` with `Retry-After` set to the remaining TTL. Every limit in the design's table is now wired:

| Limit | Key | Default |
| --- | --- | --- |
| Announces | `rl:announce:<uid>` | 20 a minute |
| Joins | `rl:join:u:<uid>`, `rl:join:ip:<ip>` | 5 a minute each |
| Session creates | `rl:create:<uid>` | 10 an hour |
| OAuth start and callback | `rl:oauth:<ip>` | 20 a minute |
| Refresh | `rl:refresh:<family>` | 10 a minute |
| Any request, per IP | at the proxy (Caddy `rate_limit` or nginx `limit_req`) | 50 a second, burst 200 |

Member cap, byte quota, live uploads, temporary disk and strikes were built in Phases 2–4. A test checks every row of the design's limits table against its enforcement point.

**8.2 Reporting a photo.** The design lets members report a photo but lists no endpoint, so this plan adds `POST /v1/sessions/{sid}/photos/{hash}/report` (member only, reason text up to 500 characters). It writes to a Postgres table `photo_reports` (sid, sha256, reporter, uploader, reason, created_at), counts `photo_reports_total`, and notifies the operators' channel. The photo itself is kept only until its normal expiry. The illegal-content and takedown process needs a legal review before any public launch, as the design says; this endpoint is the hook it will need.

**8.3 Privacy controls, completed.** Check that each one exists end to end:
- the sync indicator (the foreground notification and in-app status);
- the pause switch;
- GPS removed by default (7.7);
- the recall dialog text (7.11);
- the consent text on join (7.4).

**8.4 HTTP/3.** Put Caddy in front of the image server (`deploy/Caddyfile`):
- `servers { protocols h1 h2 h3 }` and UDP 443 open. Caddy advertises `Alt-Svc` itself.
- `reverse_proxy imageserver:8000` with `flush_interval -1` and request bodies streamed, not buffered.
- `request_body { max_size 9MB }` on the chunk route.
- An idle timeout above the slowest expected chunk.

On Android, use Cronet through the `cronet-okhttp` bridge for the image-server client only. It tries QUIC when `Alt-Svc` allows and falls back to HTTP/2 over TCP by itself. The orchestrator client stays on OkHttp (HTTP/2).
*Done when* uploads work with UDP blocked (HTTP/2), and with UDP open the access log shows `h3`. Switching the device from Wi-Fi to cellular mid-upload continues the upload without a failed chunk.

**8.5 Push as a hint.**
- Device registration: `PUT /v1/devices/current` with `DeviceRegistration` on Auth. It upserts into `devices` (an endpoint added by this plan, since the design has the table but no endpoint). The client calls it on login and whenever FCM gives a new token.
- `orchestrator/push.py`. After `APPLIED` stored, recall and session end, find the session's members whose presence (`session:{sid}:online`) is older than 90 seconds. For each, `SET push:<uid> 1 NX EX 30` coalesces a burst into one push, then send an FCM data-only message `{"sid": ..., "kind": "events"}` with the `firebase-admin` async client.
- On `UNREGISTERED` or `INVALID_ARGUMENT`, delete that `devices` row. Failures are logged and never retried: push is best effort and never carries photo data.
- Client: `FirebaseMessagingService.onMessageReceived` enqueues an expedited `CatchUpWorker`. The worker runs `/events?since=` (or the snapshot on `RESYNC_REQUIRED`), feeds `ReceivePipeline`, and runs the reconcile scan. App start runs the same worker.

**8.6 iOS (Swift).** The same server, and the same proto through `swift-protobuf`.
- Login with `ASWebAuthenticationSession`. Universal Links through `apple-app-site-association` (1.3). Tokens in the Keychain.
- `PHPhotoLibraryChangeObserver` while the app is alive.
- On launch and on return to the foreground: the reconcile scan (`PHAsset` with `creationDate` at or after the join time, `mediaSubtypes` not containing `.photoScreenshot`, not in the received set), then catch-up.
- Uploads through a background `URLSession`, with each chunk written to a file first, so transfers continue while the app is suspended. `complete` runs at the next launch or background wake.
- GPS removal: copy the image with `CGImageDestinationCopyImageSource` and the GPS dictionary removed, which needs no re-encode for JPEG or HEIC. Hash the copy.
- Saving received originals: `PHAssetCreationRequest` into a *PHOTON* album, recording the `localIdentifier` before the change block finishes.
- APNs silent push (`content-available: 1`) through `aioapns` on the server triggers catch-up when iOS allows it, and is never relied on.
- State plainly in the app that nothing syncs while it is suspended.

## Phase 9 — Observability, deployment and release

**9.1 Metrics.** Prometheus names (labels in brackets):

| Area | Metrics |
| --- | --- |
| Deletion | `deletes_total{result}`, `deletion_lag_seconds` (histogram), `files_past_expiry` (gauge), `orphans_found_total{kind}`, `expiry_backlog_oldest_seconds` |
| Reports | `outbox_pending`, `outbox_oldest_seconds`, `reports_total{kind,answer}` (answer: applied, noop, superseded, ended) |
| Uploads | `announce_total{result}`, `takeovers_total`, `stale_reservations_released_total`, `tmp_disk_bytes`, `quota_rejections_total`, `busy_responses_total`, `verifying_seconds` (histogram), `hash_mismatch_total`, `invalid_image_total` |
| Delivery | `upload_duration_seconds` (histogram, from announce to stored), `stored_to_delivered_seconds` (histogram, from the event's stream time to the WebSocket send), `ws_open`, `resync_required_total`, `push_sent_total{result}` |
| Redis | `redis_exporter`: replication lag, AOF delayed fsync, memory; `quota_correction_bytes` |
| Clocks | `node_timex_offset_seconds` (node exporter), `clock_skew_reports_total` |

p50 and p95 come from the histograms.

**9.2 Alerts** (`deploy/alerts.yml`):
- `files_past_expiry` with a lag over 15 minutes is above 0 (page);
- `outbox_oldest_seconds` over 300 (page);
- `tmp_disk_bytes` above 80% of the budget;
- the delete failure rate over 5% for 10 minutes;
- the hash-mismatch rate over 3× its 24-hour baseline;
- `expiry_backlog_oldest_seconds` over 300;
- a clock offset over 100 ms on any host;
- Redis replication lag over 5 seconds;
- `REDIS_BLOCKING_POOL` use over 80%.

**9.3 Logs.** structlog JSON lines with `request_id`, `sid`, `uid`, `upload_id` and `sha256` (the hash prefix only). Never log tokens, codes or invite tokens. Image bytes and EXIF are never logged.

**9.4 Deployment.**
- One container image per service, built from the same `pyproject`, plus Caddy.
- Orchestrator: two or more replicas behind the load balancer (WebSocket upgrade enabled, idle timeout 60 s). Auth: two replicas. Image server: one instance with a persistent volume (`/var/lib/photon`) at first.
- Redis: primary and replica with AOF `everysec`, TLS, and an ACL user per service, on a private network only. Postgres: managed or with daily backups and point-in-time recovery.
- TLS 1.3 on every hop. Internal calls use an internal CA and the service tokens of 0.7.
- `chrony` on every host.
- Secrets (signing keys, provider client secrets, FCM credentials) come from the secret store as files.
- Migrations (Alembic) and the Redis function library load run before the new version receives traffic.
- More than one image server later: route by `upload_id` (consistent hash at the proxy), or move storage to S3-compatible object storage with the same key layout. The metadata files and the sweeper move with it.

**9.5 Load test.** `tools/loadtest.py` (asyncio, the script client's code): 200 sessions × 10 members. Each member uploads 30 photos of 3–8 MB over 30 minutes and receives everyone else's. It measures:
- announce and complete latencies;
- `stored_to_delivered` p95 under 1 second on a LAN;
- Redis CPU and memory;
- open sockets and blocking-pool use;
- temporary disk.

Run it again with one orchestrator replica killed halfway.

**9.6 Failure drills.** Run each, then check the recovery rule from the design's Data model section:
- kill the image server during `complete` (the outbox resends, one file);
- Redis failover to the replica (some re-announces, `RESYNC_REQUIRED` for clients ahead of the new primary, counters fixed by the recount);
- `FLUSHALL` (files still deleted on time);
- block UDP (HTTP/2 fallback);
- stop the orchestrator for 10 minutes (the outbox drains afterwards and no file outlives its expiry by 15 minutes).

**9.7 Release checklist.**
- [ ] Every review finding has a passing test (next section).
- [ ] Load test and failure drills passed.
- [ ] Legal review of reporting and takedown done.
- [ ] Security review: decoder sandbox, upload limits, token audiences, OAuth redirect matching, Redis ACLs.
- [ ] The design's open decisions confirmed: Android first; 30-minute retention with deletion within 5 minutes; GPS stripped by default; 25 MB, 100 MP, 1 GB and the format allow-list; 10 members, 3 and 20 live uploads.
- [ ] This plan's decisions confirmed: the foreground service type (`specialUse` or `dataSync`, 7.5); HEIC GPS handling (7.7); the refresh-token lifetime (30 days, 1.7); kicked users can't rejoin (2.7); code and invite lifetimes (2 hours and 12 hours, 2.4).

## Review findings to steps and tests

Every finding in the design's last table, with the steps that build its fix and the test that proves it.

| # | Finding | Built in | Proved by |
| --- | --- | --- | --- |
| 1 | Stale upload report clears a newer reservation | 4.4, 4.5, 3.9 | 4.9 stale report; model-based test |
| 2 | Join or upload succeeds after session end | 2.2 (`do_end`), 2.5, 4.2, 4.4, 3.5, 3.8 | 2.11 joins race end; 4.9 announce racing end, report after end |
| 3 | Events missed between replay and live | 5.5 (one `XREAD` loop) | 5.8 gap-free |
| 4 | `EXPIRED` in Redis while the file still exists | 5.6 step 4, 6.3, 6.4, 6.5 | 6.8 sweeper alone, failing deletes |
| 5 | Lua across hash slots | Conventions, 2.1, 2.3, 1.7 | Unit test: the `KEYS` of every function call hash to one slot (CRC16 of the tag) |
| 6 | `complete` retried after storage | 3.4, 3.8 | 3.12 repeated `complete` in every state |
| 7 | Concurrent uploads exceed the quota | 4.2 | 4.9 quota race |
| 8 | Recall cannot reach offline devices | 4.7, 5.3 (recalled hashes), 7.9 step 6 | 5.8 recall gives `410`; 7.13 snapshot after reconnect clears caches |
| 9 | Stream trimmed before a client returns | 5.2, 5.3, 5.4 | 5.8 trim |
| 10 | Thumbnail generation fails after storage | 3.8 step 11 | 3.12 transient thumbnail failure |
| 11 | Cleanup of stale uploads hits newer ones | 3.1, 3.9, 6.5 | 3.12 deletes only `tmp/{upload_id}/`; 6.8 orphan rules |
| 12 | Resource exhaustion from temporary files | 3.5 step 4, 3.7, 3.8 step 7, 4.2 | 3.12 disk budget; 4.9 live limits |
| 13 | Expired photo downloadable through stale state | 5.6 | 5.8 tampered `expires_at` |
| 14 | Files whose Redis metadata is gone | 6.5 | 6.8 `FLUSHALL` |
| 15 | Membership and status not checked everywhere | 3.3, 3.5, 3.8, 4.2, 4.4, 5.3–5.6 | 4.9 kick during upload; 5.8 non-member download |
| 16 | Observer and WorkManager roles blurred | 7.5, 7.6, 7.7 | 7.13 camera intent, process kill |
| 17 | iOS cannot sync while suspended | 8.6 | Manual test plan on device |
| 18 | Push treated as reliable | 8.5, 7.8 | Drop every push: the app still catches up on start and reconnect |
| 19 | Re-encoding changes the hash | 7.9 step 5, 7.10 | 7.13 echo test |
| 20 | Recall cannot delete saved originals | 7.11 | Review of the dialog text |
| 21 | OAuth redirect and state validation | 1.3, 1.4, 1.5 | 1.5 tests |
| 22 | Image validation underspecified | 3.7 | 3.12 hostile files |
| 23 | Upload token invalidation | 3.3, 3.5, 3.8 | 4.9 stale report, kick; 3.12 after `STORED` |
| 24 | No error contract | 0.4 | 0.4 table tests |
| 25 | Quota accounting | 2.2, 4.2, 4.4, 4.5, 6.2, 6.6 | 4.9 model-based invariants; 6.8 drift |
| 26 | Concurrent refresh revokes a family | 1.7, 7.2 | 1.7 tests; 1.8 single refresh |
| 27 | Meaning of 30 minutes | 3.8 step 12, 5.6, Phase 6 | 6.8 boundary test |
| 28 | Deletion monitoring | 6.3, 6.4, 6.5, 9.1, 9.2 | 9.6 drills fire the alerts in staging |
| 29 | Upload monitoring | 9.1, 9.2 | 9.5 load test dashboards |
| 30 | Server guarantees mixed with best effort | The design's table; 9.7 | Release review |
| 31 | Redis durability consequences | 3.8 step 4, 5.2, 6.6, 9.6 | 9.6 failover and `FLUSHALL` drills |
| 32 | Open decisions | 9.7 | Release checklist |
| 33 | Chunks or `complete` after finalization | 3.4, 3.5 step 2 | 3.12 chunk after `STORED` |
| 34 | Token in the WebSocket URL, expiry mid-connection | 5.5 steps 1–2 and 7 | 5.8 closes |
| 35 | Kicked member's uploads and socket | 2.7, 5.5 step 5 | 2.11 kick; 4.9 kick during upload; 5.8 `4403` |
| 36 | Host leaves the session | 2.6 | 2.11 host leaves |
| 37 | Clock differences decide expiry | 3.8 step 12, 4.6, 9.1, 9.2 | 6.8 boundary test; clock alert |
| 38 | Verified file exists but Redis lost the reservation | 3.6, 3.8 step 4 | 4.9 Redis loss after storage |
| 39 | Recalled photo announced again | 4.2 | 4.9 recall |

## Additions to the design

This plan fills gaps the design leaves open. Each choice below should be confirmed, or replaced, in the design.

**Wire format and API**
- `AnnounceRequest.session_id = 5`: the session is named explicitly rather than inferred from the user's pointer.
- `Error.missing_chunks = 5`: the design says the `400` body lists missing chunks but has no field for them.
- New messages for auth, sessions, uploads, reports, catch-up, WebSocket frames and devices (0.3).
- Upload-token claims `mime` and `taken_at_ms` (3.2). Header `X-Chunk-CRC32C` (3.5). `CompleteRequest.chunk_count` and `UploadStatus.server_has_file` (3.6, 3.8).
- Endpoints: `POST /v1/sessions/{sid}/end`, `/kick`, `/invites/rotate` (2.7); `POST /v1/sessions/{sid}/photos/{hash}/report` (8.2); `PUT /v1/devices/current` (8.5); `GET /internal/v1/jwks.json` (4.1).
- Error code `404 SESSION_NOT_FOUND` for an unknown or expired code or invite. *Session is full* and *Uploads blocked* are `403 FORBIDDEN` with those messages.
- WebSocket close codes (5.5).

**State and storage**
- One Redis Functions library rather than loose scripts. Functions are still Lua, and they survive restarts and replicate.
- Extra keys: `session:{sid}:live`, `session:{sid}:kicked`, `session:{sid}:online`, `sessions:due`, `push:<uid>`, `expiry_attempts:<n>`, `lock:*`.
- `oauth:{state}` is a JSON string, because `GETDEL` works only on strings.
- Refresh tokens are keyed `refresh:{family}:<hash>` with a family record `rfam:{family}`, so a rotation is atomic in one slot.
- Image server states `VERIFIED` (named in the design's pipeline but missing from its state list), `REPORTING`, `SUPERSEDED` and `ENDED`.
- Promotion to `photos/{sid}/{sha256}/` happens after the orchestrator answers `200`, so a `409` or `410` deletes exactly that upload's directory (3.1).
- Postgres table `photo_reports`.

**Behaviour**
- Kicked users can't rejoin the session.
- Resync is also required when a client's cursor is ahead of the server's last event (5.2, condition 3).
- Both `422` reasons count as strikes, as the error contract says (the limits table names only hash mismatches).
- The client treats `IN_PROGRESS` as waiting, not synced, until the event arrives (7.7).
- Received originals are recorded before they become visible in `MediaStore` (7.9 step 5).

**Proposed numbers not in the design** (all in config):

| Setting | Value |
| --- | --- |
| Refresh-token lifetime | 30 days |
| Join code and invite lifetimes | 2 hours and 12 hours |
| Thumbnail and preview size | 320 px and 1600 px on the longest edge |
| Chunk size | 256 KiB to 8 MiB; announce hint 1 MiB |
| Outbox backoff | 1 s to 60 s |
| Decode limits | 1.5 GB address space, 20 s CPU, 30 s wall clock |
| Rate limits | 10 session creates an hour, 20 OAuth calls a minute per IP, 10 refreshes a minute per family, 50 requests a second per IP at the proxy |
| Expiry shards | 16 |
| Recount interval | 5 minutes |
| WebSocket ping | Every 30 seconds |

## Decided while building

These came up while the plan met real code, Redis 7.2 and the tests. Each one is reflected in the code.

**Redis and the function library**
- **`touch_lease` declares only the lease key.** `FCALL` checks every declared key for write access, and the image server's ACL allows only reads on `session:*`. The function reads the photo entry, which shares the `{sid}` slot, as an undeclared key. Found by verifying `deploy/users.acl.example` on Redis 7.2.
- **ACL files can't contain comments.** The explanation of each user's rights lives in the README.
- **Event ids come from the shared clock** (`XADD ... <ms>-*`) and never go backwards within a session. Trimming by `MINID`, `trimmed_before` and the resync rule then all use the same time as every other timestamp.
- **Auth's refresh rotation is its own script** (`EVALSHA` through `register_script`), not a function in the orchestrator's library. Auth then doesn't depend on the orchestrator having loaded the library, and its ACL needs no `FUNCTION` rights. Rate limits are a `MULTI` of `INCR` and `EXPIRE NX`, not a function.
- **One-session-pointer functions** (`ptr_cas`, `ptr_del`, `ptr_touch`) are single-key functions in the library.

**Image server**
- **`complete` without any chunk is accepted** when the lease exists. The state is created from the token, so the Redis-loss path (`server_has_file`, then straight to `complete`) works. Found by `test_redis_loss_after_storage_reuses_the_file`.
- **Trailing data after a valid image is accepted.** Motion photos from Pixel and Samsung cameras append a video after the JPEG, and rejecting them would refuse ordinary camera photos. The design's checks (allow-list, magic bytes, dimensions, frames, full decode) all still apply. This replaces the plan's JPEG/ZIP polyglot test.
- **Downloads never cache membership**, which is stricter than the design's 2 seconds. The photo's status has to be read on every request anyway, so the cache saved nothing.
- **Decoder limits are set by the worker itself** at startup (`resource.setrlimit`), not through `preexec_fn`, which isn't safe with threads. A fresh process per decode keeps the CPU limit per image.
- **HEIC and HEIF count as one type** when the declared MIME is compared with the detected brand (`mif1` files are commonly sent as `image/heic`).

**API**
- `GET /v1/sessions/{sid}` returns `SessionInfo`. `SessionInfo` gains `member_ids` and `host_id`, and `ReportPhotoRequest` is the body of a photo report.
- **`PhotoEvent.Type.HOST_CHANGED = 5`**, whose `member_id` is the new host, is appended when the host role passes to the longest-present member. Without it, clients kept the roles they had at join time. Found by the Android build.
- **`GET /v1/sessions/current`** returns the caller's live session (`404 SESSION_NOT_FOUND` when there is none). A client refused with `409 ALREADY_IN_SESSION` otherwise has no way to learn which session to leave. Found by the Android build.
- After a session ends, its join code and invite are deleted, so a join by code answers `404 SESSION_NOT_FOUND`. Calls that name the session id still get `410 SESSION_ENDED` for the 1-hour tombstone.
- Kicking someone who already left still records them as kicked (`OK`, nothing else to do).
- Lua errors outside the contract map onto it: `SESSION_FULL`, `UPLOADS_BLOCKED`, `NOT_HOST` and `KICKED` become `403 FORBIDDEN` with a message, and `CONFLICT` becomes `503 BUSY`.

**Clients and operations**
- **Push goes to each platform's own service:** FCM for Android devices and APNs (background push, token auth, through aioapns) for iOS devices, chosen by the device row's platform. The iOS app registers an APNs token, which FCM can't deliver to.
- **The reference client retries transport errors** (a keep-alive connection closed under it, a dropped network) on every call except session creation, because every other call is idempotent. Found by the load test.
- **uvicorn's keep-alive timeout** is 130 s in production, longer than Caddy's 2-minute upstream keep-alive, so the proxy never reuses a connection the service has just closed.
- **Development without Docker:** `photon-dev` (Redis from source, the embedded Postgres, keys, migrations, and Auth and the orchestrator behind one gateway on :8080, as the load balancer provides in production).
- **Load tests come from one IP,** so the per-IP limits must be raised on the stack under test. The 20 logins a minute limit refused the first run, which shows the limit works.
