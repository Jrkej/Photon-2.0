# PHOTON — System Design (v2)

Oct 13, 2026 · @jrke

## Overview

PHOTON syncs photos between the galleries of everyone in a live session: when one member takes a photo, it lands in every other member's gallery with no manual step. The rule that shapes the whole design: **a photo is announced to the session only after the image server has verified its SHA-256 and stored it.** A second rule came with end-to-end encryption: **a photo is encrypted on the phone that took it, and the servers store, verify and serve only files they cannot read** (see [End-to-end encryption](#end-to-end-encryption) and [e2ee.md](e2ee.md)).

**Goals**

- Detect a newly captured photo on the device and sync it while the user is in a session.
- Upload each photo once per session (dedupe by photo tag, a keyed hash of the original).
- Keep photos private from the operator: the servers never see a photo, a thumbnail or a key that opens one.
- Show a thumbnail first, then a preview, then save the original, depending on the network.
- Keep server copies short-lived: a photo is unavailable 30 minutes after it is stored (configurable) and its file is deleted within 5 minutes after that.

**Non-goals for v1**

- Video, Live Photos, and photos taken before joining.
- Long-term storage or backup.
- More than one active session per user.
- A web client, because browsers cannot watch the gallery.

**Constraints**

- Native app, Android first. iOS cannot run continuously in the background, so it relies on push to wake up.
- TLS on every hop, including service-to-service calls.

## Architecture

Five components, with one writer for each kind of state: the orchestrator for sessions and photo status, the image server for the bytes.

&#91;embedded content: architecture · 4 services, 3 stores\]

The client talks to the orchestrator over HTTPS and WebSocket and to the image server over HTTP/3. The image server never tells members anything itself: it reports to the orchestrator (the highlighted arrow), which updates Redis and notifies members. Auth issues the signed JWTs; the orchestrator and image server verify them locally.

| Component | Owns | Never does |
| --- | --- | --- |
| Client | Gallery events, upload queue, local received-hash set, its identity key and session keys, thumbnails, previews, encryption and decryption | Decide what counts as a duplicate |
| Orchestrator | Sessions, members, photo status, event fan-out, the key relay (public keys and sealed keys) | Touch image bytes, or hold a key that opens anything |
| Image server | Encrypted files, serving them and their thumbnail and preview prefixes, deletion | Read a photo, change session state or notify members |
| Auth | The signing key, token issuing | Sit in the path of every request |

## Core flows

Other members hear about a photo only after the image server has verified its SHA-256 and saved it; a mismatch ends in a rejection and nothing is announced.

&#91;embedded content: upload flow · 4 lanes, verify then announce\]

The image server, not the orchestrator, judges the hash. The orchestrator marks the photo `STORED` only when the image server's report arrives carrying the live reservation's `upload_id` while the session is still `ACTIVE`. Any other report is ignored, and the image server deletes that upload's files.

**Encrypt, then announce.** The uploader's app makes the thumbnail (WebP, at most 320 px) and the preview (WebP, at most 1,600 px, or empty when the original is no larger), and encrypts them with the original into one file under the session's current key epoch. The SHA-256 of that encrypted file is the photo's id everywhere: announce, upload token, events, downloads and recall. The announce carries it with `mime = application/vnd.photon.e2ee`, the photo tag and the key epoch ([e2ee.md §7](e2ee.md#7-upload-step-by-step-client)).

**Announce outcomes.** `UPLOAD`: the photo is new (neither its hash nor its photo tag is known in the session), so it is reserved (its bytes are counted against the session quota) and the client gets an upload token. If the same user announces again while their own upload is live, they get the same `upload_id` and a fresh token. `ALREADY_UPLOADED`: the hash or the tag is `STORED` or `EXPIRED` in this session, so the client records it locally and stops. `IN_PROGRESS`: another member's upload of this photo is live, so the client waits for the event. A recalled photo answers `410 PHOTO_RECALLED`, and an ended session answers `410 SESSION_ENDED`. Before any of these, announce checks the key epoch: `409 KEY_ROTATION_REQUIRED` after a member left, `409 KEY_EPOCH_STALE` for a file encrypted under an older epoch, and `403 KEY_REQUIRED` while the uploader holds no key for the current epoch.

**Login.** The client asks `POST /v1/auth/oauth/start` for an authorization URL carrying a fresh `state` and PKCE challenge, opens it in the system browser, and receives the redirect on a registered HTTPS app link. It then posts the `code`, the `state` and its `code_verifier` to `/v1/auth/oauth/callback`. Auth checks that the `state` exists and is unused, that the verifier matches the challenge, and that the redirect URI is the registered one, then exchanges the code with the provider. It finds or creates the user by `(provider, provider_user_id)` and returns an access JWT and a refresh token.

**Join.** The client sends a code or invite token, with its X25519 public key (`member_key`), to `/v1/sessions/join`. One script checks that the session is `ACTIVE` and not full and that the user has no other live session, then adds the member and appends `MEMBER_JOINED` carrying the key. The client opens the WebSocket, loads the snapshot, resumes from its cursor, and starts watching the gallery. It can upload and decrypt once a member's app has sealed the session key for it (`KEY_SHARED`); until then it waits.

**Receive.** On an event the client drops anything it has already processed (by `event_id` and by hash), fetches the thumbnail by hash with its JWT, then the preview or original depending on the network, and decrypts each with the key of the file's epoch. It checks the original against the event's photo tag and saves only the original to the gallery. `PHOTO_RECALLED` removes cached copies; `SESSION_ENDED` stops syncing.

## Protocols and APIs

Every hop uses a standard protocol on top of TCP or QUIC instead of a hand-built TCP protocol, because FastAPI serves HTTP and WebSocket, not raw sockets. One rule runs through every upload-related call: it names its `upload_id`, and the orchestrator applies it only if that id is still the live one for the hash.

| Plane | Protocol | Why |
| --- | --- | --- |
| Control: client to orchestrator | HTTPS, `application/x-protobuf` bodies | FastAPI is an HTTP framework; auth headers and retries come for free |
| Push: orchestrator to client | WebSocket with binary protobuf frames; FCM/APNs only as a wake-up hint | Live events while the app is open; push is best-effort and never carries photo data |
| Data: client and image server | HTTP/3 (QUIC), automatic fallback to HTTP/2 over TCP | Clients fall back via `Alt-Svc` when UDP is blocked; QUIC survives Wi-Fi to cellular switches |
| Internal: image server to orchestrator | HTTPS with mTLS or a service JWT | Only the image server may report a photo as stored or rejected |

The WebSocket at `/v1/ws` authenticates with the access JWT in its first frame, never in the URL, together with the client's `last_event_id`.

**Endpoints**

