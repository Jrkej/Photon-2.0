# PHOTON — End-to-End Encryption (version 1)

Photos are encrypted on the uploader's phone and decrypted on the receivers' phones. The server stores only encrypted files and sealed keys it cannot open. This document is the exact specification every client implements (Python reference client, Android, and later iOS): the keys, how they are sealed and relayed, the encrypted file format, and the rules the server enforces.

The reference implementation is [src/photon/common/e2ee.py](src/photon/common/e2ee.py). Every implementation must reproduce the fixed test vectors in [tests/vectors/e2ee_v1.json](tests/vectors/e2ee_v1.json) byte for byte (regenerate them with `python tools/e2ee_vectors.py`).

## 1. What it protects, and from whom

| Party | Can see | Cannot see |
| --- | --- | --- |
| The server (Auth, orchestrator, image server, Redis, disk) | Who is in a session, members' **public** keys, when photos are uploaded, encrypted file sizes, capture times, photo tags | Any photo, thumbnail or preview; image type or dimensions; any session key |
| A member | Every photo encrypted with an epoch key they hold | Photos from epochs after they left |
| Someone who left or was kicked | Photos they already received | Photos uploaded after the next key rotation; the server also refuses them downloads at once |

**The server is trusted to relay, not to read.** Against a server that is curious but follows the protocol, the keys never leave the phones. A server that actively lies could hand members a fake public key or add a fake member, and the members' apps would then seal the session key for it. Encryption alone can't detect that: it is the same limit WhatsApp has. Two things expose it: every join appears in the app, and members can compare **security codes** ([section 9](#9-security-codes)).

## 2. Primitives

X25519 (RFC 7748) · HKDF-SHA256 (RFC 5869) · HMAC-SHA256 · AES-256-GCM with 12-byte nonces and 16-byte tags · SHA-256. Integers are unsigned big-endian. `‖` is concatenation.

## 3. Keys

| Key | Size | Made by | Lives | Purpose |
| --- | --- | --- | --- | --- |
| **Member key pair** | 32 + 32 bytes | Each phone, once per install | Private half: only on the phone (encrypted at rest). Public half (`member_key`): sent with create and join | Receiving sealed session keys |
| **Epoch key** `K_e` | 32 random bytes | The creator for epoch 1; the rotating member for each later epoch | On members' phones; on the server only inside sealed boxes | Locks each photo's key |
| **Tag key** `D` | 32 random bytes | The creator, once per session; never rotated | Inside every sealed bundle | Computes photo tags for dedupe |
| **Photo key** | 32 random bytes | The uploader, fresh for every file | Inside the file, locked with `K_e` | Encrypts that one file's sections |

**Epochs.** A session starts at epoch 1. When a member leaves or is kicked, the next uploader must start a new epoch with a fresh `K_e` ([section 5.4](#54-rotation-after-a-member-leaves)), so a departed member can't read new photos. Each file records its epoch, so receivers pick the right key.

**Key bundle** (what a sealed box carries), 69 bytes:

```
offset  size  field
0       1     version = 1
1       4     epoch
5       32    K_e
37      32    D
```

## 4. Sealing a bundle for one member

`seal(bundle, recipient member_key R, session id sid, recipient member id uid)`:

1. Generate a one-time X25519 key pair `(e, E)`.
2. `shared = X25519(e, R)`. Reject an all-zero result.
3. `key = HKDF-SHA256(ikm = shared, salt = E ‖ R, info = "photon/seal/v1", length 32)`.
4. `aad = "photon/seal/v1|" ‖ sid ‖ "|" ‖ uid ‖ "|" ‖ decimal(epoch)` (UTF-8). This binds the box to one session, one recipient and one epoch, so the server can't replay it elsewhere.
5. `sealed = AES-256-GCM(key, nonce = 12 zero bytes, plaintext = bundle, aad)`. A fixed nonce is safe because every box uses a fresh one-time key.
6. **Box** = `E (32) ‖ sealed (69 + 16)` = **117 bytes**.

Opening reverses it with the recipient's private key `r`: `shared = X25519(r, E)`, the same HKDF with `salt = E ‖ public(r)`, then decrypt with the same `aad`, and check that the bundle's epoch equals the box's epoch.

## 5. Relaying keys through the server

The orchestrator stores public keys and sealed boxes and notifies members. It never sees `K_e` or `D`.

### 5.1 Endpoints and events

| Call or event | What it carries |
| --- | --- |
| `POST /v1/sessions` `{max_members, member_key}` | The creator's public key. The creator already holds epoch 1 (it made `K_1` and `D`). |
| `POST /v1/sessions/join` `{code or invite_token, member_key}` | The joiner's public key; the joiner holds no epoch yet |
| `MEMBER_JOINED` event | `member_id` and `member_key`: the cue for members to seal for the newcomer |
| `GET /v1/sessions/{sid}/keys` → `KeyState` | The current `epoch`, `rotation_required`, every member's `member_key` and newest held epoch (`key_epoch`), and the caller's own sealed boxes |
| `POST /v1/sessions/{sid}/keys` `KeyUpload{epoch, rotate, keys[]}` → `KeyUploadResult` | Sealed boxes for other members (sharing), or a new epoch (rotation) |
| `KEY_SHARED` event | `member_id` (the recipient) and `key_epoch`: a box is waiting for them |
| `KEY_ROTATED` event | `member_id` (who rotated) and `key_epoch` (the new epoch): everyone fetches their new box |
| Push `kind=joined` | Wakes members without a live socket so they can seal for the newcomer |

### 5.2 Server rules for `POST /keys`

- The caller must be a member of an active session.
- **Sharing** (`rotate = false`): `1 ≤ epoch ≤ current`, and the caller must hold at least that epoch. Each box needs a recipient who is a current member, and must be exactly 117 bytes. A box for a member who already has one for that epoch is ignored, so the first sealer wins and concurrent sealers are harmless. Each recipient's `key_epoch` rises to the epoch shared, and one `KEY_SHARED` event per recipient is appended.
- **Rotation** (`rotate = true`): `epoch` must be exactly `current + 1`, or the answer is `409 EPOCH_TAKEN` (another member rotated first). The caller must hold some epoch (so it knows `D`), and the request must include a box for **every other member** (`400` otherwise). It may also include one for the caller, and the reference client always does: a rotator that crashes right after the `POST` still finds its new key in `GET /keys`. A last remaining member rotates with its own box only, or none. The server stores the boxes, makes `epoch` current, clears `rotation_required`, sets every member's `key_epoch` to it, and appends `KEY_ROTATED`.
- Leaving and kicking set `rotation_required` whenever members remain.
- A member's boxes are deleted when they leave or are kicked, and when they join again with a different member key (a new install: the join answers `REKEYED`, resets their `key_epoch` to 0 and appends `MEMBER_JOINED` with the new key). Old boxes sealed to a key they no longer have would otherwise block new ones, since the first sealer wins.

### 5.3 What every client does ("key maintenance")

Run it after creating or joining, on `MEMBER_JOINED`, on `KEY_SHARED` for yourself, on `KEY_ROTATED`, on a `joined` push, and on every app start or catch-up:

1. `GET /keys`.
2. Open every sealed box addressed to you; keep each bundle by epoch. Ignore a box that doesn't open.
3. If you hold the current epoch: for every other member whose `key_epoch` is below it, seal **every epoch you hold** above their `key_epoch`, and `POST` them (`rotate = false`). This also lets a newcomer read photos from earlier epochs that are still live.

A newcomer can't upload or decrypt until some member's app has done step 3. Until then the app shows "waiting for a member to share the key", as WhatsApp shows "Waiting for this message".

### 5.4 Rotation after a member leaves

When `rotation_required` is true, the server refuses announces with `409 KEY_ROTATION_REQUIRED`. The uploader then:

1. Makes `K_{e+1}` (32 random bytes), keeping the session's `D`.
2. Seals the new bundle for every current member, itself included, using the member keys from `GET /keys`.
3. `POST /keys` with `{epoch: e+1, rotate: true}`. On `409 EPOCH_TAKEN`, discards its own new key, runs key maintenance to fetch the winner's box, and continues with that.
4. Re-encrypts the photo with the new epoch and announces again.

## 6. The encrypted photo file

A photo is uploaded as one file holding four encrypted sections. The server can read only the header.

### 6.1 Layout

```
offset  size  field
0       4     magic "PHE1"
4       1     version = 1
5       4     epoch
9       12    wrap nonce (random)
21      48    locked photo key = AES-256-GCM(K_epoch, wrap nonce, photo key, aad = bytes 0–20)
69      4     meta length       (ciphertext, including its 16-byte tag)
73      4     thumbnail length
77      4     preview length
81      4     original length
85            header ends; sections follow in this order: meta, thumbnail, preview, original
```

Each section `i` (meta 0, thumbnail 1, preview 2, original 3) is `AES-256-GCM(photo key, nonce = i as a 12-byte big-endian integer, plaintext, aad = the 85-byte header)`. Using the whole header as AAD binds every section to its epoch, its locked key and its neighbours' lengths. The photo key is fresh for every file, so no key and nonce pair ever repeats, even when two phones encrypt the same photo.

| Section | Plaintext |
| --- | --- |
| meta | UTF-8 JSON with sorted keys and no spaces: `{"height":…,"mime":…,"taken_at_ms":…,"v":1,"width":…}`. `mime` is one of JPEG, PNG, WebP, HEIC, HEIF, detected from the file's magic bytes. `width` and `height` are after EXIF orientation, as a viewer shows the photo. At most 4,096 bytes. |
| thumbnail | WebP, longest side at most 320 px, EXIF orientation applied, converted to sRGB, no metadata. Never empty. |
| preview | WebP, longest side at most 1,600 px, made the same way. Empty when the original's longest side is 1,600 px or less: a receiver asking for `size=preview` then gets no preview section and fetches the original instead. |
| original | The photo as the user took it, with GPS removed. Never empty. |

### 6.2 Identity and dedupe

- **File hash** `sha256 = SHA-256(whole file)`. This is the photo's id everywhere: announce, upload token, events, downloads, recall. The image server verifies it over the bytes it receives.
- **Photo tag** `= HMAC-SHA256(D, "photon/tag/v1" ‖ SHA-256(original))`. Two members uploading the same photo produce different files (different photo keys and thumbnails) but the same tag. The orchestrator dedupes by tag: a second announce of the same tag answers `ALREADY_UPLOADED`, `IN_PROGRESS` or `410 PHOTO_RECALLED`, exactly as the plaintext hash did before. The server can't compute or test a tag without `D`.
- **Receivers check the tag.** After decrypting an original, a receiver recomputes the tag and compares it with the event's `photo_tag`. A mismatch means the uploader lied to block another photo; the receiver flags it.

### 6.3 Serving sizes without decrypting

The header's lengths tell the image server where each section ends, so `GET …/photos/{sha}?size=` serves byte prefixes:

| `size` | Bytes served | Contains |
| --- | --- | --- |
| `thumb` | `0 … end of thumbnail` | header, meta, thumbnail |
| `preview` | `0 … end of preview` | header, meta, thumbnail, preview |
| `full` | the whole file (`Range` supported) | everything |

A receiver decrypts any of these prefixes with the same steps: parse the header, unlock the photo key with `K_epoch`, decrypt meta and whatever sections are present.

## 7. Upload, step by step (client)

1. Remove GPS from the photo, as before (the apps do; the Python reference client sends files as they are).
2. Check the image: a known type whose magic bytes match the declared one, at most 100 million pixels, one frame, and decodable. The server can no longer check this, so the uploader does, and refuses to send a file that fails (`INVALID_IMAGE` in the reference client).
3. Make sure you hold the current epoch (key maintenance).
4. Compute the photo tag from the original.
5. Make the thumbnail and preview, then build the file with the current epoch.
6. `sha256 = SHA-256(file)`; announce `{sha256, size, mime: "application/vnd.photon.e2ee", taken_at_ms, photo_tag, key_epoch}`.
7. Upload chunks and complete as before.

| Announce answer | What the client does |
| --- | --- |
| `409 KEY_ROTATION_REQUIRED` | Rotate ([section 5.4](#54-rotation-after-a-member-leaves)), rebuild the file, announce again |
| `409 KEY_EPOCH_STALE` | Run key maintenance, rebuild with the current epoch, announce again |
| `403 KEY_REQUIRED` | No key for the current epoch yet: wait for `KEY_SHARED` |
| `415 UNSUPPORTED_FORMAT` | The announce wasn't `application/vnd.photon.e2ee` |

Keep the built file until the upload finishes: re-announcing the same file returns the same `upload_id`. If it's lost, a rebuilt file has a new hash but the same tag, and the orchestrator replaces your own unfinished reservation with it.

## 8. What the server checks

| Where | Check |
| --- | --- |
| Announce | `mime` is `application/vnd.photon.e2ee`; `photo_tag` is 32 bytes; `key_epoch` is the current epoch; the caller holds it; no rotation pending |
| `complete` | SHA-256 of the assembled bytes equals the announced hash (`422 HASH_MISMATCH`); the header parses, is version 1, has the token's epoch, sane section lengths, and lengths that add up to the file size (`422 INVALID_IMAGE` otherwise) |
| Downloads | Unchanged: token, membership, status and expiry on every request; served as `application/octet-stream` |

The image server no longer decodes images, makes thumbnails or previews, or knows any image type. A member could upload a well-formed file whose contents aren't a valid image; receivers then fail to decode it and treat it as a failed photo. **Receivers must decode decrypted images defensively**, because the server can no longer screen them.

## 9. Security codes

`security_code(member_key) = the first 20 hex characters of SHA-256("photon/fp/v1" ‖ member_key)`, shown in groups of four, for example `5344 af73 9a4e 0454 f847`. Two members who compare codes in person, or over a call, confirm that the server delivered the real public keys. The Python CLI shows them (`photon-cli keys`); the apps show joins and will show codes in a later version.

## 10. Limits and trade-offs

- **Metadata stays visible:** membership, timing, encrypted sizes, capture times and tags.
- **No moderation by the server:** a report can't include content the server can't read. A reporter's app would have to attach the decrypted photo; this version records the report only.
- **A newcomer needs a member online** to share the key. If only members without any key remain after a rotation is required, nobody can rotate, and uploads stay blocked until the session ends.
- **Older epochs can be missed.** A member shares every epoch it holds above a newcomer's `key_epoch`. A newcomer who joined while a rotation was pending and received the new epoch from the rotator before anyone shared the old one has `key_epoch` at the current epoch, so nobody seals the older one for them, and photos still live from that epoch stay unreadable to them.
- **A member can block a newcomer.** The first box stored for a member and epoch wins, so a member who seals garbage keeps that newcomer from the key until they leave and join again. The newcomer's app sees a box that doesn't open.
- **Keys live as long as the session.** There's no per-message ratchet: sessions are short and photos expire after 30 minutes, so one key per membership change is enough.
- **iOS is not updated yet.** Until it is, the iOS app can't create, join or upload in an encrypted session.

## 11. Where the reference implementation lives

| Piece | File |
| --- | --- |
| Primitives, bundles, sealing, the file format | [src/photon/common/e2ee.py](src/photon/common/e2ee.py) |
| Test vectors every client must reproduce | [tests/vectors/e2ee_v1.json](tests/vectors/e2ee_v1.json), written by [tools/e2ee_vectors.py](tools/e2ee_vectors.py) and checked by [tests/unit/test_e2ee.py](tests/unit/test_e2ee.py) |
| The key relay | [src/photon/orchestrator/routes/keys.py](src/photon/orchestrator/routes/keys.py) and `key_state` and `put_keys` in [photon.lua](src/photon/orchestrator/lua/photon.lua) |
| Header checks and prefix serving | [pipeline.py](src/photon/imageserver/pipeline.py) (`_check_header`) and [routes.py](src/photon/imageserver/routes.py) |
| The Python client's keys | [keyring.py](src/photon/client/keyring.py): in memory, or for the CLI in `$PHOTON_HOME/<profile>.keys.json` with mode 600. `sync_keys`, `rotate_keys` and `download_photo` are in [api.py](src/photon/client/api.py). |
| Thumbnails and previews | [images.py](src/photon/client/images.py), used by [uploader.py](src/photon/client/uploader.py) |
| The system through the real stack | [tests/integration/test_keys.py](tests/integration/test_keys.py) |
