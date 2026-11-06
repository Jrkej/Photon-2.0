# Before hosting PHOTON

Everything to do before PHOTON goes live, in order. Items marked **Blocking** must be done before real users join; the rest should be done before a public launch. Commands assume the repository root.

How to run things is in [README.md](README.md), and what each file is for is in [code.md](code.md).

## 0. Know what has and hasn't been tested

| Tested here | Not tested yet |
| --- | --- |
| The backend: 189 automated tests (end-to-end encryption included), a load test (300 photos, all 1,200 deliveries), the Redis ACL file on a real Redis | The Docker image, docker-compose and the Caddyfile (no Docker access on the build machine) |
| The Android app on one real phone: sign-in, sessions, camera uploads | HTTP/3, real FCM and APNs pushes, real Google sign-in, Redis failover |
| The Android build and its unit tests; the iOS core library's 90 tests | The iOS app has never been compiled; the Android device tests haven't run |

So the first deployment must be a **staging** deployment (section 9), not production.

## 1. Confirm the product decisions

The design picked defaults; confirm or change each in `deploy/.env` (every setting is `PHOTON_<NAME>`, see `src/photon/common/config.py`):

- [ ] Retention: photos are unavailable 30 minutes after storage and deleted within 5 minutes after that (`PHOTON_RETENTION_MS`)
- [ ] Limits:
  - 25 MB per encrypted photo file (`PHOTON_MAX_PHOTO_BYTES`). The 100-megapixel limit and the type list (JPEG, PNG, WebP and HEIC) are enforced by the apps, because photos are end-to-end encrypted and the server can't see inside them ([e2ee.md](e2ee.md))
  - 1 GB per session
  - 10 members per session
  - 3 live uploads per user and 20 per session
  - 3 hash-mismatch strikes
- [ ] Sessions end after 12 hours without activity. Join codes last 2 hours and invite links 12 hours
- [ ] Location (GPS) is stripped from photos before upload, by default
- [ ] Motion photos are accepted (data after the image is allowed; the apps check this, not the server)
- [ ] Rate limits:
  - 20 announces a minute per user
  - 5 joins a minute per user and per IP
  - 20 sign-ins a minute per IP
  - 10 session creates an hour per user

## 2. Legal and store policy

- [ ] **Blocking:** a legal review of reporting and takedown for illegal content, as the design requires. Members can already report a photo; the reports land in the Postgres table `photo_reports`, and someone must watch it. Photos are end-to-end encrypted, so the server can't see a reported photo, and a report holds only the hash and the reason. The review must accept that, or the apps must attach the decrypted photo to a report (not built).
- [ ] **Blocking:** a privacy policy and terms of use. Google sign-in and both app stores require a privacy policy URL. Cover what is shared, for how long, and that originals saved to members' galleries can't be recalled. Say what the server can see: photos are end-to-end encrypted, but membership, timing, file sizes and capture times are not (e2ee.md, section 10).
- [ ] **Blocking for the stores: account deletion.** Google Play and the App Store require apps with accounts to offer deletion in the app and through a web link. **This isn't built yet.** Add an endpoint that deletes the user, their identities and devices, revokes their refresh tokens and leaves their session, plus a button in both apps.
- [ ] **Play Console declarations** for the permissions the Android app uses:
  - the special-use foreground service (the justification text is already in the manifest);
  - `READ_MEDIA_IMAGES` under Play's photo and video permissions policy (the core function needs access to new camera photos);
  - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- [ ] Age rating and content questionnaires in both stores.

## 3. Domains, server and network

- [ ] **Two host names**, both with DNS A (and AAAA) records pointing at the server:
  - `PHOTON_PUBLIC_HOST` (for example `photon.example.com`) serves the API, sign-in, invite links `/j/<token>` and `/.well-known/` (App Links, Universal Links).
  - `PHOTON_IMAGE_HOST` (for example `img.photon.example.com`) serves uploads and downloads.
- [ ] **A Linux server** with Docker and the Compose plugin.
  - CPU: the image server only hashes each upload and reads its 85-byte header; phones make the thumbnails before encrypting. Size the CPU for TLS and request load rather than image work.
  - Disk: the photo volume holds at most 30 minutes of photos, so size it for your peak. The temporary-upload budget is 2 GB (`PHOTON_TMP_DISK_BUDGET_BYTES`).
  - RAM: 8 GB or more.
- [ ] **Firewall:** open 80/tcp, 443/tcp and **443/udp** (HTTP/3). Keep everything else closed; Redis, Postgres and the services are only on the internal Docker network.
- [ ] **Clock sync:** install and enable `chrony` on the host. `expires_at` is compared across services, and an alert fires at a 100 ms offset.
- [ ] Decide where Postgres lives. The compose file has its own; a managed Postgres with point-in-time recovery is better.

## 4. Secrets and configuration

```bash
python3 tools/gen_keys.py deploy/keys            # the four Ed25519 key pairs; keep deploy/keys out of git
scripts/make-certs.sh deploy/certs               # internal CA and the Redis TLS certificate
cp deploy/users.acl.example deploy/users.acl     # one Redis user per service
cp deploy/.env.example deploy/.env
```