| Endpoint | Caller | Purpose |
| --- | --- | --- |
| `POST /v1/auth/oauth/start` | client | Create a login attempt; returns the provider URL with `state` and PKCE challenge |
| `POST /v1/auth/oauth/callback` | client | Send code, state and code verifier; get access and refresh tokens |
| `POST /v1/auth/refresh` | client | Rotate the refresh token, issue a new access token |
| `POST /v1/sessions` | client | Create a session with the creator's public key; returns code, invite link, QR payload |
| `POST /v1/sessions/join` | client | Join by code or invite token, with the joiner's public key |
| `POST /v1/sessions/leave` | client | Leave the session |
| `GET /v1/sessions/{sid}/snapshot` | client | Members, live photos, recalled hashes and an event cursor; used on join and on resync |
| `GET /v1/sessions/{sid}/events?since=` | client | Events after an id (used after a push wake-up); `410 RESYNC_REQUIRED` if the id is no longer retained |
| `GET /v1/sessions/{sid}/keys` | client | The current key epoch, whether a rotation is required, members' public keys and newest held epochs, and the caller's own sealed keys |
| `POST /v1/sessions/{sid}/keys` | client | Share sealed keys with members who lack an epoch, or start the next epoch (rotation); the server can open none of them |
| `POST /v1/photos/announce` | client | Send the encrypted file's hash, its photo tag and key epoch, and metadata; get `UPLOAD`, `ALREADY_UPLOADED` or `IN_PROGRESS` |
| `POST /v1/sessions/{sid}/photos/{hash}/recall` | client | Uploader or host stops distribution of a photo |
| `PUT /v1/uploads/{upload_id}/chunks/{n}` | client to image server | Upload one chunk with the upload token |
| `GET /v1/uploads/{upload_id}` | client to image server | Upload state and received chunks, so an upload can resume |
| `POST /v1/uploads/{upload_id}/complete` | client to image server | Idempotent: `202` while verifying, `200` with the stored result on every repeat, the same `422` on repeat after a rejection |
| `GET /v1/sessions/{sid}/photos/{hash}?size=` | client to image server | Download `thumb` or `preview` (byte prefixes of the encrypted file) or `full`; JWT, membership, status and expiry checked on every request |
| `POST /internal/v1/photos/stored` | image server to orchestrator | Hash verified, header checked and file saved; carries `upload_id`; fenced |
| `POST /internal/v1/photos/rejected` | image server to orchestrator | Hash mismatch or invalid encrypted file; carries `upload_id`; fenced |
| `DELETE /internal/v1/photos/{sid}/{hash}` | orchestrator to image server | Delete after expiry, recall or session end; idempotent, retried until acknowledged |

**Error contract.** Every error body is the `Error` message below. The client retries only when `retryable` is true, and waits `retry_after_s` when it is set.

| Status | Code | Meaning | Client action |
| --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | Malformed request or missing chunks (the body lists them) | Fix the request; do not blind-retry |
| 401 | `TOKEN_EXPIRED`, `UNAUTHENTICATED` | Missing, invalid or expired JWT | Refresh once, then retry |
| 403 | `NOT_A_MEMBER`, `FORBIDDEN` | Not in this session, or not allowed to do this | Stop; rejoin if the session still exists |
| 403 | `KEY_REQUIRED` | The caller holds no key for the session's current epoch yet | Wait for `KEY_SHARED`, then try again |
| 404 | `UNKNOWN_UPLOAD`, `PHOTO_NOT_FOUND` | No such upload or photo; also returned to non-members so existence is not leaked | Drop the local task |
| 409 | `ALREADY_IN_SESSION`, `UPLOAD_SUPERSEDED`, `UPLOAD_FINALIZED` | The upload id is no longer the live one, or the upload is already finished | Stop this upload; re-announce only if the photo is still unsynced |
| 409 | `KEY_ROTATION_REQUIRED`, `KEY_EPOCH_STALE`, `EPOCH_TAKEN` | A member left and the key must rotate; the file used an older epoch; another member rotated first | Rotate, or fetch the current key; rebuild the file and announce again |
| 410 | `SESSION_ENDED`, `PHOTO_EXPIRED`, `PHOTO_RECALLED`, `UPLOAD_EXPIRED`, `RESYNC_REQUIRED` | Final: the thing is gone | Clear local state; for `RESYNC_REQUIRED` load the snapshot |
| 413 | `TOO_LARGE` | Over the size limit | Stop; do not retry |
| 415 | `UNSUPPORTED_FORMAT` | The announce isn't of the encrypted type `application/vnd.photon.e2ee` | Stop |
| 422 | `HASH_MISMATCH`, `INVALID_IMAGE` | File rejected: wrong hash, or not a valid encrypted file for the announced epoch | Stop; counts as a strike for the uploader |
| 429 | `RATE_LIMITED`, `TOO_MANY_UPLOADS`, `QUOTA_EXCEEDED` | Limit reached | Retry after `Retry-After`; quota frees as photos expire |
| 503 | `BUSY`, `PROCESSING_FAILED` | Temporary server condition | Retry with backoff and jitter |

**Core protobuf messages.** Never reuse a field number; add new fields only.

```proto
message AnnounceRequest {
  bytes  sha256       = 1;   // of the encrypted file
  uint64 size         = 2;
  string mime         = 3;   // always application/vnd.photon.e2ee
  int64  taken_at_ms  = 4;
  string session_id   = 5;
  bytes  photo_tag    = 6;   // keyed hash of the original, for dedupe
  uint32 key_epoch    = 7;   // must be the session's current epoch
}

message AnnounceResponse {
  enum Result { UPLOAD = 0; ALREADY_UPLOADED = 1; IN_PROGRESS = 2; }
  Result result          = 1;
  string upload_id       = 2;
  string upload_url      = 3;
  string upload_token    = 4;
  uint32 chunk_size_hint = 5;
}

message PhotoStored {          // image server -> orchestrator
  string session_id    = 1;
  bytes  sha256        = 2;
  string upload_id     = 3;
  string uploader_id   = 4;
  uint64 size          = 5;
  string mime          = 6;
  int64  taken_at_ms   = 7;
  int64  stored_at_ms  = 8;
  int64  expires_at_ms = 9;   // set by the image server
}

message PhotoRejected {        // image server -> orchestrator
  string session_id = 1;
  bytes  sha256     = 2;
  string upload_id  = 3;
  string reason     = 4;      // HASH_MISMATCH, INVALID_IMAGE
}

message PhotoEvent {           // orchestrator -> members
  enum Type { PHOTO_STORED = 0; PHOTO_RECALLED = 1; MEMBER_JOINED = 2;
              MEMBER_LEFT = 3; SESSION_ENDED = 4; HOST_CHANGED = 5;
              KEY_SHARED = 6; KEY_ROTATED = 7; }
  string event_id      = 1;    // Redis stream id, increasing per session
  Type   type          = 2;
  string session_id    = 3;
  bytes  sha256        = 4;
  string uploader_id   = 5;
  uint64 size          = 6;
  int64  taken_at_ms   = 7;
  int64  expires_at_ms = 8;
  string member_id     = 9;
  bytes  member_key    = 10;   // MEMBER_JOINED: the member's X25519 public key
  uint32 key_epoch     = 11;   // PHOTO_STORED: the file's epoch; KEY_*: the epoch
  bytes  photo_tag     = 12;   // PHOTO_STORED: receivers check it
}

message Snapshot {
  string cursor                    = 1;  // continue the stream after this id
  repeated PhotoEvent live_photos  = 2;  // STORED and not yet expired
  repeated bytes recalled_sha256   = 3;
  repeated string member_ids       = 4;
  uint32 key_epoch                 = 5;  // the session's current key epoch
}

message Error {
  string code          = 1;
  string message       = 2;
  bool   retryable     = 3;
  uint32 retry_after_s = 4;
}
```

