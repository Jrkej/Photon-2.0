# PHOTON

PHOTON syncs photos between the galleries of everyone in a live session: when one member takes a photo, it lands in every other member's gallery. A photo is announced to the session only after the image server has verified its SHA-256 and stored it, and server copies expire 30 minutes after they are stored.

- [Design.md](Design.md) is the system design: the architecture, protocols, data model and the guarantees the server makes.
- [implementation.md](implementation.md) is the build plan, phase by phase, with every decision the design left open.

## What is here

| Part | Where | Language | State |
| --- | --- | --- | --- |
| Auth (OAuth with PKCE, JWTs, refresh rotation) | [src/photon/auth/](src/photon/auth/) | Python | Built and tested |
| Orchestrator (sessions, announce, fencing, events, WebSocket, sweepers, push) | [src/photon/orchestrator/](src/photon/orchestrator/) | Python, Lua | Built and tested |
| State transitions (Redis Functions library) | [src/photon/orchestrator/lua/photon.lua](src/photon/orchestrator/lua/photon.lua) | Lua | Built and tested |
| Image server (chunked upload, verification, sandboxed decoding, outbox, downloads, retention) | [src/photon/imageserver/](src/photon/imageserver/) | Python | Built and tested |
| Reference client, CLI, load test | [src/photon/client/](src/photon/client/), [tools/](tools/) | Python | Built and tested |
| Wire format | [proto/photon.proto](proto/photon.proto) | Protobuf | Shared by all parts |
| Android app | [android/](android/) | Kotlin | Builds; 96 unit tests pass; not yet run on a device. See [android/README.md](android/README.md) |
| iOS app | [ios/](ios/) | Swift | PhotonKit core: 90 tests pass on Linux. App target not yet compiled (needs Xcode). See [ios/README.md](ios/README.md) |
| Deployment | [deploy/](deploy/), [Dockerfile](Dockerfile) | Compose, Caddy, Redis, Prometheus | Written; validated pieces listed below |

## Quick start (no Docker)

```bash
scripts/dev-deps.sh                     # builds Redis 7.2 into .devtools/ (Postgres comes from a pip wheel)
python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"
.venv/bin/pytest -q                     # 137 tests: unit, integration, end to end
.venv/bin/photon-dev                    # API on :8080 (Auth + orchestrator), image server on :8081
```

`photon-dev` starts Redis, an embedded Postgres, runs the migrations, generates keys into `.devdata/keys`, and serves everything over plain HTTP with the dev login provider. It is for development only. Use `--host 0.0.0.0` to reach it from an emulator (`http://10.0.2.2:8080` on Android).

Two users sharing photos with the script client:

```bash
photon-cli --profile alice login
photon-cli --profile bob login
photon-cli --profile alice create                 # prints the join code and the invite link
photon-cli --profile bob join <CODE>
photon-cli --profile bob listen --save-dir ./received &
photon-cli --profile alice upload IMG_0001.jpg IMG_0002.heic
photon-cli --profile alice end
```

Other commands: `info`, `snapshot`, `catchup`, `recall <sha>`, `report <sha> <reason>`, `download <sha>`, `kick <user>`, `rotate`, `leave`, `device`.

## Tests

| Suite | What it proves |
| --- | --- |
| [tests/unit/](tests/unit/) | The error contract, ids, one Redis slot per function call, token verification, the decoder against hostile files, push routing |
| [tests/integration/test_auth.py](tests/integration/test_auth.py) | Single-use OAuth state, PKCE, exact redirect URIs, refresh grace window and reuse revocation, identity never merged by email |
| [tests/integration/test_sessions.py](tests/integration/test_sessions.py) | Capacity under concurrent joins, one session per user, host hand-over, kick, joins racing the end, idle expiry |
| [tests/integration/test_upload.py](tests/integration/test_upload.py) | Resumable chunks, idempotent complete in every state, hash mismatch, crash recovery through the outbox, thumbnail retries |
| [tests/integration/test_fencing.py](tests/integration/test_fencing.py) | One upload per hash, quota races, live-upload limits, stale reports fenced out, Redis-loss recovery, recall, strikes |
| [tests/integration/test_model.py](tests/integration/test_model.py) | Random sequences of every transition against the real Lua functions, with the quota and event invariants checked after each step |
| [tests/integration/test_events.py](tests/integration/test_events.py) | 1,200 events delivered once, in order, across random reconnects; resync; WebSocket close codes; downloads and Range |
| [tests/integration/test_retention.py](tests/integration/test_retention.py) | Exact logical expiry, both sweepers, retried deletes, orphans, Redis loss, quota recount |
| [tests/integration/test_limits.py](tests/integration/test_limits.py) | The design's limits table, rate limits, photo reports, push only to members without a socket |
| [tests/integration/test_acl.py](tests/integration/test_acl.py) | The Redis ACL file: each service can do its job and nothing else |
| [tests/e2e/](tests/e2e/) | Login, create, join, upload, live event, download, dedupe, recall, end |