- [ ] **Blocking:** replace **every** password in `deploy/users.acl` (including `healthcheck`) with a long random value, and put the same values in `deploy/.env`. Generate each with `openssl rand -base64 32`.
- [ ] **Blocking:** fill in `deploy/.env`:

| Variable | Value |
| --- | --- |
| `PHOTON_PUBLIC_HOST`, `PHOTON_IMAGE_HOST` | The two host names |
| `POSTGRES_PASSWORD`, `REDIS_*_PASSWORD` | Random values, matching `users.acl` |
| `PHOTON_GOOGLE_CLIENT_ID`, `PHOTON_GOOGLE_CLIENT_SECRET` | From section 5 |
| `PHOTON_GITHUB_CLIENT_ID`, `PHOTON_GITHUB_CLIENT_SECRET` | From section 5 (optional) |
| `PHOTON_OAUTH_REDIRECT_URIS` | `["https://<PHOTON_PUBLIC_HOST>/auth/cb"]` (the iOS browser flow) |
| `PHOTON_ANDROID_CERT_SHA256` | The release and Play signing certificate fingerprints (section 7) |
| `PHOTON_IOS_APP_ID` | `<TEAMID>.com.photon.app` |

- [ ] Back up `deploy/keys`, `deploy/certs/ca.key` and `deploy/.env` somewhere safe and offline. Losing `auth-access.pem` signs everyone out; leaking it lets anyone sign in as anyone.
- [ ] Confirm `PHOTON_ENV=prod`. Compose sets it, and it disables the developer sign-in.

## 5. Sign-in providers (Google, GitHub)

In the Google Cloud Console, in one project:

- [ ] **OAuth consent screen:** app name, logo, support email, privacy policy URL, the scopes `openid` and `profile`, and publishing status *In production*.
- [ ] **A Web application client.** Its id is `PHOTON_GOOGLE_CLIENT_ID`. The Android app reads it from `GET /v1/auth/config`, so it isn't built into the app.
- [ ] **An Android client** for package `com.photon.app`, with the SHA-1 of every key the app is signed with: the release key, **and Google Play's app signing key**, shown in Play Console under App integrity. Without the right SHA-1, sign-in fails silently.
- [ ] An iOS client, when the iOS app ships.

**GitHub** (optional): on GitHub, under Settings → Developer settings → OAuth Apps → New OAuth App:
- [ ] Set the authorization callback URL to `https://<PHOTON_PUBLIC_HOST>/v1/auth/oauth/return` (it must match exactly).
- [ ] Copy the client id, generate a client secret, and set `PHOTON_GITHUB_CLIENT_ID` and `PHOTON_GITHUB_CLIENT_SECRET` in `deploy/.env`. The app shows "Continue with GitHub" once the server reports it is configured.
- [ ] Use a separate OAuth App for development, with the callback `http://127.0.0.1:8080/v1/auth/oauth/return`. One app has one callback URL.

## 6. Push notifications (optional, but improves delivery)

Pushes are only wake-up hints, so PHOTON works without them.

- [ ] **Android (FCM):**
  - Create a Firebase project for `com.photon.app`, put `google-services.json` in `android/app/` and rebuild.
  - Download a service-account JSON for the server.
- [ ] **iOS (APNs):** create an APNs auth key (`.p8`) and set `PHOTON_APNS_KEY_ID`, `PHOTON_APNS_TEAM_ID` and `PHOTON_APNS_TOPIC=com.photon.app`.
- [ ] **Mount the credentials into the orchestrator.** The compose file doesn't do this yet. Add to `orchestrator` in `deploy/docker-compose.yml`:
  ```yaml
      environment:
        PHOTON_FCM_CREDENTIALS_FILE: /run/secrets/fcm.json
        PHOTON_APNS_KEY_FILE: /run/secrets/apns.p8
      secrets:
        - {source: fcm, target: /run/secrets/fcm.json}
        - {source: apns, target: /run/secrets/apns.p8}
  ```
  Then declare `fcm` and `apns` under `secrets:` with their file paths.

## 7. Build and sign the apps

**Android**
- [ ] Create a release keystore and add a `signingConfigs.release` to `android/app/build.gradle.kts` (there is none yet). Keep the keystore and its passwords backed up; losing them means you can never update the app.
- [ ] Put the release certificate's SHA-256 **and** Play's app-signing SHA-256 into `PHOTON_ANDROID_CERT_SHA256`. The server publishes them in `/.well-known/assetlinks.json`, which verifies the invite and sign-in App Links.
- [ ] Build against your hosts:
  ```bash
  cd android && ./gradlew bundleRelease \
    -Pphoton.release.apiBase=https://<PHOTON_PUBLIC_HOST> \
    -Pphoton.release.imageBase=https://<PHOTON_IMAGE_HOST> \
    -Pphoton.appLinkHost=<PHOTON_PUBLIC_HOST>
  ```
  The defaults assume `api.` and `img.` subdomains of `appLinkHost`, which doesn't match the compose layout, so pass all three.
