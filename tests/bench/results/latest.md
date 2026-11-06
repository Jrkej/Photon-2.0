# PHOTON benchmark results

Run 2026-11-06 20:21 · 13th Gen Intel(R) Core(TM) i5-13450HX, 16 threads, 15 GB · Python 3.12.3 · Redis 7.2.4

Everything runs on one machine in one process (Redis and Postgres alongside), so these are relative numbers for this build, not production capacity.

## 1. Lua state transitions (Redis functions)

| function | n | p50_ms | p95_ms | p99_ms | mean_ms | max_ms |
| --- | --- | --- | --- | --- | --- | --- |
| join | 9 | 0.61 | 0.69 | 0.69 | 0.58 | 0.69 |
| put_keys (share with one member) | 9 | 0.60 | 0.68 | 0.69 | 0.60 | 0.69 |
| key_state (10 members) | 200 | 0.70 | 1.05 | 1.17 | 0.72 | 1.40 |
| announce (new photo) | 1000 | 0.66 | 0.82 | 0.90 | 0.63 | 1.02 |
| report_stored (+ event) | 1000 | 0.62 | 0.80 | 0.87 | 0.60 | 0.98 |
| announce (duplicate file) | 1000 | 0.54 | 0.78 | 0.88 | 0.52 | 1.62 |
| announce (same photo, another file: tag dedupe) | 1000 | 0.50 | 0.74 | 0.83 | 0.48 | 0.94 |
| download_check | 1000 | 0.40 | 0.66 | 0.73 | 0.41 | 0.98 |
| events_since (500 events) | 50 | 28.22 | 57.40 | 75.53 | 34.85 | 89.59 |
| snapshot (1000 stored photos) | 30 | 41.38 | 46.15 | 55.21 | 35.95 | 58.57 |

Throughput with 64 requests in flight from one Python process: 11,654 download_check/s. Redis runs each function atomically on one core; the client side is usually the limit.

## 2. Tokens and checksums (one core)

| operation | rate |
| --- | --- |
| sign access JWT (Ed25519) | 29,211/s |
| verify access JWT (every request) | 7,694/s |
| SHA-256 (upload verification) | 1,154 MB/s |
| CRC32C (every chunk) | 7,286 MB/s |

## 3. End-to-end encryption on the device (one core, median of 3)

| image | size | thumb+preview_ms | encrypt_ms | tag_ms | decrypt_full_ms | decrypt_thumb_ms | thumb prefix | overhead |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| JPEG 1600×1200 | 0.4 MB | 21.80 | 0.19 | 0.27 | 0.14 | 0.01 | 2 kB | 219 B |
| JPEG 4032×3024 (12 MP) | 2.6 MB | 325 | 1.17 | 1.96 | 0.96 | 0.02 | 2 kB | 219 B |
| HEIC 4032×3024 (12 MP) | 5.5 MB | 1,025 | 2.00 | 3.01 | 1.87 | 0.01 | 2 kB | 219 B |

thumb+preview is the image work the server used to do; it now runs on the uploader's phone, before AES-256-GCM. Receivers decrypt only the prefix they download. Sealing a session key for one member (X25519 + HKDF + AES-GCM): 15,637/s; opening one: 25,708/s. The image server's only per-upload check, parsing the header: 607,316/s.

## 4. Announce over HTTP (JWT check, protobuf, Lua, upload-token signing)

| concurrent clients | requests/s | n | p50_ms | p95_ms | p99_ms | mean_ms | max_ms |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 184 | 1500 | 5.33 | 7.62 | 8.41 | 4.83 | 10.99 |
| 16 | 557 | 1488 | 22.11 | 26.82 | 70.20 | 23.96 | 87.15 |
| 64 | 541 | 1472 | 91.95 | 165 | 219 | 101 | 221 |
| 16 (duplicate) | 278 | 1500 | 46.46 | 145 | 203 | 56.19 | 291 |

One uvicorn worker per service, all in one Python process with the client: the services and the load generator share the same event loop and CPU.

## 5. Upload end to end by photo size (median of 3; 1 MiB chunks, 3 in parallel)

| photo | size | encrypt_ms | announce_ms | chunks_ms | chunk MB/s | complete_ms | total_ms |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1600×1200 | 0.4 MB | 29.09 | 4.26 | 10.42 | 40.52 | 21.66 | 42.68 |
| 3000×2250 | 1.5 MB | 238 | 3.26 | 15.09 | 96.91 | 23.41 | 43.22 |
| 4032×3024 (12 MP) | 3.1 MB | 353 | 3.10 | 20.37 | 151 | 26.03 | 50.02 |
| 4032×3024, heavy grain | 8.7 MB | 541 | 3.30 | 53.28 | 164 | 46.07 | 102 |

encrypt = the client's thumbnail, preview and AES-GCM, before the announce (not in total). complete = assemble + SHA-256 + header check + metadata + the fenced report to the orchestrator; the server no longer decodes images. Over loopback; on a phone the chunk phase is bounded by the network.

## 6. Live delivery to a 10-member session (20 photos of 800×600)

| measure | n | p50_ms | p95_ms | p99_ms | mean_ms | max_ms |
| --- | --- | --- | --- | --- | --- | --- |
| event appended → frame received (each member) | 200 | 6.57 | 10.50 | 11.23 | 6.34 | 11.32 |
| upload start → last of 10 members received | 20 | 52.65 | 87.13 | 126 | 59.43 | 136 |

The first row is the fan-out path alone: Redis stream → XREAD → WebSocket. The second adds the upload.

## 7. WebSocket fan-out: 153 sockets in 17 sessions

| measure | n | p50_ms | p95_ms | p99_ms | mean_ms | max_ms |
| --- | --- | --- | --- | --- | --- | --- |
| event appended → frame received | 3060 | 58.67 | 70.84 | 72.73 | 51.40 | 73.31 |

3060/3060 deliveries. All 153 sockets connected and ready in 0.28 s (sign-in and session setup took 6.8 s before that). Redis connections: 131 → 284 (+153): each open socket holds one blocking Redis connection (XREAD BLOCK). The pool is PHOTON_REDIS_BLOCKING_POOL (default 200) per orchestrator replica, so it caps open sockets per replica; add replicas or raise it for more.

## 8. Upload throughput: 8 members uploading at once (48 photos of 2400×1800)

| photos | wall_s | photos/s | MB/s | per-photo p50_ms | per-photo p95_ms |
| --- | --- | --- | --- | --- | --- |
| 48 | 2.27 | 21.17 | 20.79 | 381 | 426 |

47 MB of encrypted files in total. The image server only hashes, checks the header and moves files: no decoding, so it is bound by hashing and disk, not image work.