`PhotoEvent` carries no download URL and no key. Receivers fetch by hash with their own JWT, so a leaked event is useless on its own. The key messages (`KeyState`, `KeyUpload`, `SealedKey`, `MemberKey`) are in [proto/photon.proto](proto/photon.proto) and [e2ee.md §5.1](e2ee.md#51-endpoints-and-events). `IN_PROGRESS` means someone else has a live upload of the same hash; the client waits for the event instead of uploading.

## Data model

Redis holds session state, which is short-lived by design; Postgres holds accounts, which must survive a restart. Every state transition is one Lua script that checks, in the same script, that the session is `ACTIVE` and, where relevant, that the caller is a member and that the `upload_id` it names is still the live one for the hash.

| Key | Type | Holds | Lifetime |
| --- | --- | --- | --- |
| `session:{sid}` | hash | Host, status (`ACTIVE` or `ENDED`), created\_at, expires\_at, max\_members, `stored_bytes`, `reserved_bytes`, the current key `epoch`, and `rotate` (a member left, so the next uploader must start a new epoch) | Session expiry, then an `ENDED` tombstone for 1 hour so late callers get `410`, not `404` |
| `session:{sid}:members` | hash | uid to role, joined\_at, the member's public key (`pk`) and the newest key epoch it holds (`ke`) | Session expiry |
| `session:{sid}:photos` | hash | sha256 to status, uploader, upload\_id, size, photo tag, key epoch, reserved\_at, stored\_at, expires\_at | Session expiry |
| `session:{sid}:tags` | hash | Photo tag to sha256, so the same photo in a different file dedupes | Session expiry |
| `session:{sid}:keys` | hash | `epoch:uid` to a sealed key (`box`) and who sealed it (`from`). A member's boxes are deleted when they leave, are kicked, or rejoin from a new install | Session expiry |
| `session:{sid}:events` | stream | Photo, membership and key events; trimmed by age (`MINID`) to retention plus 1 hour | Session expiry |
| `upload:{sid}:{upload_id}` | hash | Uploader, sha256, size, state (`RECEIVING`, `VERIFYING`, `STORED`, `REJECTED`), chunk bitmap | A 15-minute lease, refreshed by every chunk; its absence means the upload is stale |
| `expiry:{n}` | sorted set | `sid:sha256` scored by expires\_at; `n` is a shard from the session id | Entry stays until the image server acknowledges the deletion |
| `user:{uid}:session` | string | Current session id, set with `NX` so a user can be in one session only | Session expiry |
| `code:{code}`, `invite:{token}` | string | Session id for joining | Minutes to hours; host can revoke |
| `oauth:{state}` | hash | Provider, PKCE challenge, redirect URI, nonce | 10 minutes, read with `GETDEL` so it is single-use |
| `refresh:{token_hash}` | hash | uid, token family, rotated\_at | Refresh lifetime |
| `rl:{scope}:{id}` | counter | Rate-limit window | The window |

**Postgres tables:** `users` (id, display\_name, created\_at), `identities` (provider, provider\_user\_id, user\_id; unique on the first two), `devices` (user\_id, platform, push token).

**Redis topology.** Version 1 runs one Redis primary with one replica and no Cluster. Every per-session key carries the `{sid}` hash tag, so a later move to Cluster keeps each script inside one slot. Two things cross slots and are handled explicitly: `user:{uid}:session` (join sets it first with `SET NX`, then runs the session script, and removes it again if the script fails; a pointer to an ended session is ignored and overwritten) and the expiry sorted sets (sharded, each shard swept on its own).

**Photo status in `session:{sid}:photos`**

1. Absent to `UPLOADING`: announce reserves the hash with `HSETNX`, records the `upload_id`, and maps the photo tag to the hash in `session:{sid}:tags`. If the entry is already `UPLOADING` but its `upload:{sid}:{upload_id}` lease is gone and the reservation is older than 60 seconds, the new announce takes over with a new `upload_id`, after releasing the old reserved bytes. A tag that already names another hash answers as that hash would (`ALREADY_UPLOADED`, `IN_PROGRESS` or `410 PHOTO_RECALLED`), except that the uploader's own unfinished reservation is released and replaced: a lost file can only be rebuilt with a new hash, because every file has a random photo key.
2. `UPLOADING` to `STORED`: only on a `stored` report whose `upload_id` and sha256 equal the entry, while the session is `ACTIVE` and the uploader is still a member. In the same script it appends the event, so `STORED` exists exactly when its event does.
3. `UPLOADING` back to absent: on a `rejected` report with the same `upload_id`, when the lease goes stale, or when the session ends. A report with any other `upload_id` changes nothing, so a slow old uploader can never clear a newer reservation.
4. `STORED` to `EXPIRED` (sweeper) or `RECALLED` (uploader or host). Both stay as tombstones until the session ends, so a late announce of the same hash does not re-upload it.
5. Session end: one script sets `ENDED`, tombstones every photo, releases all reservations, appends `SESSION_ENDED`, and queues every file for deletion.

**Quota accounting.** `reserved_bytes` and `stored_bytes` live in `session:{sid}`. Announce checks `stored + reserved + size <= cap` and adds `size` to `reserved` in the same script, so concurrent announces cannot jointly exceed the cap. A `stored` report moves the bytes from reserved to stored. A rejection, a stale reservation, a takeover or a session end releases the reservation. Expiry and recall subtract from stored. Each release happens only on the transition that clears the reservation or status, so no byte is released twice. A periodic job recomputes both counters from the photos hash and corrects drift.

**What losing recent Redis state means.** With `appendfsync everysec` and an asynchronous replica, a crash or failover can lose about the last second of acknowledged writes. Recovery rules:

- Lost join or membership: the next call returns `403 NOT_A_MEMBER` or `410 SESSION_ENDED`; the client rejoins with the code if the session still exists.
- Lost reservation or photo status: the uploader re-announces and gets a new `upload_id`. If the image server already holds a verified file for the same session and sha256, `complete` skips reassembly and re-reports `stored`.
- Lost expiry entries: the image server's own sweeper still deletes at `expires_at`.
- Lost key changes: a lost share is sealed again by the next key maintenance. A lost rotation leaves `rotate` set, so the next uploader rotates again with a fresh key.
- Lost events or a missing stream: clients get `RESYNC_REQUIRED` and load the snapshot.
- Counter drift: corrected by the periodic recount.

Accounts live in Postgres and are not affected.

## Security

Only the Auth module holds the private signing key; every other service verifies tokens locally with the public key and never calls Auth per request.

**Login (OAuth)**

- Backend-mediated Authorization Code with PKCE. `POST /v1/auth/oauth/start` creates `oauth:{state}` (provider, code challenge, redirect URI, nonce) with a 10-minute lifetime and returns the provider URL. The client opens it in the system browser (Custom Tabs or `ASWebAuthenticationSession`), never an embedded webview.
- Redirect URIs are registered per provider and matched exactly: no wildcards, no prefix matching. Use claimed HTTPS app links (Android App Links, iOS Universal Links); a custom scheme is a development fallback only.
- Each login attempt has its own `state` and PKCE verifier, kept by the client in secure storage and discarded after use. The callback reads `oauth:{state}` with `GETDEL`, so a `state` works once. A missing, expired or already used `state`, a verifier that does not hash to the stored challenge, or a redirect URI other than the registered one fails the login.
- For providers with OIDC (Google), also verify the ID token's issuer, audience, expiry and nonce. For the others, read the profile from the provider API with the access token and keep only the provider user id.
- Identify a user by `(provider, provider_user_id)`. Never merge accounts by email: an unverified email on one provider would let someone take over another account.

**Tokens**

- Access JWT: asymmetric signature (EdDSA or RS256), algorithm pinned, `iss`, `aud`, `exp` (15 minutes) and `kid` checked. The public key is published as a JWKS so keys can rotate.
- Refresh token: random, stored hashed, rotated on every use, with reuse detection per token family. Concurrent refreshes are expected, so the client sends one refresh at a time (single-flight), and the server allows a 30-second grace window: a token that was just rotated and is replayed within it gets a new pair in the same family instead of triggering revocation. Replay outside the window revokes the whole family.
- The JWT carries the user id only. Session membership and status are read from Redis, so leaving, being kicked or the session ending takes effect immediately.
- Clients keep tokens in Android Keystore or iOS Keychain.
- The WebSocket authenticates in its first frame. When the access token expires the server closes it with a dedicated code; the client refreshes and reconnects from its last event id.

**Upload token and its invalidation**

The orchestrator mints it on announce: a short-lived JWT with `aud=image-server`, `sid`, `sha256`, `size`, `uploader`, `upload_id` and a 10-minute `exp`. It is only a ticket. On every chunk (membership and status cached for at most 5 seconds) and on every `complete` (never cached), the image server also checks that the lease `upload:{sid}:{upload_id}` exists, the session is `ACTIVE` and the uploader is still a member. So a token stops working when it expires, when the reservation is released or taken over (lease gone), when the upload is finalized (`409 UPLOAD_FINALIZED`), when the uploader is kicked, and when the session ends. Chunks beyond `size` are refused.

**Download authorization**

There are no shareable links. Every `GET /v1/sessions/{sid}/photos/{hash}` request, including each `Range` request and each size, needs the user's JWT and then checks: the session is `ACTIVE`, the user is a member (cache of at most 2 seconds), the photo entry is `STORED`, and the current time is before `expires_at`. Independently, the image server refuses to serve a file past the `expires_at` in its own metadata, so an expired photo cannot be fetched even if Redis state is stale or missing. Responses are `Cache-Control: private, no-store`, so no shared cache can serve a photo after expiry.

**Hash trust**

The client's hash is a claim, not a fact. The image server recomputes SHA-256 over the assembled bytes and stores the file only if it equals the announced hash (see Image pipeline). Otherwise a member could announce hash X, upload other bytes, and make everyone's real photo X look "already uploaded".

With end-to-end encryption the hash is over the encrypted file, and dedupe moved to the photo tag, which the server can't check without the session's tag key. A member could label a file with another photo's tag to block that photo. Receivers recompute the tag from the decrypted original and flag a mismatch ([e2ee.md §6.2](e2ee.md#62-identity-and-dedupe)).

**Joining and leaving**

- Short codes are guessable: use 8 base32 characters, rate-limit joins per user and per IP, and expire the code.
- Invite links carry a random 128-bit token with expiry; the host can revoke. A QR code just encodes the invite link, opened through App Links or Universal Links.
- Roles: host and member. The host can kick, end the session, and set `max_members`.
- Kicking a member is one script: remove the member, release their live reservations, delete their sealed keys, close their socket, append `MEMBER_LEFT`, and require a key rotation. Their in-flight uploads then fail the membership check, and they never receive the next epoch's key.
- If the host leaves, the host role passes to the longest-present member; if nobody is left, the session ends.

**Transport**

TLS 1.3 on every hop. Redis is never exposed publicly: private network, TLS, ACL user per service. Service-to-service calls use mTLS or signed service tokens. Server clocks are NTP-synchronised because `expires_at` is compared across the image server and the orchestrator.

## End-to-end encryption

Photos are encrypted on the uploader's phone and decrypted on members' phones. The orchestrator relays keys it can't open, and the image server stores files it can't read. This section is the design summary; the byte layouts, the sealing construction and the test vectors are in [e2ee.md](e2ee.md).

**What the servers see.** Not the photos, thumbnails or previews, the real image type and dimensions, or any key that opens them. They still see metadata: who is in which session and when, encrypted file sizes, capture times, key epochs, and photo tags (equal tags mean the same photo, without saying which). See [e2ee.md §1](e2ee.md#1-what-it-protects-and-from-whom) and [§10](e2ee.md#10-limits-and-trade-offs).

**Keys**

- Each install has an X25519 identity key pair. The private key never leaves the device. The public key (`member_key`) is sent when creating or joining a session, and `MEMBER_JOINED` carries it to the members.
- A session has epoch keys `K_1, K_2, …` (AES-256) and one tag key `D` that never rotates. The creator's phone makes `K_1` and `D`.
- A sealed box (117 bytes) carries one epoch's key and `D` to one member: X25519 with a fresh ephemeral key, HKDF-SHA256 and AES-256-GCM, bound to the session, the member and the epoch ([e2ee.md §4](e2ee.md#4-sealing-a-bundle-for-one-member)).

**Relaying keys.** Boxes go through `GET` and `POST /v1/sessions/{sid}/keys`. Every client runs *key maintenance* after creating or joining, on `MEMBER_JOINED`, on `KEY_SHARED` for itself, on `KEY_ROTATED`, on a `joined` push and on every app start. It opens the boxes sealed for it, then seals every epoch it holds for each member whose newest epoch is lower. The first sealer wins: a box for a member who already has that epoch is ignored, so concurrent sealers are harmless. Each stored box appends `KEY_SHARED`. A newcomer can't upload or decrypt until a member who holds the key is online; the push `kind=joined` wakes members without a live socket.

**Rotation.** Leaving and kicking set `rotate` whenever members remain, and announce then answers `409 KEY_ROTATION_REQUIRED`. The uploader makes `K_{e+1}`, keeps `D`, seals the new key for every member (itself included, so a crash after the call loses nothing), and posts it with `rotate = true`. The server requires `epoch = current + 1`, or answers `409 EPOCH_TAKEN` (the loser fetches the winner's key), and a box for every other member. A lone remaining member may rotate with no boxes. Success sets every member's epoch, clears `rotate` and appends `KEY_ROTATED`. A member's boxes are deleted when they leave, are kicked, or rejoin from a new install with a new member key, so members seal for them afresh ([e2ee.md §5](e2ee.md#5-relaying-keys-through-the-server)).

**The file.** One file per photo: an 85-byte plaintext header (version, epoch, a random photo key locked under `K_epoch`, and four section lengths), then four AES-256-GCM sections: metadata, thumbnail, preview and original. The whole header is every section's associated data. The image server serves `thumb` and `preview` as byte prefixes of the file, and the receiver decrypts a prefix with the same steps as the whole file ([e2ee.md §6](e2ee.md#6-the-encrypted-photo-file)).

**Dedupe by photo tag.** `tag = HMAC-SHA256(D, "photon/tag/v1" ‖ SHA-256(original))`. The same photo from two phones gives two different files with one tag, and the orchestrator dedupes on the tag in `session:{sid}:tags`.

**A random photo key per file.** An earlier plan derived the photo key from `K` and the photo's hash, so equal photos would produce equal files and dedupe by file hash. Two phones make different thumbnails from the same photo, though, so a derived key with fixed nonces would encrypt different plaintexts under the same key and nonce. That breaks AES-GCM: it leaks the XOR of the plaintexts and allows forgeries. So every file gets a fresh random key, locked in its own header, and dedupe moved to the tag.

**What the servers check** ([e2ee.md §8](e2ee.md#8-what-the-server-checks)). Announce: the encrypted type, a 32-byte tag, `key_epoch` equal to the current epoch, the caller holding it, and no rotation pending. The upload token carries `key_epoch`. On `complete`, the image server checks the hash, then the header: version 1, the token's epoch, sane section lengths, and lengths that add up to the file size. A bad header is `422 INVALID_IMAGE`.

**Limits.**

- The servers can no longer screen images. A member could upload a well-formed file that isn't a valid image, so **receivers decode decrypted images defensively** and treat a failure as a failed photo.
- A report can't include content the server can't read; it records the hash and the reason only.
- Keys rotate when a member leaves or is kicked, not per message; sessions are short and photos expire after 30 minutes. A newcomer also receives the earlier epochs, so it can read live photos taken before it joined.
- Members can compare security codes (a fingerprint of each member key) to confirm the server delivered the real keys ([e2ee.md §9](e2ee.md#9-security-codes)).
- The Python reference client and the Android app implement the protocol. **The iOS app doesn't yet**, so it can't create, join or upload in a session until it is updated.

## Reliability

Every step is idempotent and resumable, so a crash or retry at any point leaves a state the next attempt can finish. Two rules make that safe. **Fencing:** every upload-related state change names its `upload_id`, and the orchestrator applies it only if that id still matches the live reservation. **Atomic checks:** each transition script also checks the session is `ACTIVE`, so nothing can succeed after a session ends.

**Announce and dedupe**

- `announce` is idempotent: the same user and hash in the same session get the same `upload_id` while their reservation lives, with a fresh token.
- The reservation is one Lua script: check the session is `ACTIVE`, check membership, check the key epoch, check the hash and the photo tag, check the live-upload limits, reserve quota, then `HSETNX` the hash. If two members announce the same photo at once (equal tags, different files), one gets `UPLOAD` and the other `IN_PROGRESS` and waits for the event.
- If the uploader dies, its lease key expires and the next announce takes over with a new `upload_id` (see Data model). The old upload's later reports are fenced out.

**Upload**

- The client picks chunk size from measured round-trip time and throughput and sends 2 to 4 chunks in parallel. Each chunk carries a CRC32C; the image server stores chunks by index and refreshes the upload lease, so a repeated chunk is harmless.
- On failure the client asks `GET /v1/uploads/{id}` which chunks arrived and sends only the missing ones. Each chunk is retried up to 3 times with exponential backoff and jitter. After that the photo stays in a persistent device queue and retries when the network returns.

**Completion is idempotent**

The image server keeps the upload's state (`RECEIVING`, `VERIFYING`, `STORED`, `REJECTED`) and writes each change to disk before responding. A repeated `complete` is therefore safe after a timeout:

- Chunks missing: `400` listing the missing indexes.
- Still `VERIFYING`: `202` with `Retry-After`.
- Already `STORED`: `200` with the same result, no reprocessing.
- Already `REJECTED`: the same `422` again.

**Reports to the orchestrator are fenced**

- The image server writes a pending-report record to disk before sending `stored` or `rejected`, and retries until it gets an answer. A crash between saving the file and reporting is recovered at startup from that record.
- The orchestrator answers `200` when it applied the report, `200 NOOP` for a duplicate, `409 UPLOAD_SUPERSEDED` when another `upload_id` now owns the hash, and `410 SESSION_ENDED` when the session is over.
- After a `409` or `410` the image server deletes that upload's files, and only those: all temporary and final paths include the `upload_id` until the file is promoted. A late `rejected` from an old upload can never clear a newer reservation, and a late `stored` can never revive one.

**Session end**

Ending a session is one script: set `ENDED`, tombstone the photos, release reservations, append `SESSION_ENDED`, queue deletions. Because every later join, announce and report script checks `ACTIVE`, a join or upload that races with the end fails with `410` instead of succeeding. The image server also refuses chunks and `complete` for a session that is not `ACTIVE`.

**Events: gap-free catch-up**

- On connect the client sends its `last_event_id`. The server reads the session stream with `XREAD` starting after that id and keeps reading in the same loop, so backlog and live events come from one source. There is no separate replay phase and subscribe phase, so there is no window in which an event can be missed.
- Event ids are stream ids and increase within a session. The client remembers the highest processed id, drops anything at or below it, and also drops photo events for hashes it already holds. Delivery is at-least-once; deduplication makes the effect exactly-once.
- The stream is trimmed by age to the retention window plus 1 hour; an older event cannot matter for a photo that has already expired. If the client's id is older than the oldest retained entry, or the stream is gone after a Redis restore, the server answers `RESYNC_REQUIRED`.
- On resync, and on every first join, the client loads `GET /snapshot`: members, live photos, recalled hashes and a cursor. The server reads the cursor before the state, so anything stored after the cursor is also delivered as an event; duplicates are dropped by hash and event id. The client then continues the stream from the cursor.
- Push notifications (FCM, APNs) are a hint that may be dropped, delayed or merged. On any push, and on every app start, the client runs the same catch-up.

**Session housekeeping**

- Clients heartbeat over the WebSocket. A session ends when the host ends it or after a proposed 12 hours of inactivity.
- A crashed client cannot be stuck in a session: `user:{uid}:session` expires with the session and is ignored once the session is `ENDED`, and the user can leave after restart.

## Guarantees and limits

The server promises ordering and safety. Whether a phone notices a photo, and how fast, depends on the operating system and the network.

| Property | The server guarantees | Best effort only |
| --- | --- | --- |
| Verified before announced | A photo is announced only after the hash of its encrypted file is verified, its header checked and the file saved | Nothing |
| One copy per session | Each hash and each photo tag is stored once per session | Client-side echo prevention; a file labelled with another photo's tag is caught only by receivers |
| Confidentiality | The servers hold no key that opens a photo, a thumbnail or a sealed key | Keys stay safe on members' devices; members compare security codes to rule out a substituted key |
| Key rotation | After a leave or kick, no photo is accepted until a member starts an epoch the departed member never receives | Photos from earlier epochs stay readable to whoever held that epoch |
| Event delivery | Events are appended to a durable stream and delivered at-least-once; any client can recover fully from the snapshot | Live push over WebSocket and FCM or APNs |
| Detecting a new photo | Nothing | Android while the app may run; iOS only while the app is alive or on its next launch |
| Latency | None promised | Typically as fast as the network allows |
| Retention of 30 minutes | Logical: from `expires_at` no download succeeds. Physical: files are deleted within 5 minutes after, and an alert fires at 15 minutes | Nothing |
| Recall | The server stops serving the photo at once and tells members | Offline devices delete cached copies when they next connect; originals already saved to a gallery are not deleted |
| Session end | No join, announce, upload completion or report succeeds afterwards | Cleanup on devices |
| Redis failure | At most about 1 second of writes lost; recovery rules in Data model | Photos in that second may need to be re-announced |

## Image pipeline and retention

The image server saves a file only after its SHA-256 matches the announced hash, and it tells the orchestrator only after the file is saved and its metadata is written. Until then the photo does not exist for anyone else in the session.

**Accepted files**

- One type only: the encrypted photo file, announced as `application/vnd.photon.e2ee`. The image server reads only its plaintext header and never decodes an image; it has no image code at all.
- Limit (proposed): 25 MB per file.
- The uploader's app checks the photo before encrypting it: JPEG, PNG, WebP or HEIC/HEIF, static images only, at most 100 megapixels, with the declared type matching the magic bytes. It also makes the thumbnail and preview (EXIF orientation applied, no metadata). Receivers decode what they decrypt defensively, because no server screened it.

**Temporary storage and concurrency**

- All temporary files live under `tmp/{upload_id}/`, so cleanup of one upload cannot touch another.
- Assembly streams each chunk into the output file while hashing it, and deletes the chunk once consumed, so peak extra disk is about one photo, not two.
- Limits: at most 3 live uploads per user and 20 per session (counted from reservations at announce), plus a configured temporary-disk budget per image server. When the budget or the concurrency limit is reached, chunk uploads get `503 BUSY` with `Retry-After`.

**When the client calls `complete`**

1. Check the lease, that the session is `ACTIVE` and that the uploader is still a member, all uncached. Record the state `VERIFYING` on disk. If the state is already final, return the stored result (see Reliability).
2. Assemble the chunks into a temporary file. Check that every index is present and the total equals the announced size. Stream SHA-256 over it and compare with the `sha256` in the upload token.
3. On a mismatch: delete the temporary files, record `REJECTED`, return `422 HASH_MISMATCH`, and report `rejected` with the `upload_id`. The orchestrator clears the reservation (if the id is still live), releases the quota and counts a strike against the uploader. Nothing is announced.
4. On a match: parse the file's 85-byte header. It must be version 1, carry the epoch named in the upload token, have sane section lengths, and have lengths that add up to the file size. A failure is handled like step 3, with `INVALID_IMAGE`.
5. Move the file to its final path with an atomic rename and fsync. If a verified file for the same session and sha256 already exists (for example after a Redis loss), reuse it instead of storing a second copy.
6. Write the metadata file next to the file (sha256, size, uploader, key epoch, header length and section lengths, `stored_at`, `expires_at = stored_at + retention`) and fsync it. Then write the pending-report record and send `stored` with `upload_id`, `stored_at` and `expires_at`. The orchestrator adopts the image server's `expires_at`.

A photo can't be stored without a thumbnail, because the header check refuses an empty thumbnail section. Downloads serve the stored file as `application/octet-stream`: `full` is the whole file (with `Range`), and `thumb` and `preview` are its first bytes up to the end of that section, computed from the lengths in the metadata ([e2ee.md §6.3](e2ee.md#63-serving-sizes-without-decrypting)). Nothing is generated on request. The original keeps its EXIF unless the client stripped location before upload (the default, see Client); the thumbnail and preview never carry metadata.

**Retention: what 30 minutes means**

- Logical expiry is exact. From `expires_at` no download succeeds: the orchestrator's status says `EXPIRED`, and the image server independently refuses to serve a file past the `expires_at` in its own metadata, even if Redis disagrees.
- Physical deletion is best effort but bounded: the target is within 5 minutes after `expires_at`, and an alert fires when a file outlives it by 15 minutes.
- The orchestrator's sweeper runs every minute per shard under a Redis lock. For each due entry it marks the hash `EXPIRED`, subtracts the stored bytes, and calls `DELETE /internal/v1/photos/{sid}/{hash}`. The entry leaves `expiry:{n}` only when the image server acknowledges the deletion; failures are retried with backoff. A hash marked `EXPIRED` while its file still exists is therefore safe, because downloads are refused.
- The image server runs its own sweeper every minute over its local metadata files and deletes anything past `expires_at`, whether or not the orchestrator ever asks. This is the backstop for lost Redis state and failed deletes.
- Recall and session end use the same deletion path with `expires_at` set to now.

**Orphans**

The image server removes: files with metadata, at their `expires_at`; files without metadata, once older than 1 hour; and temporary directories whose upload lease is gone for more than 15 minutes or whose session is `ENDED`. Each rule keys on the `upload_id` or the file's own metadata, never on Redis alone, so losing Redis state cannot make valid files vanish early or leave dead ones forever. The orchestrator separately drops expiry entries and photo tombstones when their session ends.

Deleting the server copy never touches photos already saved in members' galleries.

## Client

The client must be a native app, because only the operating system can tell it that a new photo exists. Detection is best effort on every platform; the server-side guarantees are listed under Guarantees and limits.

**Android: who does what**

- The observer only notices. A `MediaStore` ContentObserver, kept alive by a foreground service with a visible notification while a session is active (Android 8 and later kill plain background services), writes `(media_id, date_added, uri)` to a local `pending_photos` table and enqueues work. It does no hashing and no network calls.
- WorkManager owns everything slow: thumbnails, encryption, hashing, announce, chunked upload, retries and backoff. Jobs are persisted, wait for the network, and survive the app being killed.
- A reconcile scan covers what the observer missed. On session start, app start, service restart and after boot, it queries `MediaStore` for images taken after the join time and after the last scan cursor that are not already pending, synced or received, and enqueues them.

**iOS: no promise while suspended**

The iOS app doesn't implement end-to-end encryption yet, so until it is updated it can't create, join or upload in a session (see End-to-end encryption). What follows is its design once it does. `PHPhotoLibraryChangeObserver` works only while the app is alive. While the app is suspended nothing is synced and nothing is guaranteed. On the next launch or return to the foreground the app runs the same reconcile scan (assets created after the join time that it has not synced or received) and then the snapshot catch-up. A silent push may wake the app briefly but is never relied on. Photos taken while suspended reach the others late, as soon as they are uploaded and verified.

On both platforms only photos with a capture time after the join time and before the session end are eligible. Screenshots and downloads are skipped by filtering on the camera folder and capture time.

**Preventing the echo loop**

A received photo saved to the gallery fires the same observer, and a hash alone is not reliable: the operating system may re-encode on save (HEIC conversion, EXIF edits), which changes the saved copy's bytes. So the client uses several layers, in this order:

1. Asset identity: every photo the app saves is recorded by its `MediaStore` id or `PHAsset` identifier in a `received_assets` table, and events and scans skip those ids.
2. Location: received photos are saved to a dedicated PHOTON folder (Android) or album (iOS), and the observer and scan skip that location.
3. Owner: on Android, ignore rows whose owner package is the app itself.
4. Hash set: a local set of received and uploaded hashes and photo tags, as a last local check only.
5. Server: the session-wide dedupe by photo tag, which still catches the same photo from any member.

**Receiving**

- Show the thumbnail as soon as the event arrives, then the preview, then the original, depending on network quality. Each is decrypted on the device; a small original has no separate preview, so the client fetches the original instead. Thumbnails and previews live in the app cache only. Save just the original to the gallery, otherwise each photo appears three times.
- Decode decrypted images defensively (size and pixel limits, a decoder failure marks the photo failed), and check the original against the event's photo tag before saving it.
- Fetch originals on Wi-Fi or while charging when the user chooses that setting. Keep the original capture time on the saved file so it lands in the right place in the timeline.
- Process events idempotently: drop an `event_id` already seen and a hash already held. Recovery after a missed push, a reconnect or a restart is the catch-up described in Reliability.

**Privacy controls**

Auto-sharing everything someone shoots is the largest product risk, so the client needs:

- A visible indicator while syncing is on, and a pause switch.
- Location (GPS) removed from the original before upload, by default.
- A recall action for the uploader and the host.
- Clear text on join that photos taken during the session are shared with its members.

**What recall does and does not do.** Recall stops distribution. The server refuses further downloads at once and appends `PHOTO_RECALLED`. Online members delete their cached thumbnail and preview when the event arrives; offline members do so when they reconnect, because the snapshot lists recalled hashes. Recall cannot reach a member who never reconnects, and the app does not delete originals a member has already saved to their gallery, because it has no right to remove the user's own photos. The recall dialog says this in plain words.

## Abuse, quotas and operations

Without limits, PHOTON becomes free file hosting. These are proposed defaults to tune once real usage is known.

| Limit | Proposed default | Enforced by |
| --- | --- | --- |
| Maximum photo size | 25 MB per encrypted file; 100 megapixels | Upload token `size` and the image server's header check; the pixel limit is checked by the apps, because the servers can't see pixels |
| Members per session | 10 | Orchestrator, on join |
| Bytes per session | 1 GB of stored plus reserved bytes | Orchestrator, reserved at announce, converted on `stored` |
| Live uploads | 3 per user, 20 per session | Orchestrator, counted from reservations at announce |
| Temporary disk | A configured budget per image server | Image server; over budget answers `503 BUSY` |
| Announces per user | 20 per minute | Orchestrator, rate limiter in Redis |
| Join attempts | 5 per minute per user and per IP | Orchestrator |
| Hash-mismatch strikes | 3, then uploads blocked for that session | Orchestrator, on `rejected` |

**Abuse handling.** Members can report a photo and the host can kick a member. Short retention limits how long anything stays on the server. The servers can't see photo content, so a report records only the hash and the reason; a reporter's app would have to attach the decrypted photo, which this version doesn't do. Illegal-content reporting and takedown rules need a legal review before any public launch, and that review must account for end-to-end encryption.

**Observability**

- Structured logs with a request id passed through orchestrator and image server.
- Deletion: failed deletions, deletion lag (time between `expires_at` and the file being gone), files still on disk past `expires_at`, orphan files found by the sweeper, and unacknowledged `stored` and `rejected` reports.
- Uploads: stale reservations and takeovers, `UPLOAD_SUPERSEDED` and `SESSION_ENDED` answers to reports, temporary disk usage, quota rejections, `BUSY` responses, and time spent in `VERIFYING`.
- Delivery: announce results, upload duration p50 and p95, time from `stored` to event delivery, open WebSockets, `RESYNC_REQUIRED` rate, hash mismatches.
- Redis: replication lag, AOF fsync delay, memory, and the size of any quota-counter correction.
- Alerts: a file past `expires_at` by 15 minutes, a growing backlog of unacknowledged reports, temporary disk above its threshold, a rising deletion failure rate, and a rising mismatch rate.

**Scaling**

- The orchestrator is stateless: all state is in Redis and Postgres, so run several behind a load balancer. Gateways need no sticky routing because events come from the Redis stream.
- Version 1 uses a single Redis primary with a replica. Keys are already built with `{sid}` hash tags so Cluster is possible later (see Data model).
- With one image server, local disk is enough. With several, either route all chunks of one upload to the same instance (hash on `upload_id`) or move storage to S3-compatible object storage using the same key layout; the metadata files and the local sweeper move with it.

## Changes from v1

Thirteen changes, the first being the one you asked for: a hash is trusted only after the image server verifies it.

| # | v1 | v2 | Why |
| --- | --- | --- | --- |
| 1 | Orchestrator trusted the client's hash | Image server verifies SHA-256 after assembling, saves only on a match, then reports `stored`; the orchestrator announces only after that | A client can claim any hash; unverified trust poisons dedupe |
| 2 | Client talked to a FastAPI orchestrator over raw TCP | HTTPS for requests, WebSocket for push | FastAPI serves HTTP and WebSocket, not raw sockets |
| 3 | QUIC with a hand-built TCP fallback | HTTP/3 with automatic HTTP/2 fallback | Standard clients already do this fallback |
| 4 | Notification carried a download link | Event carries the hash only; download needs JWT plus membership | A shareable link contradicts the authorization rule |
| 5 | Redis for everything, described as a text database | Redis for session state, Postgres for accounts | Redis is an in-memory store; accounts must be durable |
| 6 | Web frontend implied | Native app, Android first | Browsers cannot watch the gallery |
| 7 | Received photos could re-trigger upload | Local received-hash set and own-package filter | Saving to the gallery fires the same observer |
| 8 | Thumbnail, preview and original all saved to gallery | Only the original is saved; the rest stay in cache | Avoids three copies and extra observer events |
| 9 | Cron every 20 minutes deleted images older than 30 | Per-photo expiry swept every minute; status marked `EXPIRED` before deletion | Old scheme kept files up to 50 minutes and could leave hashes pointing at deleted files |
| 10 | Image server asked the orchestrator for a signed link | Orchestrator mints a signed upload token, verified locally | Removes one round trip and one point of failure |
| 11 | Image server notified all users directly | Image server reports to the orchestrator, which owns session state and fan-out | One writer of session state; events can be replayed from a stream |
| 12 | JWT could stand in for membership | JWT holds the user id only; membership read from Redis | Leaving a session takes effect immediately |
| 13 | Retry up to 3 times and start over | Resumable chunks, backoff, persistent device queue | A retry should not resend finished chunks |

Added in the first review: fencing of every upload report by `upload_id`; session status checked in every transition; gap-free event catch-up with snapshot resync; expiry enforced on the image server with its own sweeper and retried deletions; Redis topology (standalone for v1, `{sid}` hash tags); atomic quota reservation; idempotent `complete`; recall semantics; thumbnail failure recovery; an error contract; OAuth state and redirect validation; refresh-token grace window; layered echo prevention; and a table of server guarantees against best-effort client behaviour. The last section maps every review finding to where it is handled.

Added later: end-to-end encryption ([e2ee.md](e2ee.md)). Photos are encrypted on the phone, keys are relayed as sealed boxes, and the key rotates on every leave and kick. The image server lost its decoder, thumbnails and previews, and dedupe moved from the file hash to the photo tag.

## Build order and open decisions

Build the server path first and test it with a script client; the Android app then plugs into a path that already works. Write the concurrency tests for fencing and quota before the client exists.

1. Auth and orchestrator: OAuth start and callback, JWTs, and session create, join, leave and end in Redis, with the `ACTIVE` check inside every script.
2. Image server: chunked upload, idempotent `complete`, hash verification, metadata files, and the `stored` and `rejected` reports with the on-disk outbox.
3. Announce, dedupe, quota reservation and fencing by `upload_id`, with tests that replay stale reports, race a session end, and announce concurrently.
4. Events: the stream, the WebSocket with `last_event_id`, snapshot and resync.
5. Retention: the orchestrator sweeper, the image server sweeper, and deletion monitoring.
6. Android client: observer, WorkManager upload, reconcile scan, echo layers, thumbnail-first receiving, recall.
7. Rate limits and privacy controls, then HTTP/3, push as a hint, and iOS.
8. End-to-end encryption: the key relay and rotation, the encrypted file, prefix downloads, dedupe by tag; then the reference client and Android. iOS still needs it.

**Decisions to confirm (the design uses these defaults)**

- [ ] Platform: Android first, with iOS later
- [ ] Retention: 30 minutes of availability from stored\_at, physical deletion within 5 minutes after; ending a session deletes everything
- [ ] Strip GPS from originals by default (proposed: yes)
- [ ] Size limits: 25 MB and 100 megapixels per photo, 1 GB per session; JPEG, PNG, WebP and HEIC only (the type list and pixel limit are now enforced by the apps)
- [ ] Session capacity: 10 members, 3 live uploads per user, 20 per session

## Review findings and where each is handled

Findings 1 to 32 came from the design review; 33 to 39 were found while fixing them.

| # | Finding | How it is handled | Section |
| --- | --- | --- | --- |
| 1 | Stale upload report clears a newer reservation | Every report names `upload_id`; applied only if it matches the live one; otherwise `409` and the old files are deleted | Reliability, Data model |
| 2 | Join or upload succeeds after session end | Every transition script checks `ACTIVE`; ending is one atomic script | Reliability |
| 3 | Events missed between replay and live | One `XREAD` loop from `last_event_id`; ids and hashes deduplicate | Reliability |
| 4 | `EXPIRED` in Redis while the file still exists | Image server enforces `expires_at` itself, runs its own sweeper, and deletions retry until acknowledged | Image pipeline and retention |
| 5 | Lua across hash slots in Redis Cluster | Standalone Redis for v1; `{sid}` hash tags; cross-slot keys use compensation | Data model |
| 6 | `complete` retried after storage | Persisted upload state; repeats return the same result | Reliability |
| 7 | Concurrent uploads exceed the quota | Bytes reserved atomically at announce | Data model |
| 8 | Recall cannot reach offline devices | Server stops serving at once; clients delete caches on reconnect via the snapshot; limits stated | Client, Guarantees and limits |
| 9 | Stream trimmed before a client returns | `RESYNC_REQUIRED` and a snapshot with a cursor | Reliability, Protocols and APIs |
| 10 | Thumbnail generation fails after storage | No longer possible: the uploader's app makes the thumbnail and encrypts it into the file, and the header check refuses a file without one | Image pipeline and retention, End-to-end encryption |
| 11 | Cleanup of stale uploads hits newer ones | All paths and leases keyed by `upload_id` | Image pipeline and retention, Data model |
| 12 | Resource exhaustion from temporary files | Live-upload limits, disk budget, streaming assembly, `503 BUSY` | Image pipeline and retention, Abuse |
| 13 | Expired photo downloadable through stale state | Checks on every request, plus the image server's own metadata and `no-store` | Security |
| 14 | Files whose Redis metadata is gone | Local metadata files and orphan rules independent of Redis | Image pipeline and retention |
| 15 | Membership and status not checked everywhere | Checked on announce, chunks, `complete`, reports and downloads | Security, Reliability |
| 16 | Observer and WorkManager roles blurred | Observer only enqueues; workers do the rest; reconcile scan | Client |
| 17 | iOS cannot sync while suspended | Stated; reconcile scan and snapshot on next run | Client, Guarantees and limits |
| 18 | Push treated as reliable | Push is a hint; every wake-up runs catch-up | Reliability, Guarantees and limits |
| 19 | Re-encoding changes the hash | Five layers; asset id, folder, owner package come before hash | Client |
| 20 | Recall cannot delete saved originals | Stated in the recall dialog and the guarantees table | Client, Guarantees and limits |
| 21 | OAuth redirect and state validation | Exact redirect URIs; single-use `state` and PKCE per attempt | Security |
| 22 | Image validation underspecified | The servers can't see images: the image server checks the encrypted file's header; the apps enforce the type list and pixel limit, and receivers decode defensively | Image pipeline and retention, End-to-end encryption |
| 23 | Upload token invalidation | Lease and session checks on every chunk and `complete` | Security |
| 24 | No error contract | Status and code table, `Error` message, retry rules | Protocols and APIs |
| 25 | Quota accounting | Reserve, convert and release exactly once per transition | Data model |
| 26 | Concurrent refresh revokes a family | Client single-flight and a 30-second server grace window | Security |
| 27 | Meaning of 30 minutes | Logical expiry exact; physical deletion within 5 minutes | Guarantees and limits, Image pipeline and retention |
| 28 | Deletion monitoring | Lag, failures, orphans and overdue files with alerts | Abuse, quotas and operations |
| 29 | Upload monitoring | Stale reservations, disk, quota and `BUSY` metrics | Abuse, quotas and operations |
| 30 | Server guarantees mixed with best effort | Separate columns in one table | Guarantees and limits |
| 31 | Redis durability consequences | Recovery rule for each kind of lost state | Data model |
| 32 | Open decisions | Defaults chosen and listed to confirm | Build order and decisions |
| 33 | Chunks or `complete` arriving after finalization | `409 UPLOAD_FINALIZED` | Security, Protocols and APIs |
| 34 | Token in the WebSocket URL, expiry mid-connection | First-frame auth; close code, refresh, reconnect from last id | Security |
| 35 | Kicked member's uploads and socket | One kick script releases reservations and closes the socket | Security |
| 36 | Host leaves the session | Host role passes on; session ends if empty | Security |
| 37 | Clock differences decide expiry | The image server sets `expires_at`; NTP everywhere | Image pipeline and retention, Security |
| 38 | Verified file exists but Redis lost the reservation | Reuse the existing file and re-report `stored` | Image pipeline and retention, Data model |
| 39 | Recalled photo announced again | `410 PHOTO_RECALLED` | Core flows, Protocols and APIs |