- [ ] Run the device tests on real phones (Pixel, Samsung, Xiaomi, Motorola): `./gradlew connectedDebugAndroidTest`. Check by hand:
  - photos taken with the app in the background are shared;
  - GPS is gone from shared JPEG and HEIC files;
  - camera folder names differ between manufacturers, and photos from each are picked up;
  - the battery-optimisation prompt works.

**iOS** (needs a Mac with Xcode 26)
- [ ] Compile `ios/PhotonApp` for the first time and fix what Xcode reports. It has never been built.
- [ ] Set `DEVELOPMENT_TEAM`, the real hosts in `ios/PhotonApp/Config/Release.xcconfig`, and the capabilities: Associated Domains, Push, Background Modes. Test through TestFlight on devices.

## 8. Gaps to close in code before a public launch

- [ ] **Blocking for the stores:** account deletion (section 2).
- [ ] **Invite links in a browser:** `https://<host>/j/<token>` shows an error to someone without the app. Add a small page that explains PHOTON and links to the stores.
- [ ] **Monitoring:** `deploy/prometheus.yml` scrapes `redis-exporter` and `node-exporter`, but the compose file doesn't run them. Add both, plus **Alertmanager** with a receiver (email, Slack, PagerDuty), or the alerts in `deploy/alerts.yml` reach nobody.
- [ ] **Postgres backups:** nothing backs Postgres up yet. Add a daily `pg_dump` to off-site storage, or use managed point-in-time recovery, and do one test restore. Accounts are the only long-lived data; Redis holds only short-lived session state (with AOF), and photos expire anyway.
- [ ] **Redis failover is manual:** there is a replica but no Sentinel. Decide whether a manual promotion is acceptable, or add Sentinel. Implementation step 9.6 describes the recovery rules.
- [ ] **Display names:** members are shown by user id. Consider exposing names before launch.
- [ ] **iOS end-to-end encryption:** the iOS app doesn't implement [e2ee.md](e2ee.md) yet, so it can't create, join or upload in a session. Port it (and pass the test vectors in `tests/vectors/e2ee_v1.json`) before shipping iOS.

## 9. Staging run and verification

Bring the stack up on a staging server first, with the same configuration and its own host names:

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
docker compose -f deploy/docker-compose.yml ps                                  # everything healthy
docker compose -f deploy/docker-compose.yml exec orchestrator \
  python -c "import urllib.request; print(urllib.request.urlopen('http://localhost:8080/readyz').read())"
docker compose -f deploy/docker-compose.yml logs migrate                        # migrations ran
```

- [ ] Every service is `ready`; `https://<PHOTON_PUBLIC_HOST>/.well-known/assetlinks.json` lists your fingerprints; `/internal/...`, `/metrics` and `/readyz` answer 404 from outside.
- [ ] HTTP/3: with a curl built with HTTP/3 support, `curl --http3 -I https://<PHOTON_IMAGE_HOST>/healthz` works, and the `alt-svc` header is present.
- [ ] The full flow on real phones: Google sign-in, create, join by code and by QR, background upload, live delivery, recall, leave, end, and expiry after 30 minutes.
- [ ] **Load test:** `tools/loadtest.py` signs in with the developer login. Run it only against a *private* staging stack with `PHOTON_ENV=dev` and the per-IP limits raised (see its docstring); never expose such a stack. Size for your expected peak, for example `--sessions 50 --members 10 --photos 5`.
- [ ] **Failure drills** (implementation step 9.6):
  - stop the image server during uploads;
  - restart the orchestrator;
  - promote the Redis replica;
  - block UDP 443.
  
  After each: nothing is lost, the outbox drains, and no file outlives its expiry by 15 minutes.
- [ ] Watch Prometheus during all of this. `photon_files_past_expiry` stays 0, `photon_outbox_pending` returns to 0, and `photon_redis_blocking_pool_in_use` stays well under `PHOTON_REDIS_BLOCKING_POOL` (200 by default; one connection per open WebSocket).

## 10. Operations ready

- [ ] Alerts go to a person (section 8), and someone is on call for launch week.
- [ ] A short runbook covers how to:
  - restart a service;
  - read logs (`docker compose logs`, JSON lines with a `request_id`);
  - promote the Redis replica;
  - restore Postgres;
  - rotate a signing key (README, "Keys");
  - handle a photo report or takedown request.
- [ ] Log retention is decided. Logs contain user ids and hash prefixes; never tokens, codes or photo data.
- [ ] Capacity: more orchestrator and Auth replicas can be added at any time. A second image server needs routing by `upload_id`, or object storage (Design.md, Scaling).

## 11. Go-live checklist

- [ ] Sections 1–4 done; legal sign-off, privacy policy published.
- [ ] Google sign-in works with the release build downloaded from Play (Play's signing key SHA-1 is registered). GitHub sign-in works if it's configured.
- [ ] Staging passed: the device flow, load test and failure drills.
- [ ] Backups run, and a test restore succeeded.
- [ ] Alerts reach a person.
- [ ] `PHOTON_ENV=prod`, the developer sign-in returns 404, and `deploy/keys` and `deploy/.env` are not in git.
- [ ] Production deployed from the same images and configuration as staging.
