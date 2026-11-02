# PHOTON benchmark results

Run 2026-11-02 17:26 · 13th Gen Intel(R) Core(TM) i5-13450HX, 16 threads, 15 GB · Python 3.12.3 · Redis 7.2.4

Everything runs on one machine in one process (Redis and Postgres alongside), so these are relative numbers for this build, not production capacity.

## 1. Lua state transitions (Redis functions)

| function | n | p50_ms | p95_ms | p99_ms | mean_ms | max_ms |
| --- | --- | --- | --- | --- | --- | --- |
| join | 9 | 0.07 | 0.12 | 0.14 | 0.08 | 0.14 |
| announce (new photo) | 1000 | 0.08 | 0.17 | 0.26 | 0.09 | 0.34 |
| report_stored (+ event) | 1000 | 0.07 | 0.17 | 0.24 | 0.09 | 0.31 |
| announce (duplicate) | 1000 | 0.08 | 0.13 | 0.22 | 0.09 | 0.40 |
| download_check | 1000 | 0.07 | 0.15 | 0.28 | 0.08 | 0.37 |
| events_since (500 events) | 50 | 14.39 | 15.27 | 32.07 | 15.11 | 47.42 |
| snapshot (1000 stored photos) | 30 | 10.91 | 14.61 | 18.84 | 11.45 | 19.87 |

Throughput with 64 requests in flight from one Python process: 14,038 download_check/s. Redis runs each function atomically on one core; the client side is usually the limit.

## 2. Tokens and checksums (one core)

| operation | rate |
| --- | --- |
| sign access JWT (Ed25519) | 34,230/s |
| verify access JWT (every request) | 9,842/s |
| SHA-256 (upload verification) | 1,831 MB/s |
| CRC32C (every chunk) | 11,403 MB/s |

## 3. Image validation and thumbnails (sandboxed subprocess, median of 3)

| image | size | validate_ms | thumb_ms | preview_ms |
| --- | --- | --- | --- | --- |
| tiny JPEG 64×48 (process overhead) | 0.0 MB | 47.62 | 64.01 | 72.84 |
| JPEG 1600×1200 | 0.4 MB | 59.38 | 89.32 | 203 |
| JPEG 4032×3024 (12 MP) | 2.6 MB | 96.80 | 151 | 325 |
| HEIC 4032×3024 (12 MP) | 5.5 MB | 574 | 625 | 801 |

validate + thumb run on every upload's complete; preview runs once, on the first preview download. A 12 MP photo costs about validate + thumb of CPU on the image server.

## 4. Announce over HTTP (JWT check, protobuf, Lua, upload-token signing)

| concurrent clients | requests/s | n | p50_ms | p95_ms | p99_ms | mean_ms | max_ms |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 545 | 1500 | 1.61 | 1.97 | 2.61 | 1.66 | 4.08 |
| 16 | 696 | 1488 | 18.87 | 19.85 | 29.58 | 19.16 | 57.47 |
| 64 | 704 | 1472 | 72.03 | 115 | 152 | 75.48 | 153 |
| 16 (duplicate) | 408 | 1500 | 32.88 | 67.75 | 91.71 | 38.32 | 1,121 |

One uvicorn worker per service, all in one Python process with the client: the services and the load generator share the same event loop and CPU.

## 5. Upload end to end by photo size (median of 3; 1 MiB chunks, 3 in parallel)

| photo | size | announce_ms | chunks_ms | chunk MB/s | complete_ms | total_ms |
| --- | --- | --- | --- | --- | --- | --- |
| 1600×1200 | 0.4 MB | 2.56 | 7.67 | 55.04 | 169 | 183 |
| 3000×2250 | 1.5 MB | 2.95 | 11.35 | 129 | 216 | 230 |
| 4032×3024 (12 MP) | 3.1 MB | 2.50 | 21.05 | 146 | 298 | 322 |
| 4032×3024, heavy grain | 8.7 MB | 2.58 | 38.47 | 226 | 351 | 392 |

complete = assemble + SHA-256 + validate + thumbnail + metadata + the fenced report to the orchestrator. Over loopback; on a phone the chunk phase is bounded by the network.

## 6. Live delivery to a 10-member session (20 photos of 800×600)

| measure | n | p50_ms | p95_ms | p99_ms | mean_ms | max_ms |
| --- | --- | --- | --- | --- | --- | --- |
| event appended → frame received (each member) | 200 | 2.59 | 4.29 | 4.48 | 2.90 | 4.49 |
| upload start → last of 10 members received | 20 | 159 | 167 | 168 | 158 | 168 |

The first row is the fan-out path alone: Redis stream → XREAD → WebSocket. The second adds the upload.

## 7. WebSocket fan-out: 153 sockets in 17 sessions

| measure | n | p50_ms | p95_ms | p99_ms | mean_ms | max_ms |
| --- | --- | --- | --- | --- | --- | --- |
| event appended → frame received | 3060 | 28.84 | 31.32 | 35.79 | 28.10 | 36.34 |

3060/3060 deliveries. All 153 sockets connected and ready in 0.25 s (sign-in and session setup took 3.3 s before that). Redis connections: 131 → 284 (+153): each open socket holds one blocking Redis connection (XREAD BLOCK). The pool is PHOTON_REDIS_BLOCKING_POOL (default 200) per orchestrator replica, so it caps open sockets per replica; add replicas or raise it for more.

## 8. Upload throughput: 8 members uploading at once (48 photos of 2400×1800)

| photos | wall_s | photos/s | MB/s | per-photo p50_ms | per-photo p95_ms |
| --- | --- | --- | --- | --- | --- |
| 48 | 1.94 | 24.76 | 23.21 | 291 | 388 |

45 MB in total. Bound by CPU: hashing, and the two decode subprocesses per photo (validate, thumb), with 16 decoders allowed at once.