The harness starts a real Redis, an embedded Postgres and all three services on random ports. Time is driven through `photon.common.clock`, so expiry and grace windows are tested without waiting.

## Configuration

Every setting is an environment variable `PHOTON_<NAME>` (see [src/photon/common/config.py](src/photon/common/config.py)); the limits default to the design's proposed values. The ones every deployment sets:

| Variable | Meaning |
| --- | --- |
| `PHOTON_ENV` | `prod` refuses the dev login provider and custom-scheme redirects |
| `PHOTON_REDIS_URL` | `rediss://<service-user>:<password>@redis:6379/0?ssl_ca_certs=/certs/ca.pem` |
| `PHOTON_DATABASE_URL` | `postgresql+asyncpg://...` (Auth and the orchestrator) |
| `PHOTON_KEYS_DIR` | Directory with this service's private key and the public keys it verifies (from `tools/gen_keys.py`) |
| `PHOTON_PUBLIC_BASE_URL`, `PHOTON_IMAGE_BASE_URL` | Public URLs (invite links, upload URLs) |
| `PHOTON_ORCHESTRATOR_INTERNAL_URL`, `PHOTON_IMAGESERVER_INTERNAL_URL` | Service-to-service URLs |
| `PHOTON_STORAGE_ROOT` | Image server data directory (one filesystem) |
| `PHOTON_GOOGLE_CLIENT_ID`, `PHOTON_GOOGLE_CLIENT_SECRET` | Google sign-in |
| `PHOTON_OAUTH_REDIRECT_URIS` | Registered redirect URIs, matched exactly |
| `PHOTON_FCM_CREDENTIALS_FILE` | Push hints to Android devices through FCM (needs the `push` extra) |
| `PHOTON_APNS_KEY_FILE`, `PHOTON_APNS_KEY_ID`, `PHOTON_APNS_TEAM_ID`, `PHOTON_APNS_TOPIC` | Background pushes to iOS devices through APNs (needs the `push` extra) |
| `PHOTON_TRUSTED_PROXIES` | Peers whose `X-Forwarded-For` is trusted |

## Deploying

[deploy/docker-compose.yml](deploy/docker-compose.yml) runs everything on one host: Redis with a replica (TLS, AOF every second, one ACL user per service), Postgres, a migration job, two Auth and two orchestrator replicas, one image server with a volume, Caddy (TLS 1.3, HTTP/3 with HTTP/2 fallback) and Prometheus.

```bash
python3 tools/gen_keys.py deploy/keys
scripts/make-certs.sh deploy/certs
cp deploy/users.acl.example deploy/users.acl     # set real passwords, the same ones as in deploy/.env
cp deploy/.env.example deploy/.env               # host names, passwords, Google client
docker compose -f deploy/docker-compose.yml up -d --build
```

- **Redis ACLs** ([users.acl.example](deploy/users.acl.example)). The orchestrator owns session state and loads the function library, but can't flush, list keys or delete functions. Auth can reach only `oauth:*`, `refresh:*`, `rfam:*` and `rl:*`. The image server can read `session:*` and refresh `upload:*` leases, and can write nothing else. Redis ACL files can't contain comments, so the explanation lives here.
- **Keys.** Each service mounts only its own private key (compose secrets). To rotate a key, run `tools/gen_keys.py --next`, publish the `.next` public key, wait 10 minutes, swap the files, and remove the old key after 15 minutes.
- **Private routes.** Caddy refuses `/internal/*`, `/metrics` and `/readyz` from outside. The image server's chunk route is capped at 9 MB.
- **Clocks.** Run chrony or another NTP client on every host: `expires_at` is compared across hosts, and an alert fires at a 100 ms offset.
- **Scaling.** Add orchestrator and Auth replicas freely; no sticky routing is needed. A second image server needs routing by `upload_id`, or object storage (Design.md, Scaling).

What was validated here: the Redis ACL file against Redis 7.2 (test_acl.py), the dev stack end to end, and a load test. The compose file, Caddyfile and Dockerfile were written but not run here (no Docker access on the build machine).

## Operating

- **Metrics:** every service serves `/metrics` (Prometheus). [deploy/alerts.yml](deploy/alerts.yml) holds the alerts from implementation step 9.2: files past expiry, the report backlog, temporary disk, delete failures, the expiry backlog, hash-mismatch spikes, clock skew, Redis replication, and the WebSocket pool.
- **Health:** `/healthz` reports the process is alive. `/readyz` reports that Redis, Postgres and the function library are at the expected version.
- **Load test:** `tools/loadtest.py --sessions 20 --members 5 --photos 3` against a stack with raised per-IP limits (see its docstring). On the build machine (16 cores, everything on one host), 300 photos of 1600×1200 were all stored and all 1,200 deliveries arrived. Server-side stored-to-delivered averaged 15 ms. Upload p50 was 0.88 s and p95 4.3 s, with the load generator sharing the CPU.
