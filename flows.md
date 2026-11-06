# PHOTON — Request Flows

How every request moves through PHOTON: which component receives it, what it checks, what state it changes, and what the caller gets back. This document describes the code as built. [Design.md](Design.md) explains why the system is shaped this way; where the code differs from the design, [section 11](#11-where-the-code-differs-from-designmd) says so.

Photos are **end-to-end encrypted**. Phones encrypt each photo before uploading it and decrypt it after downloading it; the servers store and relay only encrypted files, public keys and sealed session keys, none of which they can open. [e2ee.md](e2ee.md) specifies the cryptography and byte layouts. This document shows where it enters each flow: the key relay in [sections 4.9](#49-share-the-session-key-key-maintenance) and [4.10](#410-rotate-the-session-key-after-a-leave-or-kick), the encrypted upload in [section 5](#5-photo-upload-announce-chunks-complete-report), and decryption after download in [section 6.5](#65-download-a-photo).

Every step links to the code that does it. Paths are relative to the repository root.

## Contents

1. [System architecture](#1-system-architecture)
2. [What every request goes through](#2-what-every-request-goes-through)
3. [Sign-in and account flows](#3-sign-in-and-account-flows)
4. [Session flows](#4-session-flows)
5. [Photo upload: announce, chunks, complete, report](#5-photo-upload-announce-chunks-complete-report)
6. [Receiving: snapshot, WebSocket, catch-up, push, download](#6-receiving-snapshot-websocket-catch-up-push-download)
7. [Recall and report](#7-recall-and-report)
8. [Background flows: expiry, idle sessions, recount, sweeping, outbox](#8-background-flows-expiry-idle-sessions-recount-sweeping-outbox)
9. [Client-side flows (Android, iOS, CLI)](#9-client-side-flows-android-ios-cli)
10. [Endpoint index](#10-endpoint-index)
11. [Where the code differs from Design.md](#11-where-the-code-differs-from-designmd)

---

## 1. System architecture

### 1.1 Components and request paths

```
              Android app  ·  iOS app  ·  photon-cli
                    │                          │
       HTTPS + WebSocket (protobuf)     HTTP/3, falls back to HTTP/2
                    │                          │
                    ▼                          ▼
   ┌──────────────────────────────────────────────────────────────────┐
   │ Caddy: TLS 1.3 at the edge; /internal/*, /metrics, /readyz → 404 │
   │        public host                          image host           │
   └──────┬────────────────────┬─────────────────────────┬────────────┘
          │ /v1/auth/*         │ everything else         │ everything
          │ /v1/devices/*      │ (including /v1/ws)      │ (chunk bodies
          │ /.well-known/*     │                         │  up to 9 MB)
          ▼                    ▼                         ▼
     ┌─────────┐      ┌─────────────────┐  stored /  ┌──────────────────┐
     │ Auth    │      │ Orchestrator    │  rejected  │ Image server     │
     │ ×2      │      │ ×2              │◄───────────│ ×1               │
     │ :8082   │      │ :8080           │───────────►│ :8081            │
     └─────────┘      └─────────────────┘  DELETE    └──────────────────┘
```

Caddy is the only public entry point ([deploy/Caddyfile](deploy/Caddyfile)). It serves two host names:

- The **public host** sends `/v1/auth/*`, `/v1/devices/*` and `/.well-known/*` to Auth (round robin), and everything else, including WebSocket upgrades on `/v1/ws`, to the orchestrator (least connections, 24-hour stream timeout).
- The **image host** sends everything to the image server, caps chunk bodies at 9 MB, and streams without buffering.
- Both refuse `/internal/*`, `/metrics` and `/readyz` with `404`, so internal routes and operational endpoints are reachable only on the private network.

In development there is no Caddy. `photon-dev` ([src/photon/dev/run.py](src/photon/dev/run.py)) serves Auth and the orchestrator together on `:8080` through a small ASGI router ([src/photon/dev/gateway.py:11](src/photon/dev/gateway.py#L11)) that applies the same prefix split, and the image server on `:8081`, all over plain HTTP.

### 1.2 What each component owns and uses

One writer for each kind of state: the orchestrator writes session and photo state, the image server writes image bytes, and Auth writes accounts and tokens.

| Component | Owns | Uses | Never does |
| --- | --- | --- | --- |
| **Auth** ([src/photon/auth/](src/photon/auth/)) | The access-token signing key; login attempts and refresh tokens in Redis; users, identities and devices in Postgres | Google and GitHub OAuth; Redis (`oauth:*`, `refresh:*`, `rfam:*`, `rl:*` only); Postgres | Sit in the path of ordinary requests. Other services verify its tokens locally. |
| **Orchestrator** ([src/photon/orchestrator/](src/photon/orchestrator/)) | Sessions, members, photo status, the event stream, the expiry index, the upload-token signing key, members' public keys and sealed key bundles (which it can't open) | Redis (all session state, through the Lua function library); Postgres (devices for push, photo reports); FCM and APNs; the image server's internal delete | Touch image bytes, or hold a key that opens a photo |
| **Image server** ([src/photon/imageserver/](src/photon/imageserver/)) | Encrypted photo files, upload state on disk, the report outbox | Its storage volume; Redis read-only for session state, plus refreshing upload leases; the orchestrator's internal report routes | Change session state, notify members, or decrypt or decode a photo |
| **Redis 7.2** (primary and replica) | All live session state, by design short-lived | — | Expose itself publicly. TLS, AOF every second, one ACL user per service ([deploy/users.acl.example](deploy/users.acl.example)). |
| **Postgres 16** | `users`, `identities`, `devices`, `photo_reports` ([src/photon/db.py](src/photon/db.py), [migrations/](migrations/)) | — | Hold session state |

Each service gets only the Redis permissions it needs ([deploy/users.acl.example](deploy/users.acl.example)):

- **orchestrator:** everything except admin commands, `FLUSH*`, `KEYS` and deleting functions. It loads the function library.
- **auth:** reads and writes only `oauth:*`, `refresh:*`, `rfam:*` and `rl:*`.
- **imageserver:** reads `session:*`, reads and writes `upload:*`, and may run only `FCALL`, `FCALL_RO` and a few hash commands.

### 1.3 Who signs what

Every token is an Ed25519 JWT with the algorithm pinned to EdDSA. Each service verifies tokens locally ([src/photon/common/jwtutil.py:121](src/photon/common/jwtutil.py#L121)), so no request waits on another service to check a token. A `kid` header selects the key, which allows rotation through the `.next` and `.prev` key files.

| Key | Signs | Held by | Verified by | Claims | Lifetime |
| --- | --- | --- | --- | --- | --- |
| `auth-access` | Access tokens | Auth | Orchestrator, image server, Auth itself (`PUT /v1/devices/current`) | `iss=photon-auth`, `aud=photon`, `sub=<user id>` | 15 minutes |
| `orch-upload` | Upload tokens, one per `upload_id` | Orchestrator | Image server (key file, or `/internal/v1/jwks.json`) | `iss=photon-orchestrator`, `aud=image-server`, `sid`, `sha256` (of the encrypted file), `size`, `mime`, `taken_at_ms`, `uploader`, `upload_id`, `key_epoch` | 10 minutes |
| `svc-orchestrator` | Calls to the image server (`DELETE`) | Orchestrator | Image server | `iss=orchestrator`, `aud=imageserver-internal` | 60 s, reused until 10 s before expiry |
| `svc-imageserver` | Reports to the orchestrator | Image server | Orchestrator | `iss=imageserver`, `aud=orchestrator-internal` | 60 s, reused until 10 s before expiry |

Refresh tokens are not JWTs. Each is an opaque `<family>.<secret>`, stored only as `sha256(secret)` in Redis, valid for 30 days and rotated on every use ([section 3.5](#35-refresh-a-token)).

The end-to-end keys are separate from all of these and never reach a server unsealed. Each phone has an X25519 identity key pair and sends only the public half. Each session has an epoch key `K_e`, which rotates when a member leaves, and a tag key `D` ([e2ee.md §3](e2ee.md#3-keys)). Members pass them to each other sealed to the recipient's public key ([section 4.9](#49-share-the-session-key-key-maintenance)).

### 1.4 Protocols

| Hop | Protocol | Body |
| --- | --- | --- |
| Client to Auth and orchestrator | HTTPS | `application/x-protobuf` ([proto/photon.proto](proto/photon.proto)); errors are the `Error` message |
| Orchestrator to client, live | WebSocket `/v1/ws` | Binary protobuf frames: `ClientFrame` (`hello`, `ping`), `ServerFrame` (`event`, `error`, `pong`, `ready_cursor`) |
| Client to image server | HTTP/3, or HTTP/2 when UDP is blocked (Caddy advertises `Alt-Svc`) | Raw chunk bytes of the encrypted file with a CRC32C header; protobuf for status and complete; the encrypted file, or a prefix of it, for downloads (`application/octet-stream`) |
| Image server to orchestrator, and back | HTTP on the private network, authenticated by a service JWT | Protobuf (`PhotoStored`, `PhotoRejected`, `ReportAck`) |
| Orchestrator to phones, wake-up only | FCM (Android), APNs background push (iOS) | `{"sid", "kind"}`, never photo data |

### 1.5 Where state lives

Every per-session key carries the session id as a Redis hash tag (`{sid}`), so one session's keys share a slot and each Lua function touches only one slot ([src/photon/orchestrator/keys.py](src/photon/orchestrator/keys.py)).

| Key | Holds | Written by |
| --- | --- | --- |
| `session:{sid}` | host, status (`ACTIVE` or `ENDED`), expiry, `max_members`, `stored_bytes`, `reserved_bytes`, join code, invite token, `last_event_id`, `trimmed_before`, `epoch` (the current key epoch), `rotate` (`1` after a member left) | Orchestrator (Lua) |
| `session:{sid}:members` | user id to `{role, joined_at, strikes, pk, ke}`: `pk` is the member's public key (hex), `ke` the newest key epoch they hold | Orchestrator (Lua) |
| `session:{sid}:photos` | sha256 of the encrypted file to `{status, uploader, upload_id, size, mime, taken_at, reserved_at, stored_at, expires_at, tag, ke}` | Orchestrator (Lua) |
| `session:{sid}:live` | upload id to `{sha, uploader, size, reserved_at}`, for live-upload limits | Orchestrator (Lua) |
| `session:{sid}:events` | The event stream, trimmed by age to retention plus 1 hour | Orchestrator (Lua) |
| `session:{sid}:kicked` | Users who may not rejoin | Orchestrator (Lua) |
| `session:{sid}:tags` | photo tag (hex) to the sha256 of the file that carries it: dedupe of the same photo in different files | Orchestrator (Lua) |
| `session:{sid}:keys` | `<epoch>:<uid>` to `{from, box}`: the sealed key bundles, which only the recipient can open | Orchestrator (Lua) |
| `session:{sid}:online` | user id to the last WebSocket activity, used to decide who needs a push | Orchestrator |
| `upload:{sid}:<upload_id>` | The upload lease (15 minutes, refreshed by every chunk) | Created by the orchestrator; refreshed by the image server |
| `user:{uid}:session` | The user's one current session | Orchestrator |
| `code:<code>`, `invite:<token>` | Session id for joining (2 hours and 12 hours) | Orchestrator |
| `sessions:due` | Sessions scored by idle expiry, for the idle sweeper | Orchestrator |
| `expiry:<n>` (16 shards) | `sid:sha256` scored by delete time; an entry stays until the image server acknowledges the delete | Orchestrator |
| `oauth:<state>`, `oauth:nonce:<nonce>` | Login attempts and native sign-in nonces (10 minutes, single use) | Auth |
| `refresh:{family}:<hash>`, `rfam:{family}` | Refresh tokens and their family | Auth |
| `rl:<scope>:<id>` | Rate-limit windows | Auth, orchestrator |
| `push:<uid>` | Push coalescing window (30 s) | Orchestrator |
| `lock:<name>` | Background job locks | Orchestrator |

**Every state change is one Lua function** in the Redis Functions library [src/photon/orchestrator/lua/photon.lua](src/photon/orchestrator/lua/photon.lua) (version `4`). Each function checks, in the same atomic step, that the session is `ACTIVE`, that the caller is a member and, for uploads, that the named `upload_id` is still the live one for the hash. The orchestrator loads the library at startup if Redis has another version ([src/photon/common/redisutil.py:50](src/photon/common/redisutil.py#L50)), and `/readyz` reports not ready until the versions match.

The image server keeps its own durable state on one filesystem ([src/photon/imageserver/store.py](src/photon/imageserver/store.py)):

```
tmp/{upload_id}/state.json      the upload's state, written before every response
tmp/{upload_id}/chunks/{n}      received chunks
tmp/{upload_id}/assembled       being hashed
tmp/{upload_id}/staged/         the verified file and its meta.json, kept under the upload id until the orchestrator accepts them
photos/{sid}/{sha256}/          promoted: file (end-to-end encrypted, e2ee.md §6) and meta.json
outbox/{upload_id}.json         pending stored/rejected reports
trash/                          renamed here, deleted in the background
```

### 1.6 Scaling

Auth and the orchestrator are stateless and run as two replicas each behind Caddy with no sticky routing. A WebSocket can land on any orchestrator because it reads events from the shared Redis stream. The image server runs as one instance with a local volume; a second would need routing by `upload_id` or shared object storage.

---

## 2. What every request goes through

Before any handler runs, every service applies the same layer ([src/photon/common/httpapp.py:23](src/photon/common/httpapp.py#L23)):

1. **Request id.** `RequestIdMiddleware` tags the request, and every log line carries the id ([src/photon/common/logs.py](src/photon/common/logs.py)).
2. **Body parsing.** Protobuf bodies must have `Content-Type: application/x-protobuf` and are capped at 64 KB by default (16 KB for Google sign-in). A bad body is `400 INVALID_REQUEST`; an oversized one is `413 TOO_LARGE` ([src/photon/common/proto.py:16](src/photon/common/proto.py#L16)).
3. **Authentication.** Client routes run `user_dependency` ([src/photon/common/httpapp.py:80](src/photon/common/httpapp.py#L80)): `Authorization: Bearer <access JWT>`, verified locally. An expired token is `401 TOKEN_EXPIRED` (retryable after a refresh); anything else is `401 UNAUTHENTICATED`. Internal routes run `service_dependency` ([src/photon/common/httpapp.py:94](src/photon/common/httpapp.py#L94)), which accepts only the named peer service's token. Upload routes check the upload token instead ([section 5.2](#52-upload-chunks)).
4. **Client IP.** For per-IP rate limits, `X-Forwarded-For` is trusted only when the direct peer is a configured proxy ([src/photon/common/httpapp.py:68](src/photon/common/httpapp.py#L68)); in the Compose deployment that is Caddy's fixed address.
5. **Rate limits.** Fixed windows in Redis ([src/photon/common/ratelimit.py:10](src/photon/common/ratelimit.py#L10)). Going over is `429 RATE_LIMITED` with `Retry-After` set to the rest of the window.
6. **Errors.** Every failure becomes the `Error` protobuf with a code from one table ([src/photon/common/errors.py:8](src/photon/common/errors.py#L8)) that fixes its HTTP status, whether it is retryable, and the default `Retry-After`. Lua functions return their own codes, which are mapped onto the contract (`SESSION_FULL`, `UPLOADS_BLOCKED`, `NOT_HOST` and `KICKED` become `403 FORBIDDEN`; `CONFLICT` becomes `503 BUSY`; `KEYS_INCOMPLETE` becomes `400 INVALID_REQUEST`). The key codes pass through as they are: `409 KEY_ROTATION_REQUIRED`, `409 KEY_EPOCH_STALE`, `409 EPOCH_TAKEN` and `403 KEY_REQUIRED`, none retryable as such ([sections 4.9](#49-share-the-session-key-key-maintenance) and [4.10](#410-rotate-the-session-key-after-a-leave-or-kick) say what the client does). An unexpected exception is logged and answered `503 BUSY`.

| Rate limit | Key | Limit |
| --- | --- | --- |
| OAuth start, callback, nonce, Google sign-in, dev login | `rl:oauth:<ip>` | 20 a minute |
| Refresh | `rl:refresh:<family>` | 10 a minute |
| Create a session | `rl:create:<uid>` | 10 an hour |
| Join | `rl:join:u:<uid>` and `rl:join:ip:<ip>` | 5 a minute each |
| Announce | `rl:announce:<uid>` | 20 a minute |
| Share or rotate keys (`POST …/keys`) | `rl:keys:<uid>` | 60 a minute |
| Report a photo | `rl:report:<uid>` | 10 a minute |

**Operational endpoints** on every service:

- `GET /healthz` answers `ok` while the process is alive.
- `GET /metrics` serves Prometheus metrics.
- `GET /readyz` checks Redis, plus Postgres for Auth, the function library version for the orchestrator, and the storage root for the image server.

Caddy blocks `/metrics` and `/readyz` from outside; Prometheus scrapes them on the private network ([deploy/prometheus.yml](deploy/prometheus.yml)).

---

## 3. Sign-in and account flows

All of these are served by Auth ([src/photon/auth/app.py](src/photon/auth/app.py)). Every successful sign-in ends the same way: `find_or_create_user` ([src/photon/auth/app.py:53](src/photon/auth/app.py#L53)) looks up the user by `(provider, provider_user_id)`, never by email, and creates one if needed (two racing first logins end with one user). Then `Tokens.pair` returns a `TokenPair`: a 15-minute access JWT, a new refresh-token family, the access expiry and the user id.

### 3.1 Sign-in options

`GET /v1/auth/config` → `AuthConfig` ([src/photon/auth/app.py:172](src/photon/auth/app.py#L172)). It reports the Google client id (empty if Google is off), whether dev login is available, and whether GitHub is available. The app shows only the buttons the server supports. No authentication is needed.

### 3.2 Browser OAuth with PKCE (Google in the browser, GitHub, dev)

```
App                         Auth                          Provider
 │ POST /v1/auth/oauth/start  │                              │
 │ {provider, redirect_uri,   │                              │
 │  code_challenge}           │                              │
 │──────────────────────────►│ store oauth:<state> (10 min)  │
 │◄──────────────────────────│ {authorization_url, state}    │
 │ open system browser ──────────────────────────────────────►│ user signs in
 │◄──────────── redirect to app link with code and state ─────│
 │ POST /v1/auth/oauth/callback {code, state, code_verifier, redirect_uri}
 │──────────────────────────►│ GETDEL oauth:<state>, check PKCE and redirect URI
 │                           │ exchange code ───────────────►│
 │                           │◄──────── tokens / profile ────│
 │◄──────────────────────────│ TokenPair                     │
```

1. **Start** ([src/photon/auth/app.py:111](src/photon/auth/app.py#L111)): rate limit per IP; the provider must be configured; `redirect_uri` must exactly equal a registered one (no prefixes or wildcards); `code_challenge` must look like an S256 challenge (43 base64url characters). Auth creates a random `state` and `nonce` and stores `oauth:<state>` with the provider, challenge, redirect URI and nonce for 10 minutes. It answers with the provider URL and the `state`.
2. **Browser.** The app opens the URL in the system browser (Custom Tabs or `ASWebAuthenticationSession`), never a web view. The provider returns the browser to the app's redirect URI with `code` and `state`.
   - **GitHub** has one fixed callback URL, so it returns the browser to `GET /v1/auth/oauth/return` ([src/photon/auth/app.py:182](src/photon/auth/app.py#L182)). That page reads (does not consume) `oauth:<state>` and forwards `code` and `state` to the app's registered redirect URI with an instant redirect.
   - **Dev** ([src/photon/auth/app.py:231](src/photon/auth/app.py#L231)), outside production only: `GET /v1/auth/dev/authorize` shows a username form, then redirects back with a short-lived code signed by Auth (`aud=dev-code`, carrying the nonce).
3. **Callback** ([src/photon/auth/app.py:131](src/photon/auth/app.py#L131)): rate limit; `GETDEL oauth:<state>`, so a `state` works exactly once (a missing, expired or used state is `401`); the redirect URI must match the stored one; `S256(code_verifier)` must equal the stored challenge (constant-time comparison). Auth then exchanges the code with the provider ([src/photon/auth/providers.py](src/photon/auth/providers.py)):
   - **Google:** verifies the ID token (RS256 against Google's JWKS, issuer, audience, expiry) and that its nonce matches.
   - **GitHub:** reads the profile from the GitHub API and keeps only the numeric user id.
   - **Dev:** verifies Auth's own code and its nonce.
   A provider outage is `503 BUSY`; a refused code is `401`.
4. Find or create the user, then return a `TokenPair`.

### 3.3 Native Google sign-in (Android Credential Manager, iOS)

1. `POST /v1/auth/nonce` ([src/photon/auth/app.py:196](src/photon/auth/app.py#L196)): rate limit; stores `oauth:nonce:<nonce>` for 10 minutes; returns the nonce and its expiry.
2. The app asks the platform account picker for a Google ID token bound to that nonce.
3. `POST /v1/auth/google {id_token, nonce}` ([src/photon/auth/app.py:204](src/photon/auth/app.py#L204)): Google must be configured; `GETDEL oauth:nonce:<nonce>`, so a captured token can't be replayed; the ID token is verified (RS256, issuer, audience equal to the server's client id, expiry, nonce). Then find or create the user and return a `TokenPair`.

### 3.4 Dev login (development only)

`POST /v1/auth/dev/login {username}` ([src/photon/auth/app.py:220](src/photon/auth/app.py#L220)) signs in with a bare username, with no browser. The route is registered only when the dev provider exists, which never happens with `PHOTON_ENV=prod`; Auth refuses to start in production with the dev provider enabled ([src/photon/auth/app.py:92](src/photon/auth/app.py#L92)).

### 3.5 Refresh a token

`POST /v1/auth/refresh {refresh_token}` ([src/photon/auth/app.py:152](src/photon/auth/app.py#L152)):

1. Parse `<family>.<secret>` and rate-limit per family (10 a minute).
2. Run one atomic Lua script ([src/photon/auth/tokens.py:23](src/photon/auth/tokens.py#L23)) over the old token, the family and the new token:
   - The family is revoked → `401`.
   - The token is unknown or expired → `401`.
   - First use: mark it rotated now and issue a new token in the same family.
   - Already rotated **within the last 30 seconds** (a concurrent refresh): issue another new token in the same family.
   - Already rotated **more than 30 seconds ago** (a replay): **revoke the whole family** and count `refresh_reuse_total` → `401`.
3. Return a new `TokenPair` with a fresh access token and the new refresh token.

Clients refresh one at a time (single flight) when a call returns `401 TOKEN_EXPIRED` or the WebSocket closes with `4001`.

### 3.6 Register a device for push

`PUT /v1/devices/current {platform, push_token}` ([src/photon/auth/app.py:159](src/photon/auth/app.py#L159)) with an access token. `platform` must be `android` or `ios`, and the token 10 to 4,096 characters. It upserts into `devices`, keyed by `push_token` (a token that moves to another account moves with it), and answers `204`. The orchestrator reads this table when it sends wake-up pushes ([section 6.4](#64-push-wake-ups)).

### 3.7 Public key and app-link documents

- `GET /.well-known/jwks.json`: Auth's access-token public keys, cacheable for 5 minutes. A verifier uses it when `PHOTON_AUTH_JWKS_URL` is set; otherwise it reads the key files.
- `GET /.well-known/assetlinks.json` and `GET /.well-known/apple-app-site-association`: the Android App Links and iOS Universal Links declarations. They cover the OAuth return path `/auth/cb` and invite links `/j/*`.
- `GET /internal/v1/jwks.json` on the orchestrator: the upload-token public key, for an image server configured with `PHOTON_UPLOAD_JWKS_URL`. It is reachable only on the private network.

---

## 4. Session flows

All served by the orchestrator ([src/photon/orchestrator/routes/sessions.py](src/photon/orchestrator/routes/sessions.py)), all with an access token. Each state change is one Lua function call with the session's eight keys ([src/photon/orchestrator/keys.py:10](src/photon/orchestrator/keys.py#L10)).

**One session per user.** `user:{uid}:session` points at the user's current session. It lives in another Redis slot, so it can't be part of the session's Lua call. Create and join therefore claim it first with `SET NX` and remove it again if the session call fails ([src/photon/orchestrator/svc.py:84](src/photon/orchestrator/svc.py#L84)). If the pointer names a session where the user is still a live member, the answer is `409 ALREADY_IN_SESSION`. A pointer to an ended session is overwritten with a compare-and-set.

**Session info.** Several routes answer with `SessionInfo`, built by the read-only `session_info` function: session id, join code, invite URL (`<public base>/j/<token>`, which is also the QR payload), expiry, the caller's role, member ids and host id. It needs the session to be `ACTIVE` (`410 SESSION_ENDED`, or `404 SESSION_NOT_FOUND` once the 1-hour tombstone is gone) and the caller to be a member (`403 NOT_A_MEMBER`).

### 4.1 Create a session

`POST /v1/sessions {max_members, member_key}` ([routes/sessions.py:71](src/photon/orchestrator/routes/sessions.py#L71)). Before sending it, the phone makes the session's first epoch key `K_1` and its tag key `D`, 32 random bytes each. They stay on the phone; others get them sealed ([section 4.9](#49-share-the-session-key-key-maintenance)).

1. `member_key`, the phone's X25519 public key, must be 32 bytes (`400 INVALID_REQUEST`) ([routes/sessions.py:24](src/photon/orchestrator/routes/sessions.py#L24)). `max_members` defaults to 10 and must be 2 to 10. Rate limit: 10 creates an hour.
2. New session id (22 URL-safe characters); claim the user's session pointer.
3. Allocate a join code (8 Crockford base32 characters, `SET NX`, 2 hours, up to five tries) and an invite token (128 bits, 12 hours) ([routes/sessions.py:31](src/photon/orchestrator/routes/sessions.py#L31)).
4. `create_session` ([photon.lua:176](src/photon/orchestrator/lua/photon.lua#L176)) writes the session hash with `epoch = 1` and `rotate = 0`, adds the caller as host with their `pk` and `ke = 1` (the creator holds epoch 1 from the start), appends `MEMBER_JOINED` with the member key, and sets the idle expiry (12 hours from now) on all eight keys.
5. Add the session to `sessions:due` for the idle sweeper. If anything fails, the code, invite and pointer are removed again.
6. Answer `SessionInfo`.

### 4.2 Join a session

`POST /v1/sessions/join {code | invite_token, member_key}` ([routes/sessions.py:95](src/photon/orchestrator/routes/sessions.py#L95)):

1. `member_key` must be 32 bytes (`400`). Rate limits: 5 a minute per user and 5 a minute per IP (join codes are guessable, so both are needed).
2. Resolve `code:<normalized code>` or `invite:<token>` to a session id. Normalizing accepts lower case, dashes, spaces, and I, L, O typed for 1, 1, 0. Unknown → `404 SESSION_NOT_FOUND`.
3. Claim the session pointer.
4. `join` ([photon.lua:189](src/photon/orchestrator/lua/photon.lua#L189)):
   - The session must be `ACTIVE` (`410`).
   - Already a member with the same member key → success, nothing changes (`ALREADY_MEMBER`).
   - Already a member with another key (the same user from a new install) → store the new `pk`, reset `ke` to 0, drop the member's old sealed boxes, and append `MEMBER_JOINED` again (`REKEYED`), so members seal the key afresh.
   - Kicked from this session → `403`.
   - Full → `403`.
   - Otherwise add the member with `ke = 0`, append `MEMBER_JOINED` with their member key, and push the idle expiry forward.
5. If the join fails, release the pointer.
6. Unless the caller was already a member, send `joined` pushes to the other members ([section 6.4](#64-push-wake-ups)): one of them must seal the session key for the newcomer.
7. Answer `SessionInfo`.

After joining, the client opens the WebSocket, loads the snapshot ([section 6](#6-receiving-snapshot-websocket-catch-up-push-download)) and runs key maintenance ([section 4.9](#49-share-the-session-key-key-maintenance)). It holds no key yet, so it can't upload or decrypt until a member has sealed one for it.

### 4.3 Read session info

- `GET /v1/sessions/current` ([routes/sessions.py:126](src/photon/orchestrator/routes/sessions.py#L126)): the caller's live session, from the pointer. A client refused with `ALREADY_IN_SESSION` uses it to show or leave that session. No live session → `404 SESSION_NOT_FOUND`.
- `GET /v1/sessions/{sid}` ([routes/sessions.py:135](src/photon/orchestrator/routes/sessions.py#L135)): `SessionInfo` for a session the caller belongs to.

### 4.4 Leave

`POST /v1/sessions/leave {session_id}` ([routes/sessions.py:139](src/photon/orchestrator/routes/sessions.py#L139)) → `leave` ([photon.lua:223](src/photon/orchestrator/lua/photon.lua#L223)):

1. Not active or not a member → nothing happens.
2. Remove the member ([photon.lua:97](src/photon/orchestrator/lua/photon.lua#L97)): release every live reservation they hold (and its reserved bytes), drop their sealed key boxes, then append `MEMBER_LEFT`.
3. If nobody is left, **end the session** (see 4.6).
4. Otherwise set `rotate = 1`. The leaver holds the current key, so the next upload must start a new epoch first ([section 4.10](#410-rotate-the-session-key-after-a-leave-or-kick)).
5. If the leaver was host, the longest-present member becomes host and `HOST_CHANGED` is appended.
6. Release the pointer. If the session ended, run the end clean-up ([section 4.6](#46-end-a-session)). Answer `204`.

### 4.5 Kick a member

`POST /v1/sessions/{sid}/kick {member_id}` ([routes/sessions.py:157](src/photon/orchestrator/routes/sessions.py#L157)) → `kick` ([photon.lua:239](src/photon/orchestrator/lua/photon.lua#L239)). Host only (`403`), and the host can't kick themselves. The target is added to `session:{sid}:kicked` (so they can never rejoin), removed like a leave (reservations released, sealed boxes dropped, `MEMBER_LEFT` appended), `rotate` is set to `1`, and their pointer is released. Answer `204`.

What the kicked member experiences:

- Their WebSocket sees `MEMBER_LEFT` with their own id and closes with `4403`.
- Their in-flight chunks fail the image server's membership check within 5 seconds (the cache limit), and `complete` fails at once (uncached).
- Downloads answer `404`.
- They may still hold the old epoch keys, but they can't download anything to use them on, and the next upload rotates to an epoch nobody seals for them.

### 4.6 End a session

`POST /v1/sessions/{sid}/end` ([routes/sessions.py:149](src/photon/orchestrator/routes/sessions.py#L149)) → `end_session` in `host` mode ([photon.lua:254](src/photon/orchestrator/lua/photon.lua#L254)). Host only. The same function in `idle` mode is used by the idle sweeper ([section 8.2](#82-idle-session-sweeper-every-30-s)).

`do_end` ([photon.lua:127](src/photon/orchestrator/lua/photon.lua#L127)) runs as one atomic step:

1. Set the status to `ENDED` and zero both byte counters.
2. Drop every `UPLOADING` reservation and its lease. Mark every `STORED` photo `EXPIRED`. Collect every stored, expired and recalled hash for deletion.
3. Clear the live-upload table and append `SESSION_ENDED`.
4. Shorten every session key's lifetime to 1 hour, the sealed key boxes included. The tombstone lets late callers get `410` rather than `404`.

Then `after_end` ([src/photon/orchestrator/svc.py:157](src/photon/orchestrator/svc.py#L157)) runs outside the session's slot:

1. Delete the join code and invite.
2. Remove the session from `sessions:due` and every member's pointer.
3. Queue all files for deletion at once in the expiry index.
4. Send `ended` pushes.
5. Try the deletes immediately.

The expiry sweeper retries any delete that fails. Because every later join, announce, report and chunk checks `ACTIVE`, anything racing the end fails with `410`.

### 4.7 Rotate the invite

`POST /v1/sessions/{sid}/invites/rotate` ([routes/sessions.py:169](src/photon/orchestrator/routes/sessions.py#L169)). Host only. It allocates a new code and token, swaps them into the session with `set_invites` ([photon.lua:276](src/photon/orchestrator/lua/photon.lua#L276)), deletes the old ones (old links stop working at once), and answers `SessionInfo`.

### 4.8 Keeping a session alive

A session ends after 12 hours without activity. Two things count as activity: an announce, and a WebSocket ping. Each schedules `touch` ([src/photon/orchestrator/svc.py:107](src/photon/orchestrator/svc.py#L107)), throttled to once a minute per session per process. `touch` moves `expires_at` forward, raises the score in `sessions:due`, and extends every member's pointer.

### 4.9 Share the session key (key maintenance)

A newcomer joins holding no key. Any member whose app holds the current epoch seals it to the newcomer's public key, and the orchestrator relays the sealed box. This is the WhatsApp pattern: the server carries keys it can't open. Here Alice holds epoch 1 and Bob has just joined:

```
Bob (newcomer)                  Orchestrator                         Alice (holds K_1 and D)
 │ POST /v1/sessions/join          │                                     │
 │ {code, member_key} ────────────►│ join: Bob's pk stored, ke = 0       │
 │                                 │── MEMBER_JOINED {bob, member_key} ─►│ WebSocket, or push kind=joined
 │                                 │◄──────────── GET …/keys ────────────│
 │                                 │──── members: bob at key epoch 0 ───►│ seal (1, K_1, D) to Bob's key
 │                                 │◄─ POST …/keys {epoch 1, [bob: box]} │
 │                                 │ put_keys: store box, bob ke = 1     │
 │◄──── KEY_SHARED {bob, 1} ───────│                                     │
 │ GET …/keys ────────────────────►│                                     │
 │◄── sealed [{epoch 1, from alice, box}]                                │
 │ open the box with Bob's private key → K_1 and D                       │
```

**Read the key state.** `GET /v1/sessions/{sid}/keys` ([routes/keys.py:24](src/photon/orchestrator/routes/keys.py#L24)) → `key_state` ([photon.lua:561](src/photon/orchestrator/lua/photon.lua#L561)), read-only. It needs an active session and a member. It answers `KeyState`: the current `epoch`, `rotation_required`, every member's `member_key` and `key_epoch` (the newest epoch they hold), and the caller's own sealed boxes, each with its epoch and sender. Nobody can read another member's boxes.

**Share.** `POST /v1/sessions/{sid}/keys {epoch, rotate: false, keys[]}` ([routes/keys.py:40](src/photon/orchestrator/routes/keys.py#L40)) → `put_keys` ([photon.lua:586](src/photon/orchestrator/lua/photon.lua#L586)):

1. The route checks the shape: `epoch ≥ 1`; 1 to 64 boxes; distinct recipients; every box exactly 117 bytes ([e2ee.md §4](e2ee.md#4-sealing-a-bundle-for-one-member)); every box for the request's epoch (`400 INVALID_REQUEST` otherwise). Rate limit: 60 a minute per user.
2. The function needs an active session and a member, then:
   - `1 ≤ epoch ≤ current`, or `409 KEY_EPOCH_STALE`;
   - the caller holds that epoch (`ke ≥ epoch`), or `403 KEY_REQUIRED`.
3. For each box whose recipient is a current member other than the caller, and who has no box for that epoch yet: store `<epoch>:<uid>` → `{from, box}`, raise the recipient's `ke` to the epoch, and append `KEY_SHARED` (member = the recipient, `key_epoch`). A box for a member who already has one is ignored. The first sealer wins, so several members sealing for the same newcomer at once is harmless.
4. Answer `KeyUploadResult {epoch: current, stored}`.

**What every client does** ([e2ee.md §5.3](e2ee.md#53-what-every-client-does-key-maintenance)). The client runs key maintenance after creating or joining, on `MEMBER_JOINED`, on `KEY_SHARED` for itself, on `KEY_ROTATED`, on a `joined` push, and on every start or catch-up:

1. `GET …/keys`, then open every box addressed to it and keep each bundle by epoch. A box that doesn't open, because it was sealed for another install's key or tampered with, is ignored.
2. If it holds the current epoch, then for each epoch it holds, oldest first, it seals that epoch for every other member whose `key_epoch` is below it, in one `POST` per epoch. A newcomer therefore also gets earlier epochs and can read photos from before the last rotation that are still live.

In the Python client this is `PhotonClient.sync_keys` ([src/photon/client/api.py](src/photon/client/api.py)), and the listener runs it on those events ([src/photon/client/listener.py](src/photon/client/listener.py)). Until some member's app has done step 2 for a newcomer, the newcomer's announces answer `403 KEY_REQUIRED`, and its app shows that it is waiting for the key.

### 4.10 Rotate the session key after a leave or kick

A member who leaves or is kicked keeps the keys they held. So a leave or kick sets `rotate = 1` (sections [4.4](#44-leave) and [4.5](#45-kick-a-member)), and the orchestrator refuses announces until a member starts a new epoch whose key the departed member never gets. The next uploader does it:

```
Alice (uploader)                  Orchestrator                         Bob
 │ announce {key_epoch 1} ────────►│ rotate = 1 → 409 KEY_ROTATION_REQUIRED
 │ GET …/keys ────────────────────►│ members, epoch 1, rotation_required
 │ K_2 = 32 random bytes, keeping D; seal (2, K_2, D) for every member, Alice included
 │ POST …/keys {epoch 2, rotate: true, keys[]} ►│ put_keys: epoch = 2, rotate = 0,
 │                                 │ every member's ke = 2               │
 │                                 │──────── KEY_ROTATED {alice, 2} ────►│ GET …/keys, open the box → K_2
 │ encrypt again with epoch 2; announce {key_epoch 2}                    │
```

`put_keys` with `rotate: true` ([photon.lua:586](src/photon/orchestrator/lua/photon.lua#L586)):

1. `epoch` must be exactly `current + 1`, or `409 EPOCH_TAKEN`.
2. The caller must hold some epoch (`ke ≥ 1`), so that it knows `D`, or `403 KEY_REQUIRED`.
3. There must be a box for **every other member**, or `400 INVALID_REQUEST` (`KEYS_INCOMPLETE`). A box for the caller itself is stored too; the Python client sends one, so it can recover its own new key after a crash. The last member left may rotate with no boxes at all.
4. Store the boxes, set every member's `ke` to the new epoch, make it current, clear `rotate`, and append `KEY_ROTATED` (member = the rotator, `key_epoch` = the new epoch). Answer `{epoch, stored}`.

**Two members rotating at once.** Both read epoch 1 and both send epoch 2. The function runs one at a time, so the first wins and the second gets `409 EPOCH_TAKEN`. The loser discards its own new key, runs key maintenance to open the winner's box, and continues with that key. This is `PhotonClient.rotate_keys` in the Python client.

**Uploads during and after the rotation.** An announce for the old epoch after the rotation answers `409 KEY_EPOCH_STALE`. The client runs key maintenance, encrypts the photo again with the current epoch, and announces again ([section 5.1](#51-announce)). The tag key `D` stays the same across epochs, so dedupe still works ([e2ee.md §5.4](e2ee.md#54-rotation-after-a-member-leaves)).

---

## 5. Photo upload: announce, chunks, complete, report

This is the core flow. Its one rule: **a photo is announced to the session only after the image server has verified its SHA-256 and saved it.** The client's hash is a claim, not a fact.

What is uploaded is the **encrypted file**, never the photo ([e2ee.md §7](e2ee.md#7-upload-step-by-step-client)). Before announcing, the phone:

1. Makes sure it holds the current epoch (key maintenance, [section 4.9](#49-share-the-session-key-key-maintenance)).
2. Computes the **photo tag** `HMAC-SHA256(D, "photon/tag/v1" ‖ SHA-256(original))`. It is the same for the same photo whoever uploads it, and meaningless to the server, which has no `D`.
3. Makes a WebP thumbnail (longest side 320 px) and, when the original is larger than 1,600 px, a WebP preview, with EXIF orientation applied and no metadata ([src/photon/client/images.py](src/photon/client/images.py) in the Python client).
4. Builds the `PHE1` file: an 85-byte plaintext header (epoch, section lengths, and a fresh random photo key locked with `K_epoch`), then the metadata, thumbnail, preview and original, each encrypted with AES-256-GCM under the photo key ([e2ee.md §6.1](e2ee.md#61-layout)).
5. Hashes the whole file with SHA-256. That hash is the photo's id everywhere: announce, upload token, events, downloads, recall.

The Python client does all of this in `upload_bytes` ([src/photon/client/uploader.py](src/photon/client/uploader.py)).

```
Uploader            Orchestrator              Redis (Lua)           Image server              Other members
   │ POST /v1/photos/announce │                    │                      │                          │
   │─────────────────────────►│ announce ─────────►│ key epoch, tag,      │                          │
   │                          │◄───────────────────│ reserve, quota, lease│                          │
   │◄─ UPLOAD + upload token ─│                    │                      │                          │
   │ PUT /v1/uploads/{id}/chunks/{n}  (2–4 in parallel, CRC32C each)      │                          │
   │──────────────────────────────────────────────────────────────────────►│ upload_check (≤5 s cache)│
   │                          │                    │◄─── touch_lease ─────│ write chunk, fsync       │
   │ POST /v1/uploads/{id}/complete                │                      │                          │
   │──────────────────────────────────────────────────────────────────────►│ assemble + SHA-256,      │
   │                          │                    │                      │ check the PHE1 header,   │
   │                          │                    │                      │ meta,                    │
   │                          │◄──── POST /internal/v1/photos/stored ─────│ outbox record            │
   │                          │ report_stored ────►│ fence on upload_id,  │                          │
   │                          │                    │ STORED + event       │                          │
   │                          │──── 200 APPLIED ─────────────────────────►│ promote to photos/       │
   │◄──────────────────────────────── 200 CompleteResponse ───────────────│                          │
   │                          │ XREAD on WebSocket ─────────────────────────────────────────────────►│ PHOTO_STORED
   │                          │ push hint to members without a socket ──────────────────────────────►│
```

### 5.1 Announce

`POST /v1/photos/announce {session_id, sha256, size, mime, taken_at_ms, photo_tag, key_epoch}` ([src/photon/orchestrator/routes/photos.py:27](src/photon/orchestrator/routes/photos.py#L27)):

1. **Validate the request.** The hash must be 32 bytes; `size` must be positive and at most 25 MB (`413 TOO_LARGE`); `mime` must be `application/vnd.photon.e2ee`, the only type the server accepts, because the photo's real type is inside the encrypted file (`415 UNSUPPORTED_FORMAT`); `photo_tag` must be 32 bytes and `key_epoch` at least 1 (`400 INVALID_REQUEST`); `taken_at_ms` must be positive and not more than a day in the future. Rate limit: 20 a minute per user.
2. **Run `announce`** ([photon.lua:290](src/photon/orchestrator/lua/photon.lua#L290)) with a fresh `upload_id` (32 hex characters) and the limits:
   1. The session must be `ACTIVE` (`410 SESSION_ENDED`). The caller must be a member (`403`). The caller must have fewer than 3 hash-mismatch strikes (`403`, uploads blocked).
   2. **The key epoch.** A rotation is pending → `409 KEY_ROTATION_REQUIRED` ([section 4.10](#410-rotate-the-session-key-after-a-leave-or-kick)). `key_epoch` isn't the current epoch → `409 KEY_EPOCH_STALE`: the client fetches keys, encrypts the photo again and announces again. The caller doesn't hold that epoch → `403 KEY_REQUIRED`: it waits for `KEY_SHARED`.
   3. **The hash is already known in this session:**
      - `STORED` or `EXPIRED` → **`ALREADY_UPLOADED`**: the client records it and stops.
      - `RECALLED` → `410 PHOTO_RECALLED`.
      - `UPLOADING` with a live lease, and it's the caller's own → **`UPLOAD` with the same `upload_id`** and a fresh token (announce is idempotent).
      - `UPLOADING` with a live lease, someone else's → **`IN_PROGRESS`**: the client waits for the `PHOTO_STORED` event.
      - `UPLOADING` with the lease gone, reserved less than 60 seconds ago → `IN_PROGRESS`.
      - `UPLOADING` with the lease gone, older than that → the reservation is stale, so it is released and **taken over** under the new `upload_id`.
   4. **The same photo in another file.** Two members encrypting the same photo produce different files (different photo keys), so their hashes differ, but their tags are equal. `session:{sid}:tags` maps each tag to the hash that carries it. If the tag belongs to another hash:
      - that photo is `STORED` or `EXPIRED` → **`ALREADY_UPLOADED`**;
      - it is `RECALLED` → `410 PHOTO_RECALLED`;
      - it is the caller's own unfinished upload (a file it rebuilt after losing the first) → that reservation is released and this file replaces it;
      - it is someone else's upload with a live lease, or reserved less than 60 seconds ago → `IN_PROGRESS`;
      - otherwise it is stale and **taken over**.
   5. **Live-upload limits.** Stale reservations found along the way are released. Then at most 3 live uploads per user and 20 per session (`429 TOO_MANY_UPLOADS`, retry in 10 s).
   6. **Quota.** `stored_bytes + reserved_bytes + size` must stay within 1 GB (`429 QUOTA_EXCEEDED`, retry in 60 s; quota frees as photos expire).
   7. **Reserve.** `HSETNX` the hash as `UPLOADING` with the new `upload_id`, the tag and the key epoch. If a concurrent announce won, answer `IN_PROGRESS`. Point the tag at this hash, add the upload to the live table, add `size` to `reserved_bytes`, and create the lease `upload:{sid}:<upload_id>` (state `RECEIVING`, 15 minutes).
3. Count the session as active (`touch`).
4. For `UPLOAD`, mint the **upload token** ([src/photon/orchestrator/upload_tokens.py:8](src/photon/orchestrator/upload_tokens.py#L8)), valid for 10 minutes and carrying the `key_epoch`, and answer with `upload_id`, `upload_url` (`<image base>/v1/uploads/<upload_id>`), `upload_token` and `chunk_size_hint` (1 MB). `ALREADY_UPLOADED` and `IN_PROGRESS` carry no token.

Because the check and the reservation are one function, two members announcing the same photo at the same moment, by hash or by tag, get exactly one `UPLOAD` and one `IN_PROGRESS`, and concurrent announces can never jointly exceed the quota.

### 5.2 Upload chunks

`PUT /v1/uploads/{upload_id}/chunks/{n}` on the image server, with `Authorization: Bearer <upload token>`, `Content-Length` and `X-Chunk-CRC32C` ([src/photon/imageserver/routes.py:77](src/photon/imageserver/routes.py#L77), [src/photon/imageserver/pipeline.py:66](src/photon/imageserver/pipeline.py#L66)):

1. **Token.** Verify the upload token (`aud=image-server`, `iss=photon-orchestrator`, not expired); its `upload_id` must match the path (`404 UNKNOWN_UPLOAD`) ([src/photon/imageserver/access.py:32](src/photon/imageserver/access.py#L32)).
2. **Request checks.** `n` must be below 4,096. `Content-Length` is required and at most 8 MB (Caddy also caps bodies at 9 MB). The CRC32C header must be 4 bytes, base64-encoded.
3. **Upload state.** Under the upload's file lock, create `tmp/<upload_id>/state.json` (`RECEIVING`) on the first chunk. If the upload is already past `RECEIVING`, answer `409 UPLOAD_FINALIZED`.
4. **Session checks** ([access.py:76](src/photon/imageserver/access.py#L76)): the session must be `ACTIVE` and the uploader a member, read through `upload_check` and cached for at most 5 seconds. Then `touch_lease` refreshes the 15-minute lease. That is the only Redis key the image server may write, and the reason its ACL allows writes to `upload:*` only.
   - If the lease is gone and another `upload_id` now owns the hash → `409 UPLOAD_SUPERSEDED`.
   - If the lease is simply gone → `410 UPLOAD_EXPIRED`.
5. **Capacity.** If the temporary-disk budget (2 GB) or the write-concurrency limit (64) is reached → `503 BUSY`, retry in 5 s. Chunks may not add up to more than the announced `size` (`413`).
6. **Write.** Stream the body to a temporary part file while computing CRC32C; `fsync`; check the length and the CRC (`400`, retryable). Then, under the lock, atomically rename the part to `chunks/<n>`. A repeated chunk simply replaces the old one. Answer `204`.

### 5.3 Resume an upload

`GET /v1/uploads/{upload_id}` with the upload token ([routes.py:83](src/photon/imageserver/routes.py#L83), [pipeline.py:138](src/photon/imageserver/pipeline.py#L138)) → `UploadStatus`: state, received chunk indexes, received bytes, and `server_has_file`, which is true when a verified copy for this session and hash already exists. An upload with no chunks yet but a live lease reports `RECEIVING`; one with neither is `404 UNKNOWN_UPLOAD`. After a network failure, the client sends only the missing chunks.

### 5.4 Complete

`POST /v1/uploads/{upload_id}/complete {chunk_count}` with the upload token ([routes.py:88](src/photon/imageserver/routes.py#L88), [pipeline.py:165](src/photon/imageserver/pipeline.py#L165)). It is idempotent: every repeat returns the same outcome. The upload moves through these states, each saved to `state.json` before any response:

```
RECEIVING → VERIFYING → REPORTING → STORED
                 └──→ REJECTED (HASH_MISMATCH, INVALID_IMAGE)
orchestrator answers: 409 → SUPERSEDED, 410 → ENDED
```

Under the upload lock:

1. **Already final?** `STORED` → `200` with the same result. `REJECTED` → the same `422` again. `SUPERSEDED` → `409`. `ENDED` → `410`. No state file and no lease → `404 UNKNOWN_UPLOAD`.
2. **Recover from a crash.** If the state is `VERIFYING`, assembly crashed and the consumed chunks are gone. Reset to `RECEIVING`; the client resends them.
3. **Check without cache** ([access.py:91](src/photon/imageserver/access.py#L91)): the session is `ACTIVE`, the uploader is still a member, and the lease is alive (otherwise `409 UPLOAD_SUPERSEDED` or `410 UPLOAD_EXPIRED`).
4. **Reuse.** If a verified, unexpired file for this session and hash already exists (for example after Redis lost the reservation), skip all processing, keep its expiry, and report it under the new `upload_id`.
5. **Check the chunks.** Every index below `chunk_count` must be present (`400 INVALID_REQUEST` with `missing_chunks` listed), none above it, and the bytes must add up to the announced size.
6. **Assemble and hash** ([pipeline.py:255](src/photon/imageserver/pipeline.py#L255)): set `VERIFYING`, then stream the chunks into one file while computing SHA-256, deleting each chunk as it is consumed (peak extra disk is about one photo). If the digest differs from the token's `sha256`, reject with **`422 HASH_MISMATCH`**. The hash is over the encrypted bytes, so the server verifies the file without being able to read it.
7. **Check the header** ([pipeline.py:238](src/photon/imageserver/pipeline.py#L238)). This is the only look the server gets. It parses the file's first 85 bytes ([e2ee.md §8](e2ee.md#8-what-the-server-checks)): magic `PHE1`, version 1, an epoch of at least 1, and sane section lengths (a non-empty thumbnail and original, metadata of at most 4 KB). The lengths must add up to the file's size, and the epoch must equal the upload token's `key_epoch`. Any failure → **`422 INVALID_IMAGE`**. The server never decodes the photo, because its type, dimensions and pixels are encrypted; receivers decode defensively instead. Then move the file to `staged/file`.
8. **Metadata.** Write `staged/meta.json`: sid, hash, size, mime (the E2EE type), uploader, `upload_id`, `taken_at_ms`, `key_epoch`, the header length and the four section lengths (used to serve the thumbnail and preview prefixes), `stored_at_ms = now` and **`expires_at_ms = now + 30 minutes`**. The image server sets the expiry, and the orchestrator adopts it.
9. **Report.** Write the outbox record `outbox/<upload_id>.json` *before* sending anything, and set `REPORTING` ([pipeline.py:271](src/photon/imageserver/pipeline.py#L271)).

On a rejection ([pipeline.py:281](src/photon/imageserver/pipeline.py#L281)), the upload's files are deleted, `REJECTED` is recorded with its code, a `rejected` report goes into the outbox, and the client gets the `422`.

Outside the lock, `complete` waits up to 5 seconds for the outbox to get a final answer from the orchestrator:

- **Answered `APPLIED` or `NOOP`:** the staged directory has been promoted to `photos/<sid>/<sha256>/` → **`200 CompleteResponse`** (`STORED`, `stored_at_ms`, `expires_at_ms`).
- **No final answer yet** (the orchestrator is slow or unreachable) → **`202` with `Retry-After: 2`**. The client calls `complete` again, and the outbox keeps retrying in the background.

### 5.5 The report to the orchestrator

The outbox ([src/photon/imageserver/outbox.py](src/photon/imageserver/outbox.py)) sends each record to `POST /internal/v1/photos/stored` or `/rejected` with a service token, and retries with exponential backoff and jitter (up to a minute) until it gets a **final** answer: `200`, `409` or `410`. Anything else, including a timeout, is retried. At startup it resends every record still on disk, which recovers a crash between saving a file and reporting it ([src/photon/imageserver/app.py:72](src/photon/imageserver/app.py#L72)).

**Stored** ([src/photon/orchestrator/routes/internal.py:26](src/photon/orchestrator/routes/internal.py#L26)) → `report_stored` ([photon.lua:372](src/photon/orchestrator/lua/photon.lua#L372)):

| Situation | Function result | HTTP answer | What the image server does |
| --- | --- | --- | --- |
| Session not `ACTIVE` | `SESSION_ENDED` | `410` | Deletes the upload's files; state `ENDED` |
| Hash unknown, or another `upload_id` owns it (**fencing**) | `UPLOAD_SUPERSEDED` | `409` | Deletes the upload's files; state `SUPERSEDED` |
| Uploader no longer a member | Reservation released, `UPLOAD_SUPERSEDED` | `409` | As above |
| Already `STORED` (a duplicate report) | `NOOP` | `200 NOOP` | Promotes, if not already; state `STORED` |
| `UPLOADING` with this `upload_id` | Status `STORED` with the image server's `stored_at` and `expires_at`; bytes move from reserved to stored; live entry and lease removed; **`PHOTO_STORED` appended** in the same step, carrying the photo tag and key epoch from the reservation | `200 APPLIED` with the event id | Promotes `staged/` to `photos/<sid>/<sha256>/`; state `STORED` |

On `APPLIED` *and* on `NOOP`, the orchestrator adds `sid:sha256` to the expiry index at `expires_at`. `ZADD LT` is idempotent, so this repairs a crash between the function and the queueing. On `APPLIED` it also records the upload duration (announce to stored) and sends `stored` push hints ([section 6.4](#64-push-wake-ups)). The orchestrator counts a clock-skew metric when the image server's `stored_at` differs from its own clock by more than 2 seconds.

Because `STORED` and its event are written in one atomic step, a photo is `STORED` exactly when its `PHOTO_STORED` event exists. A late or duplicate report can never revive or clear another upload's reservation.

The image server applies each answer in a repeat-safe order ([pipeline.py:293](src/photon/imageserver/pipeline.py#L293)): first the effect (promote or delete), then the state, then removing the outbox record.

**Rejected** ([internal.py:52](src/photon/orchestrator/routes/internal.py#L52)) → `report_rejected` ([photon.lua:395](src/photon/orchestrator/lua/photon.lua#L395)). Fenced the same way. It releases the reservation, its tag and the quota, and **adds a strike** to the uploader; three strikes block their uploads in this session. Nothing is announced.

### 5.6 What happens when something fails

| Failure | Recovery |
| --- | --- |
| A chunk fails or the network drops | The client retries the chunk (CRC mismatches and short bodies are retryable), or asks `GET /v1/uploads/{id}` and sends only the missing chunks |
| The uploader's app dies mid-upload | The lease expires after 15 minutes without chunks. A re-announce by the same user within the lease gets the same `upload_id`; after it, any member's announce takes over with a new `upload_id` once the reservation is 60 s old. The old upload's late reports are fenced out (`409`) and its files deleted. |
| `complete` times out on the client | Repeat it: `202` while the report is pending, then the same `200` or `422` |
| The image server crashes after saving, before reporting | The outbox record is on disk and is resent at startup |
| The image server crashes mid-assembly | The state stays `VERIFYING`; the next `complete` resets to `RECEIVING` and reports the missing chunks |
| The orchestrator is down when the report is sent | The outbox retries with backoff; `complete` answers `202` meanwhile |
| Redis loses the reservation (about the last second of writes on a crash) | The client re-announces and gets a new `upload_id`; `complete` finds the verified file, skips reprocessing and reports it again under the new id |
| A member left or was kicked since the last upload | Announce answers `409 KEY_ROTATION_REQUIRED`; the uploader rotates ([section 4.10](#410-rotate-the-session-key-after-a-leave-or-kick)), encrypts the photo again with the new epoch and announces again |
| The epoch moved on while the file was being built | Announce answers `409 KEY_EPOCH_STALE`; the client runs key maintenance, rebuilds the file and announces again |
| The uploader loses its built file before the upload finishes | Re-announcing the same file returns the same `upload_id`. A rebuilt file has a new hash but the same tag, and the announce replaces the uploader's own unfinished reservation with it |
| The file isn't a well-formed `PHE1` file, or its header names another epoch | `422 INVALID_IMAGE` at `complete`; the rejection report adds a strike, as a hash mismatch does |
| The session ends during the upload | Chunks and `complete` answer `410 SESSION_ENDED`; a report answers `410`; the image server deletes the files; the image server's sweeper also drops temporary directories of ended sessions |

---

## 6. Receiving: snapshot, WebSocket, catch-up, push, download

Events have one durable source: the session's Redis stream `session:{sid}:events`. Its entry ids (`<ms>-<seq>`) increase within a session and are the clients' cursors. Delivery is at least once; clients drop events whose id they have already processed, and photo events for hashes they already hold, so the effect is exactly once.

| Event | Appended by | Carries |
| --- | --- | --- |
| `PHOTO_STORED` | `report_stored` | sha256 of the encrypted file, uploader, size, `taken_at_ms`, `expires_at_ms`, `photo_tag`, `key_epoch` (never a URL) |
| `PHOTO_RECALLED` | `recall` | sha256, uploader |
| `MEMBER_JOINED` | `create_session`, `join` (also a rejoin with a new member key) | member id, `member_key` |
| `MEMBER_LEFT` | `leave`, `kick` | member id |
| `HOST_CHANGED` | `leave` when the host leaves | new host id |
| `SESSION_ENDED` | `end_session` (host or idle), last member leaving | — |
| `KEY_SHARED` | `put_keys` (sharing) | member id of the recipient, `key_epoch`: a sealed box is waiting for them |
| `KEY_ROTATED` | `put_keys` (rotation) | member id of the rotator, `key_epoch` (the new epoch): every member fetches their new box |

### 6.1 Snapshot

`GET /v1/sessions/{sid}/snapshot` ([src/photon/orchestrator/routes/events.py:38](src/photon/orchestrator/routes/events.py#L38)) → `snapshot` ([photon.lua:523](src/photon/orchestrator/lua/photon.lua#L523)). It needs an active session and a member. It answers `Snapshot`:

- `cursor`: the last event id. It is read in the same atomic step as the state, so anything stored afterwards also arrives as an event.
- Member ids.
- Every `STORED`, unexpired photo as a `PhotoEvent`, with its `photo_tag` and `key_epoch`.
- The recalled hashes.
- `key_epoch`: the session's current key epoch, so a client can tell at once whether it holds it.

The client loads it on first join and whenever it must resync, then continues the stream from `cursor`.

### 6.2 The WebSocket

`/v1/ws` on the orchestrator ([events.py:78](src/photon/orchestrator/routes/events.py#L78)). Each socket is a `Connection` ([events.py:93](src/photon/orchestrator/routes/events.py#L93)):

1. **Hello.** The server accepts the socket and waits up to 10 seconds for the first binary frame. It must be `ClientFrame.hello` with the **access token, session id and `last_event_id`**. The token is never in the URL.
2. **Check.** Verify the token, then `ws_check` ([photon.lua:551](src/photon/orchestrator/lua/photon.lua#L551)): the session is active, the caller is a member, and the cursor can be continued. A cursor needs a resync if it is malformed, older than the trimmed part of the stream, ahead of the last event (the client saw events Redis has since lost), or the stream is gone while events existed ([photon.lua:155](src/photon/orchestrator/lua/photon.lua#L155)).
3. **Ready.** The server sends `ServerFrame.ready_cursor`, records presence in `session:{sid}:online`, and starts three tasks; the first to end closes the socket:
   - **Reader** ([events.py:167](src/photon/orchestrator/routes/events.py#L167)): one loop of `XREAD BLOCK 15000` from the cursor, on a dedicated Redis connection pool (one connection per open socket). Backlog and live events come from the same read, so no event can fall between a replay and a subscription. Each entry is sent as `ServerFrame.event`. A read that times out with nothing new re-runs `ws_check`, which catches an ended session, a kick or a lost stream. The reader also closes the socket on `MEMBER_LEFT` for this user (`4403`) and on `SESSION_ENDED` (`4410`).
   - **Receiver** ([events.py:193](src/photon/orchestrator/routes/events.py#L193)): answers each `ping` with `pong`, refreshes presence (at most every 30 s) and counts as activity (`touch`).
   - **Token timer** ([events.py:221](src/photon/orchestrator/routes/events.py#L221)): closes the socket with `4001` when the access token expires.
4. **Close.** On an error, the server sends `ServerFrame.error`, then closes with a code ([events.py:25](src/photon/orchestrator/routes/events.py#L25)):

| Close code | Meaning | What the client does |
| --- | --- | --- |
| `1000` | Normal close | — |
| `1011` | Server fault | Reconnect with backoff |
| `4001` | `TOKEN_EXPIRED` | Refresh, then reconnect from its last event id |
| `4400` | `INVALID_REQUEST` (no hello, bad frame) | Fix the request |
| `4401` | `UNAUTHENTICATED` | Sign in again |
| `4403` | `NOT_A_MEMBER` (left or kicked) | Stop; rejoin only if invited again |
| `4409` | `RESYNC_REQUIRED` | Load the snapshot, then reconnect from its cursor |
| `4410` | `SESSION_ENDED` | Stop syncing |

The metric `stored_to_delivered_seconds` measures each `PHOTO_STORED` from its stream id to the moment it is sent.

### 6.3 Catch-up without a socket

`GET /v1/sessions/{sid}/events?since=<event id>&limit=<1–1000, default 500>` ([events.py:54](src/photon/orchestrator/routes/events.py#L54)) → `events_since` ([photon.lua:542](src/photon/orchestrator/lua/photon.lua#L542)). It applies the same member and resync rules as the WebSocket, then returns an `EventBatch` of the entries after `since` and the new cursor. A cursor that can't be continued answers `410 RESYNC_REQUIRED`, and the client loads the snapshot. Apps use it after a push wake-up and on start, when a socket isn't open.

### 6.4 Push wake-ups

`Pusher.notify` ([src/photon/orchestrator/push.py:118](src/photon/orchestrator/push.py#L118)) runs in the background, never holding up a response. It fires after:

- a `stored` report is applied (`kind=stored`);
- a recall (`kind=recalled`);
- a join, to the other members (`kind=joined`), so that a member without a live socket wakes up and seals the session key for the newcomer ([section 4.9](#49-share-the-session-key-key-maintenance));
- a session end (`kind=ended`, to the members as they were before the end).

For each member:

1. Skip the member if their socket showed activity in the last 90 seconds. A live socket delivers the event itself.
2. Send at most one push per user per 30 seconds (`push:<uid>`, `SET NX`).
3. Send `{"sid", "kind"}` to every device in Postgres `devices`: Android through FCM (high-priority data message), iOS through APNs (background push, `content-available`).
4. Delete tokens the push service reports as invalid.

Without credentials, the sender only logs ([push.py:106](src/photon/orchestrator/push.py#L106)). A push carries no photo data and may be dropped, delayed or merged. The phone always answers it with the same catch-up: events since its cursor, or the snapshot, followed by key maintenance.

### 6.5 Download a photo

`GET /v1/sessions/{sid}/photos/{sha}?size=thumb|preview|full` on the image server, with the **user's access token** ([src/photon/imageserver/routes.py:97](src/photon/imageserver/routes.py#L97)). There are no shareable links; every request, including each `Range` request, is checked:

1. `download_check` ([photon.lua:511](src/photon/orchestrator/lua/photon.lua#L511)) reads the session status, membership and the photo entry in one call, with no cache.
2. In this order:
   - not a member → `404 PHOTO_NOT_FOUND` (non-members can't learn a photo exists);
   - session not `ACTIVE` → `410 SESSION_ENDED`;
   - photo absent or `UPLOADING` → `404`;
   - `RECALLED` → `410 PHOTO_RECALLED`;
   - `EXPIRED`, or now is at or past `expires_at` → `410 PHOTO_EXPIRED`.
3. Read `meta.json` from `photos/<sid>/<sha256>/`. In the moment between the orchestrator's `200` and promotion, it falls back to the staged copy. **Then the image server applies its own expiry guard:** past the `expires_at_ms` in its own metadata → `410`, whatever Redis says.
4. Serve the encrypted file, or a prefix of it, as `application/octet-stream` ([e2ee.md §6.3](e2ee.md#63-serving-sizes-without-decrypting)). The section lengths in `meta.json`, copied from the file's header at `complete`, say where each section ends:
   - `thumb` → bytes from 0 to the end of the thumbnail section: header, metadata and thumbnail.
   - `preview` → bytes from 0 to the end of the preview section: the same plus the preview.
   - `full` → the whole file, supporting a single `Range` (`206`, or `416` for an invalid range).

Every response has `Cache-Control: private, no-store` and an `ETag` of `<sha>-<size>`, so no shared cache can serve a photo after it expires.

**The receiver decrypts what it got** (`PhotonClient.download_photo` in [src/photon/client/api.py](src/photon/client/api.py)):

1. For `full`, check that the SHA-256 of the bytes equals `sha`.
2. Parse the header and unlock the photo key with `K_epoch` for the header's epoch. If it lacks that epoch, it runs key maintenance first.
3. Decrypt the metadata and whatever sections the prefix holds. Any tampered byte fails AES-GCM.
4. An empty preview means the original is small enough to be its own preview, so the receiver fetches `full` instead.
5. After decrypting an original, recompute the photo tag and compare it with the event's `photo_tag`. A mismatch means the uploader labelled the file with another photo's tag, for example to block it, and the receiver rejects the photo.
6. Decode the image defensively. The server could not check that the decrypted bytes are a valid image.

---

## 7. Recall and report

### 7.1 Recall a photo

`POST /v1/sessions/{sid}/photos/{sha}/recall` ([src/photon/orchestrator/routes/photos.py:75](src/photon/orchestrator/routes/photos.py#L75)) → `recall` ([photon.lua:410](src/photon/orchestrator/lua/photon.lua#L410)):

1. The session must be active and the caller a member.
2. The photo must exist and not be `UPLOADING` (`404`). Already `RECALLED` → nothing changes. Expired → `410 PHOTO_EXPIRED`.
3. Only the uploader or the host may recall (`403`).
4. Set `RECALLED`, subtract the stored bytes, and append `PHOTO_RECALLED`.
5. Queue the file for deletion now, try the delete at once, and send `recalled` push hints. Answer `204`.

Downloads stop at once (`410 PHOTO_RECALLED`). The `RECALLED` tombstone stays until the session ends, so a later announce of the same hash, or of the same photo in another file (the same tag), answers `410` instead of re-uploading it. Online members delete their cached copies on the event; offline members do so on their next snapshot, which lists recalled hashes. Originals already saved to someone's gallery are not touched.

### 7.2 Report a photo

`POST /v1/sessions/{sid}/photos/{sha}/report {reason}` ([routes/photos.py:88](src/photon/orchestrator/routes/photos.py#L88)):

1. The reason may be up to 500 characters. Rate limit: 10 a minute.
2. The caller must be an active member (checked through `session_info`), and the photo must exist (`404`).
3. Insert a row into Postgres `photo_reports` (session, hash, reporter, uploader, reason). Count `photo_reports_total` and log a warning. Answer `204`.

Nothing changes for members. The report backlog is an operations alert ([deploy/alerts.yml](deploy/alerts.yml)). The server can't see the photo, so a report records only who reported what and why ([e2ee.md §10](e2ee.md#10-limits-and-trade-offs)).

---

## 8. Background flows: expiry, idle sessions, recount, sweeping, outbox

The orchestrator runs three jobs in every replica ([src/photon/orchestrator/jobs.py:125](src/photon/orchestrator/jobs.py#L125)). Each starts at a random offset and runs with ±10% jitter. A Redis lock per shard or job makes sure only one process does each piece of work at a time.

### 8.1 Expiry sweeper (every 60 s)

For each of the 16 `expiry:<n>` shards, under the lock `lock:expiry:<n>` ([jobs.py:34](src/photon/orchestrator/jobs.py#L34)):

1. Take up to 200 entries due now, oldest first, and process 16 at a time.
2. Run `expire_photo` ([photon.lua:428](src/photon/orchestrator/lua/photon.lua#L428)):
   - `STORED` and due → mark it `EXPIRED` and subtract its bytes.
   - Not yet due → move the entry to its real time. **A live photo is never deleted.**
   - Already gone, recalled or ended → continue to the delete.
3. Call `DELETE /internal/v1/photos/{sid}/{sha}` on the image server with a service token ([src/photon/orchestrator/svc.py:133](src/photon/orchestrator/svc.py#L133)). `204` and `404` both count as acknowledged.
4. On acknowledgement, remove the entry. On failure, push it back with exponential backoff (2, 4, 8 … up to 300 s).

The image server's `DELETE` ([src/photon/imageserver/routes.py:134](src/photon/imageserver/routes.py#L134)) renames the photo directory into `trash/`, records the deletion lag, and removes an empty session directory. It is idempotent.

Recall and session end feed the same index with a delete time of "now". So every server copy leaves through one path, retried until the image server confirms.

### 8.2 Idle session sweeper (every 30 s)

Under `lock:session_sweeper` ([jobs.py:74](src/photon/orchestrator/jobs.py#L74)), take up to 100 sessions from `sessions:due` whose idle expiry has passed and run `end_session` in `idle` mode:

- Ended → the same end clean-up as a host end ([section 4.6](#46-end-a-session)).
- `NOT_DUE` (touched since) → reschedule.
- Already ended → drop the entry.

### 8.3 Quota recount (every 5 minutes)

Under `lock:recount` ([jobs.py:93](src/photon/orchestrator/jobs.py#L93)), for every session in `sessions:due`, `recount` ([photon.lua:440](src/photon/orchestrator/lua/photon.lua#L440)):

1. Releases stale reservations (lease gone, older than 60 s).
2. Marks overdue `STORED` photos `EXPIRED` and queues them for deletion. This repairs lost expiry entries.
3. Recomputes `stored_bytes` and `reserved_bytes` from the photos hash.
4. Reports the correction size as a metric, and logs it when non-zero.

### 8.4 Image server sweeper (every 60 s)

The image server's own sweeper ([src/photon/imageserver/sweeper.py:26](src/photon/imageserver/sweeper.py#L26)) decides from its own files, never from Redis alone. So losing Redis state can neither delete a valid file early nor keep a dead one forever.

1. **Photos** ([sweeper.py:32](src/photon/imageserver/sweeper.py#L32)):
   - A file past the `expires_at_ms` in its `meta.json` goes to trash, *whether or not the orchestrator ever asked*. Any lag over 15 minutes counts toward an alert.
   - A photo directory without metadata, older than 1 hour, is an orphan and goes to trash.
   - The sweeper then exports how many files are still past expiry (should be 0).
2. **Temporary uploads** ([sweeper.py:63](src/photon/imageserver/sweeper.py#L63)), skipping any upload with a pending outbox record:
   - A directory without `state.json`, older than 15 minutes, is dropped.
   - A final state older than 1 hour is dropped.
   - An upload of a session that is no longer `ACTIVE` is dropped.
   - An upload whose lease has been missing for more than 15 minutes is dropped.
3. **Trash** is emptied, and the temporary-disk usage is recounted.

### 8.5 Image server outbox loop

The outbox task ([src/photon/imageserver/outbox.py:101](src/photon/imageserver/outbox.py#L101)) wakes on every new record, or at least every second. It sends every record that is due and not already in flight, and exports the backlog size and the age of the oldest record (both alerted). Its send and retry rules are in [section 5.5](#55-the-report-to-the-orchestrator).

---

## 9. Client-side flows (Android, iOS, CLI)

The server flows above are what every client must drive. This section shows how the two apps drive them. Neither app has been run end to end against the backend, and the iOS app target has never been compiled ([android/README.md](android/README.md), [ios/README.md](ios/README.md)). These are the flows as written, not as observed on a device.

**End-to-end encryption.** The tables below describe the apps' flows from before encryption. The Android app is being moved to E2EE (identity key, key maintenance, encrypting before upload, decrypting after download; see [android/README.md](android/README.md)). **The iOS app has not been updated.** It sends no member key and uploads plain photos, so against this server it can't create, join or upload until it is ([e2ee.md §10](e2ee.md#10-limits-and-trade-offs)). Wherever a step below hashes the photo, fetches a WebP thumbnail or verifies the original's SHA-256, an E2EE client instead hashes the encrypted file ([section 5](#5-photo-upload-announce-chunks-complete-report)) and decrypts the served prefixes ([section 6.5](#65-download-a-photo)). The Python reference client ([section 9.7](#97-the-reference-client-and-cli)) implements the E2EE flows in full.

Paths below are shortened: `A/` is [android/app/src/main/java/com/photon/app/](android/app/src/main/java/com/photon/app/), `K/` is [ios/PhotonKit/Sources/PhotonKit/](ios/PhotonKit/Sources/PhotonKit/) (the iOS core), and `P/` is [ios/PhotonApp/Sources/](ios/PhotonApp/Sources/) (the iOS app target).

### 9.1 Sign-in

| | Android | iOS |
| --- | --- | --- |
| Which methods | Asks `GET /v1/auth/config` and shows Google, GitHub and (debug builds only) dev (`A/ui/AppViewModel.kt:168`, `A/auth/AuthService.kt:62`) | Browser OAuth only, provider fixed by build: `dev` in Debug, `google` in Release (`ios/PhotonApp/Config/*.xcconfig`). Never calls `/v1/auth/config`, `/nonce`, `/google` or `/dev/login`. |
| Google | Native: `POST /v1/auth/nonce`, Credential Manager ID token, `POST /v1/auth/google` ([section 3.3](#33-native-google-sign-in-android-credential-manager-ios)) (`A/auth/AuthService.kt:100`) | Through the browser flow |
| Browser flow | `oauth/start` with PKCE; verifier and state in encrypted preferences; Custom Tab; redirect into `MainActivity`; `oauth/callback` (`A/auth/BrowserOAuth.kt:86`, `:100`). A repeated state is ignored; `access_denied` cancels quietly. | `oauth/start`; `ASWebAuthenticationSession`; `oauth/callback`; verifier in the Keychain (`K/Auth/AuthFlow.swift:30`). A Universal Link can also finish the login (`K/PhotonClient.swift:132`). |
| Redirect URI | `photon://auth/cb` in debug, an App Link `https://<host>/auth/cb` in release | The same pair |
| Token storage | EncryptedSharedPreferences, AES-256-GCM (`A/data/prefs/EncryptedTokenStore.kt`) | Keychain, after first unlock, this device only (`P/Platform/KeychainStore.swift:37`) |
| After sign-in | Resume or drop the stored session, register the device, check `GET /v1/sessions/current` (`A/session/SessionManager.kt:272`) | Register the device; resume as in 9.4 |

### 9.2 Token refresh

Both apps refresh one at a time (Android with a mutex, iOS with an actor sharing one in-flight task). Both refresh ahead of time when the access token has less than 30 seconds left (iOS: 60 seconds before opening a WebSocket). After a `401`, both refresh once and retry the call once (`A/net/AuthInterceptor.kt:106`, `K/Net/APIClient.swift:71`).

They differ on when a failed refresh signs the user out. Android signs out only on a `401` from `/v1/auth/refresh` and keeps the tokens on any other failure (`A/net/TokenRefresher.kt:55`). iOS signs out on a `401` or a `403` (`K/Auth/TokenManager.swift:108`).

### 9.3 Push registration and wake-ups

- **Android** sends `PUT /v1/devices/current` (`platform=android`) after each sign-in and whenever FCM issues a new token, but only when the build includes `google-services.json` (`A/push/Push.kt:31`). An FCM message starts `CatchUpWorker`: expire old local items, run catch-up (again if another push arrived meanwhile, at most 3 passes), then the reconcile scan and pending uploads (`A/events/CatchUpWorker.kt:25`).
- **iOS** registers for remote notifications at every launch and sends `PUT /v1/devices/current` (`platform=ios`, hex token) once signed in (`P/App/AppDelegate.swift:20`). A silent push runs `backgroundCatchUp`, capped at 25 seconds: events or snapshot, reconcile, receive, uploads (`K/PhotonClient.swift:349`). The same catch-up also runs from background app refresh (every 15 minutes or more) and from background transfer wake-ups.

### 9.4 Sessions

Both apps use the same endpoints: create, join (code or invite token), leave, end, kick, rotate and `GET /v1/sessions/current` (`A/net/PhotonApi.kt:70`, `K/Net/APIClient.swift:137`). Both show a consent screen first, because every photo taken during the session is shared.

**Entering a session** (`A/session/SessionManager.kt:70`, `K/PhotonClient.swift:174`):

1. Store the role, host, code, invite and members. A rejoin keeps the original join time and event cursor.
2. Load the snapshot.
3. Open the WebSocket.
4. Start detection, run the reconcile scan and drain uploads.

**On `409 ALREADY_IN_SESSION`**, both read `GET /v1/sessions/current` and offer to open that session or leave it and retry.

**Joining by link or QR.** Android scans QR codes in the app (zxing) and handles `/j/<token>` App Links. iOS has no in-app scanner and relies on the system camera opening the Universal Link.

**Leaving and ending** tolerate "already gone" answers:

- Android ignores those errors on leave, and accepts `SESSION_ENDED` or not-found on end.
- iOS accepts `403`, `404` and `410` on leave, and only `410` on end.

### 9.5 Upload pipeline

**Detecting new photos**

| | Android | iOS |
| --- | --- | --- |
| While running | A foreground service (special use) holds a MediaStore `ContentObserver`, debounced 750 ms, which scans and enqueues `UploadWorker` (`A/sync/SyncService.kt:55`) | A `PHPhotoLibrary` change observer, debounced 1 s (`P/Platform/PhotoLibraryObserver.swift:35`) |
| Process not running | A WorkManager content-URI trigger plus a 15-minute safety-net job (`A/sync/WorkScheduler.kt:68`) | Nothing runs while suspended; a scan on becoming active, on resume and on join (`K/PhotonClient.swift:318`) |
| Eligible photos | `DCIM/` only, not screenshots, not `Pictures/PHOTON/`, not files the app owns, inside the join and pause windows, not in `received_assets` (`A/sync/Eligibility.kt:72`) | User-library images created at or after the join time, not screenshots, not app-saved, not in the PHOTON album (`K/Sync/Reconciler.swift:10`) |

**Announcing.** Both apps hash a copy with GPS removed and skip a hash they already hold, then announce:

- `ALREADY_UPLOADED`: mark the photo synced.
- `IN_PROGRESS`: wait. Re-announce after 2 minutes, doubling up to 15. Only a `PHOTO_STORED` event marks the photo synced.
- `UPLOAD`: Android uses the returned `upload_url` (falling back to `<image base>/v1/uploads/<id>`). iOS ignores `upload_url` and builds the URL from its configured image base (`K/Upload/UploadEngine.swift:407`), keeping the upload token in memory only.

**Sending chunks.**

1. Ask `GET /v1/uploads/{id}` first. Send chunks only while the state is `RECEIVING` and the server doesn't already hold the file.
2. Choose the chunk size to take about 2 seconds at the measured throughput, else the server's hint, else 1 MiB. Clamp it to 256 KiB–8 MiB, align it to 64 KiB, and keep the count under 4,096 (`A/upload/ChunkPlanner.kt:31`).
3. Send 3 chunks in parallel, each with `X-Chunk-CRC32C`. Retries: Android 3; iOS 4 attempts with 0.5–8 s backoff. iOS sends chunks through a background `URLSession`, so transfers continue while the app is suspended.

**Completing.** Both poll `complete` at most 30 times:

- `200`: synced.
- `202`: wait for `Retry-After` (2 s by default).
- `400` with missing chunks: resend them, at most 3 rounds.
- `503`: wait, then try again.

**Errors** (`A/upload/Uploader.kt:346`, `K/Upload/UploadEngine.swift:225`):

| Answer | Android | iOS |
| --- | --- | --- |
| `UPLOAD_FINALIZED` | Call `complete` again | Re-announce |
| `UPLOAD_SUPERSEDED`, `UPLOAD_EXPIRED`, `UNKNOWN_UPLOAD`, `401` while uploading | Re-announce (at most 3 times per run) | Re-announce |
| `SESSION_ENDED`, `NOT_A_MEMBER` | End the session locally | End the session locally |
| Recalled or expired | Skip | Skip (any other `410`) |
| `422` | Mark failed, notify the user | Mark failed, show it in the UI |
| `413`, `415` | — | Mark failed |
| Rate limited | Pause the queue | — |

### 9.6 Receiving

**The WebSocket** URL is the API base with `ws`/`wss` and `/v1/ws`. Both run an HTTP catch-up before every connect, then send `hello{access_token, session_id, last_event_id}` (Android won't connect without a cursor). Both ping every 30 seconds. iOS replaces a socket that has been silent for 75 seconds; Android has no silence detection (its read timeout is 0).

| Close code | Android (`A/events/CloseHandler.kt:75`) | iOS (`K/Sync/EventSync.swift:35`) |
| --- | --- | --- |
| `4001` | Refresh, reconnect | Refresh, reconnect |
| `4401` | Try a refresh first, like `4001` | Sign out; log in again |
| `4403`, `4410` | End locally only after `GET /v1/sessions/current` confirms it | Stop at once |
| `4409` | Snapshot, reconnect (at most 3 in a row) | Snapshot, reconnect (at most 3 in a row) |
| Anything else | Backoff 1–60 s with jitter, reset after 30 s of a stable connection | Backoff, reset when `ready_cursor` arrives |

**Catch-up** pages through `GET /v1/sessions/{sid}/events?since=…&limit=500` until a short page. Both load the snapshot when there is no cursor, or on `410 RESYNC_REQUIRED` (at most 3 on Android, 2 on iOS). After a snapshot, both re-read the host: Android with `GET /v1/sessions/current`, iOS with `GET /v1/sessions/{sid}`.

**Event handling.** An event whose id is at or below the cursor is dropped, and the cursor advances only after the event is handled. Unknown event types are skipped, and the cursor still advances.

- `PHOTO_STORED` creates a receive job, unless the photo is the user's own, the hash is already known or queued, or it has expired.
- `PHOTO_RECALLED` deletes cached copies and cancels downloads; originals saved to the gallery stay. Android also records a tombstone for a hash it hasn't seen yet; iOS doesn't.
- `SESSION_ENDED` ends the session locally.
- `MEMBER_LEFT` for the user themselves means they were kicked; for anyone else, it updates the member list.
- `HOST_CHANGED` updates the host and the user's role.

**Downloads** (`A/receive/ReceiveRules.kt:69`, `K/Receive/ReceivePipeline.swift:43`):

1. Always fetch the thumbnail.
2. Fetch the preview on an unmetered network or at 5 Mbit/s or more; a `503` keeps the thumbnail.
3. Fetch the original now, or later on an unmetered network or while charging if the user chose that.

Both verify the original's SHA-256. Android resumes an interrupted original with `Range`; iOS downloads into memory with no resume. On a `404`, Android retries 3 times and iOS marks the photo failed.

**Saving without an echo loop.**

- Android writes the file into `Pictures/PHOTON/` with `IS_PENDING=1`, records the MediaStore id in `received_assets` and the hash, and only then makes the file visible (`A/receive/GallerySaver.kt:58`).
- iOS records the hash first, then the asset id inside the PhotoKit change block, in the PHOTON album.

Either way, the app's own observer never re-uploads a received photo.

### 9.7 The reference client and CLI

[src/photon/client/](src/photon/client/) is a Python client used by the end-to-end tests, the load test ([tools/loadtest.py](tools/loadtest.py)) and the `photon-cli` command. `photon-cli` covers `login`, `create`, `join`, `keys`, `listen`, `upload`, `end`, `info`, `snapshot`, `catchup`, `recall`, `report`, `download`, `kick`, `rotate`, `leave` and `device`. It is the complete E2EE client:

- **Keys.** [keyring.py](src/photon/client/keyring.py) holds the install's X25519 identity and, per session, the epoch bundles it holds. The CLI keeps it in `$PHOTON_HOME/<profile>.keys.json` (mode 600). `create_session` makes epoch 1 and sends the public key, `join` sends it, and `sync_keys` and `rotate_keys` implement [sections 4.9](#49-share-the-session-key-key-maintenance) and [4.10](#410-rotate-the-session-key-after-a-leave-or-kick). `photon-cli join` waits up to `--wait` seconds (15 by default) for a member to share the key. `photon-cli keys` prints the epoch, the epochs held and every member's security code ([e2ee.md §9](e2ee.md#9-security-codes)).
- **Upload.** `upload_bytes` ([uploader.py](src/photon/client/uploader.py)) makes the thumbnail and preview ([images.py](src/photon/client/images.py)), encrypts, and uploads. On `KEY_ROTATION_REQUIRED` it rotates; on `KEY_EPOCH_STALE` it runs key maintenance; in both cases it then rebuilds the file and announces again. `upload_file` sends a file that is already built.
- **Download.** `download_photo` decrypts a full file or a prefix and checks the photo tag ([section 6.5](#65-download-a-photo)). `photon-cli download` saves the decrypted image; `--raw` saves the encrypted file as served.

Its listener ([src/photon/client/listener.py](src/photon/client/listener.py)) sends `hello` and a ping every 30 seconds, and runs key maintenance on start, on `MEMBER_JOINED` for someone else, on `KEY_SHARED` for itself and on `KEY_ROTATED`. It handles close codes as follows:

- `4403`, `4410` and `4401`: stop.
- `4001`: refresh, then reconnect.
- `4409`: load the snapshot, then reconnect.

---

## 10. Endpoint index

Every route in the code. "Access token" means `Authorization: Bearer <access JWT>`.

| Method and path | Service | Caller and credential | Handler | Lua function | Section |
| --- | --- | --- | --- | --- | --- |
| `GET /v1/auth/config` | Auth | Anyone | [auth/app.py:172](src/photon/auth/app.py#L172) | — | [3.1](#31-sign-in-options) |
| `POST /v1/auth/oauth/start` | Auth | Anyone (rate-limited) | [auth/app.py:111](src/photon/auth/app.py#L111) | — | [3.2](#32-browser-oauth-with-pkce-google-in-the-browser-github-dev) |
| `GET /v1/auth/oauth/return` | Auth | Browser, from GitHub | [auth/app.py:182](src/photon/auth/app.py#L182) | — | [3.2](#32-browser-oauth-with-pkce-google-in-the-browser-github-dev) |
| `POST /v1/auth/oauth/callback` | Auth | App, with `state` and PKCE verifier | [auth/app.py:131](src/photon/auth/app.py#L131) | — | [3.2](#32-browser-oauth-with-pkce-google-in-the-browser-github-dev) |
| `POST /v1/auth/nonce` | Auth | Anyone (rate-limited) | [auth/app.py:196](src/photon/auth/app.py#L196) | — | [3.3](#33-native-google-sign-in-android-credential-manager-ios) |
| `POST /v1/auth/google` | Auth | App, with a Google ID token | [auth/app.py:204](src/photon/auth/app.py#L204) | — | [3.3](#33-native-google-sign-in-android-credential-manager-ios) |
| `POST /v1/auth/dev/login` | Auth (not in prod) | Anyone | [auth/app.py:220](src/photon/auth/app.py#L220) | — | [3.4](#34-dev-login-development-only) |
| `GET /v1/auth/dev/authorize` | Auth (not in prod) | Browser | [auth/app.py:231](src/photon/auth/app.py#L231) | — | [3.2](#32-browser-oauth-with-pkce-google-in-the-browser-github-dev) |
| `POST /v1/auth/refresh` | Auth | App, with a refresh token | [auth/app.py:152](src/photon/auth/app.py#L152) | Refresh script in [tokens.py](src/photon/auth/tokens.py#L23) | [3.5](#35-refresh-a-token) |
| `PUT /v1/devices/current` | Auth | Access token | [auth/app.py:159](src/photon/auth/app.py#L159) | — | [3.6](#36-register-a-device-for-push) |
| `GET /.well-known/jwks.json` | Auth | Verifiers | [auth/app.py:106](src/photon/auth/app.py#L106) | — | [3.7](#37-public-key-and-app-link-documents) |
| `GET /.well-known/assetlinks.json` | Auth | Android | [auth/app.py:249](src/photon/auth/app.py#L249) | — | [3.7](#37-public-key-and-app-link-documents) |
| `GET /.well-known/apple-app-site-association` | Auth | iOS | [auth/app.py:257](src/photon/auth/app.py#L257) | — | [3.7](#37-public-key-and-app-link-documents) |
| `POST /v1/sessions` | Orchestrator | Access token | [routes/sessions.py:71](src/photon/orchestrator/routes/sessions.py#L71) | `create_session` | [4.1](#41-create-a-session) |
| `POST /v1/sessions/join` | Orchestrator | Access token | [routes/sessions.py:95](src/photon/orchestrator/routes/sessions.py#L95) | `join` | [4.2](#42-join-a-session) |
| `GET /v1/sessions/current` | Orchestrator | Access token | [routes/sessions.py:126](src/photon/orchestrator/routes/sessions.py#L126) | `session_info` | [4.3](#43-read-session-info) |
| `GET /v1/sessions/{sid}` | Orchestrator | Access token, member | [routes/sessions.py:135](src/photon/orchestrator/routes/sessions.py#L135) | `session_info` | [4.3](#43-read-session-info) |
| `POST /v1/sessions/leave` | Orchestrator | Access token | [routes/sessions.py:139](src/photon/orchestrator/routes/sessions.py#L139) | `leave` | [4.4](#44-leave) |
| `POST /v1/sessions/{sid}/kick` | Orchestrator | Access token, host | [routes/sessions.py:157](src/photon/orchestrator/routes/sessions.py#L157) | `kick` | [4.5](#45-kick-a-member) |
| `POST /v1/sessions/{sid}/end` | Orchestrator | Access token, host | [routes/sessions.py:149](src/photon/orchestrator/routes/sessions.py#L149) | `end_session` | [4.6](#46-end-a-session) |
| `POST /v1/sessions/{sid}/invites/rotate` | Orchestrator | Access token, host | [routes/sessions.py:169](src/photon/orchestrator/routes/sessions.py#L169) | `set_invites` | [4.7](#47-rotate-the-invite) |
| `GET /v1/sessions/{sid}/keys` | Orchestrator | Access token, member | [routes/keys.py:24](src/photon/orchestrator/routes/keys.py#L24) | `key_state` | [4.9](#49-share-the-session-key-key-maintenance) |
| `POST /v1/sessions/{sid}/keys` | Orchestrator | Access token, member (holding the epoch it shares) | [routes/keys.py:40](src/photon/orchestrator/routes/keys.py#L40) | `put_keys` | [4.9](#49-share-the-session-key-key-maintenance), [4.10](#410-rotate-the-session-key-after-a-leave-or-kick) |
| `POST /v1/photos/announce` | Orchestrator | Access token, member | [routes/photos.py:27](src/photon/orchestrator/routes/photos.py#L27) | `announce` | [5.1](#51-announce) |
| `PUT /v1/uploads/{id}/chunks/{n}` | Image server | Upload token | [imageserver/routes.py:77](src/photon/imageserver/routes.py#L77) | `upload_check`, `touch_lease` | [5.2](#52-upload-chunks) |
| `GET /v1/uploads/{id}` | Image server | Upload token | [imageserver/routes.py:83](src/photon/imageserver/routes.py#L83) | — | [5.3](#53-resume-an-upload) |
| `POST /v1/uploads/{id}/complete` | Image server | Upload token | [imageserver/routes.py:88](src/photon/imageserver/routes.py#L88) | `upload_check` | [5.4](#54-complete) |
| `POST /internal/v1/photos/stored` | Orchestrator | Image server service token | [routes/internal.py:26](src/photon/orchestrator/routes/internal.py#L26) | `report_stored` | [5.5](#55-the-report-to-the-orchestrator) |
| `POST /internal/v1/photos/rejected` | Orchestrator | Image server service token | [routes/internal.py:52](src/photon/orchestrator/routes/internal.py#L52) | `report_rejected` | [5.5](#55-the-report-to-the-orchestrator) |
| `GET /internal/v1/jwks.json` | Orchestrator | Image server (private network) | [routes/internal.py:22](src/photon/orchestrator/routes/internal.py#L22) | — | [3.7](#37-public-key-and-app-link-documents) |
| `GET /v1/sessions/{sid}/snapshot` | Orchestrator | Access token, member | [routes/events.py:38](src/photon/orchestrator/routes/events.py#L38) | `snapshot` | [6.1](#61-snapshot) |
| `WS /v1/ws` | Orchestrator | Access token in the `hello` frame | [routes/events.py:78](src/photon/orchestrator/routes/events.py#L78) | `ws_check` | [6.2](#62-the-websocket) |
| `GET /v1/sessions/{sid}/events?since=` | Orchestrator | Access token, member | [routes/events.py:54](src/photon/orchestrator/routes/events.py#L54) | `events_since` | [6.3](#63-catch-up-without-a-socket) |
| `GET /v1/sessions/{sid}/photos/{sha}?size=` | Image server | Access token, member | [imageserver/routes.py:97](src/photon/imageserver/routes.py#L97) | `download_check` | [6.5](#65-download-a-photo) |
| `POST /v1/sessions/{sid}/photos/{sha}/recall` | Orchestrator | Access token, uploader or host | [routes/photos.py:75](src/photon/orchestrator/routes/photos.py#L75) | `recall` | [7.1](#71-recall-a-photo) |
| `POST /v1/sessions/{sid}/photos/{sha}/report` | Orchestrator | Access token, member | [routes/photos.py:88](src/photon/orchestrator/routes/photos.py#L88) | `session_info` | [7.2](#72-report-a-photo) |
| `DELETE /internal/v1/photos/{sid}/{sha}` | Image server | Orchestrator service token | [imageserver/routes.py:134](src/photon/imageserver/routes.py#L134) | — | [8.1](#81-expiry-sweeper-every-60-s) |
| `GET /healthz`, `/metrics`, `/readyz` | All three | Private network (Caddy refuses `/metrics` and `/readyz` from outside) | [common/httpapp.py:49](src/photon/common/httpapp.py#L49) | `version` (orchestrator `/readyz`) | [2](#2-what-every-request-goes-through) |

---

## 11. Where the code differs from Design.md

The code follows [Design.md](Design.md) closely. These are the places where it goes further than, or differs from, the design text:

- **Routes the design's endpoint table doesn't list.** The design lists the core endpoints. The code also has `GET /v1/auth/config`, `GET /v1/auth/oauth/return` (GitHub's fixed callback), `POST /v1/auth/nonce` and `POST /v1/auth/google` (native Google sign-in), `PUT /v1/devices/current`, the dev login routes, `GET /v1/sessions/current`, `GET /v1/sessions/{sid}`, kick, invite rotation, `POST …/report`, `GET /internal/v1/jwks.json` and the two well-known app-link documents.
- **`HOST_CHANGED` event.** The design's `PhotoEvent` lists five types. The proto adds `HOST_CHANGED = 5`, appended when the host leaves ([photon.lua:108](src/photon/orchestrator/lua/photon.lua#L108)).
- **End-to-end encryption is specified in [e2ee.md](e2ee.md)**, which the code follows. It moved image work from the image server to the phones: the server no longer validates, decodes or thumbnails images, and it serves thumbnails and previews as byte prefixes of the encrypted file. It also added the key relay ([sections 4.9](#49-share-the-session-key-key-maintenance) and [4.10](#410-rotate-the-session-key-after-a-leave-or-kick)), `KEY_SHARED = 6` and `KEY_ROTATED = 7`, and the photo tag. Two choices in it differ from the first sketch of the scheme:
  - Each file has a **random photo key**, locked with the epoch key in the header, rather than a key derived from the epoch key and the photo's hash. Two phones encrypting the same photo then never reuse an AES-GCM key and nonce pair, even though their thumbnails differ.
  - **Dedupe uses the photo tag**, an HMAC under the session's tag key, rather than the hash. Encrypted files of the same photo differ, so their hashes can't match. The server can't compute or test a tag.
- **Upload states on the image server.** Besides the design's `RECEIVING`, `VERIFYING`, `STORED` and `REJECTED`, the code has `REPORTING` (waiting for the orchestrator), and the orchestrator-driven `SUPERSEDED` and `ENDED`. The former `VERIFIED` state, the thumbnail retry point, went with server-side thumbnails.
- **Service-to-service transport.** The design asks for TLS on every hop, with mTLS or a signed service token. The Compose deployment uses plain HTTP between services on the private Docker network (`PHOTON_ORCHESTRATOR_INTERNAL_URL: http://orchestrator:8080`), authenticated by the 60-second service JWTs. Redis connections do use TLS (`rediss://`). Postgres connections in Compose don't set TLS either. If every hop must be encrypted, these internal URLs and the database URL need TLS.
- **Invite links have no server route.** `SessionInfo.invite_url` is `<public base>/j/<token>`, and `/j/*` is declared for App Links and Universal Links, so the app opens it. A browser without the app installed would reach the orchestrator, which has no `/j/` route and answers `404`.
- **iOS ignores `upload_url`.** The protocol gives the upload URL in `AnnounceResponse`; Android uses it, but iOS builds `<configured image base>/v1/uploads/<id>` itself ([section 9.5](#95-upload-pipeline)). This works only while the app's configured image base matches the server's `PHOTON_IMAGE_BASE_URL`.
- **The two apps handle a few answers differently.** Each is a deliberate choice in its own code, but a reader of this document should know the behaviour isn't uniform ([sections 9.1](#91-sign-in), [9.2](#92-token-refresh), [9.6](#96-receiving)):
  - iOS has no native Google sign-in and never reads `/v1/auth/config`.
  - iOS signs the user out on a `403` from refresh.
  - iOS ends a session at once on WebSocket close `4403` or `4410`, while Android first confirms with `GET /v1/sessions/current`.
- **iOS doesn't speak E2EE yet.** It can't create, join or upload against this server until it is updated ([section 9](#9-client-side-flows-android-ios-cli)).
