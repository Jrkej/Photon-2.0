"""PHOTON benchmarks. Run with scripts/bench.sh (or: pytest tests/bench -m bench -s). Each benchmark adds a section
to the report in tests/bench/results/latest.md."""

from __future__ import annotations

import asyncio
import hashlib
import io
import os
import statistics
import time
from collections.abc import Awaitable, Callable
from typing import Any

import google_crc32c
import pillow_heif
import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from PIL import Image
from websockets.asyncio.client import connect

from photon.client.api import PhotonClient
from photon.client.uploader import crc32c_b64
from photon.common import clock, ids, jwtutil
from photon.common.config import Settings
from photon.common.pb import photon_pb2 as pb
from photon.common.redisutil import Functions
from photon.imageserver.decode import Decoder
from photon.orchestrator import keys as K
from tests.bench.conftest import Report, latency, pct, photo_like
from tests.conftest import Login, Stack

pytestmark = pytest.mark.bench
RET = 30 * 60 * 1000
CHUNK = 1024 * 1024


async def timed(fn: Callable[[], Awaitable[Any]]) -> float:
    t = time.perf_counter()
    await fn()
    return time.perf_counter() - t


def sha_of(i: int, tag: str = "b") -> str:
    return hashlib.sha256(f"{tag}{i}".encode()).hexdigest()


# --------------------------------------------------------------------------- 1. Lua state transitions

async def test_lua_functions(env: Stack, bench: Report) -> None:
    """Each transition is one Redis function; measured from Python (client overhead included)."""
    fn = Functions(env.redis)
    sid = ids.new_sid()
    keys = K.session_keys(sid)
    now = clock.now_ms
    await fn.call("create_session", keys, sid, now(), RET, "host", 12 * 3600_000, 10, "CODE", "TOKEN")
    rows = []

    join_s = [await timed(lambda i=i: fn.call("join", keys, sid, now(), RET, f"m{i}", 12 * 3600_000))
              for i in range(9)]
    rows.append({"function": "join", **latency(join_s)})

    n = 1000
    announce_s, stored_s = [], []
    for i in range(n):
        sha, up = sha_of(i), ids.new_upload_id()
        announce_s.append(await timed(lambda sha=sha, up=up: fn.call(
            "announce", keys, sid, now(), RET, "host", sha, 3_000_000, "image/jpeg", now(), up,
            10**15, 3, 20, 3, 900_000, 60_000)))
        stored_s.append(await timed(lambda sha=sha, up=up: fn.call(
            "report_stored", keys, sid, now(), RET, sha, up, now(), now() + RET)))
    rows.append({"function": "announce (new photo)", **latency(announce_s)})
    rows.append({"function": "report_stored (+ event)", **latency(stored_s)})

    dup_s = [await timed(lambda i=i: fn.call(
        "announce", keys, sid, now(), RET, "m1", sha_of(i % n), 3_000_000, "image/jpeg", now(),
        ids.new_upload_id(), 10**15, 3, 20, 3, 900_000, 60_000)) for i in range(1000)]
    rows.append({"function": "announce (duplicate)", **latency(dup_s)})

    dl_s = [await timed(lambda i=i: fn.call_ro("download_check", keys, sid, now(), RET, "m2", sha_of(i % n)))
            for i in range(1000)]
    rows.append({"function": "download_check", **latency(dl_s)})

    last = (await env.redis.xrevrange(K.events(sid), count=600))[-1][0]
    ev_s = [await timed(lambda: fn.call_ro("events_since", keys, sid, now(), RET, "m3", last, 500))
            for _ in range(50)]
    rows.append({"function": "events_since (500 events)", **latency(ev_s)})

    snap_s = [await timed(lambda: fn.call_ro("snapshot", keys, sid, now(), RET, "m4")) for _ in range(30)]
    rows.append({"function": f"snapshot ({n} stored photos)", **latency(snap_s)})

    # Throughput with many requests in flight (what the replicas do together)
    ops = 20_000
    sem = asyncio.Semaphore(64)

    async def one(i: int) -> None:
        async with sem:
            await fn.call_ro("download_check", keys, sid, now(), RET, "m5", sha_of(i % n))

    t = time.perf_counter()
    await asyncio.gather(*(one(i) for i in range(ops)))
    rate = ops / (time.perf_counter() - t)
    bench.add("1. Lua state transitions (Redis functions)", rows,
              f"Throughput with 64 requests in flight from one Python process: {rate:,.0f} download_check/s. "
              f"Redis runs each function atomically on one core; the client side is usually the limit.")


# --------------------------------------------------------------------------- 2. Tokens and checksums

async def test_crypto(bench: Report) -> None:
    key = jwtutil.SigningKey("k", Ed25519PrivateKey.generate())
    keys = jwtutil.StaticKeys({key.kid: key.public})
    now = clock.now_ms() // 1000
    claims = {"sub": "u", "iss": "photon-auth", "aud": "photon", "iat": now, "exp": now + 900}
    n = 5000
    t = time.perf_counter()
    tokens = [jwtutil.sign(claims, key) for _ in range(n)]
    sign_rate = n / (time.perf_counter() - t)
    t = time.perf_counter()
    for tok in tokens:
        await jwtutil.verify(tok, keys, audience="photon", issuer="photon-auth")
    verify_rate = n / (time.perf_counter() - t)

    data = os.urandom(25 * 1024 * 1024)
    t = time.perf_counter()
    h = hashlib.sha256()
    for i in range(0, len(data), CHUNK):
        h.update(data[i:i + CHUNK])
    sha_mbs = 25 / (time.perf_counter() - t)
    t = time.perf_counter()
    for i in range(0, len(data), CHUNK):
        google_crc32c.value(data[i:i + CHUNK])
    crc_mbs = 25 / (time.perf_counter() - t)
    bench.add("2. Tokens and checksums (one core)", [
        {"operation": "sign access JWT (Ed25519)", "rate": f"{sign_rate:,.0f}/s"},
        {"operation": "verify access JWT (every request)", "rate": f"{verify_rate:,.0f}/s"},
        {"operation": "SHA-256 (upload verification)", "rate": f"{sha_mbs:,.0f} MB/s"},
        {"operation": "CRC32C (every chunk)", "rate": f"{crc_mbs:,.0f} MB/s"},
    ])


# --------------------------------------------------------------------------- 3. Image validation and thumbnails

async def test_decode(bench: Report, tmp_path: Any) -> None:
    """Each step is a fresh sandboxed subprocess (memory, CPU and time limits)."""
    dec = Decoder(Settings(env="test"))
    tiny = photo_like(64, 48)
    small = photo_like(1600, 1200, seed=1)
    big = photo_like(4032, 3024, seed=2)
    heif = pillow_heif.from_pillow(Image.open(io.BytesIO(big)))
    heic_path = tmp_path / "big.heic"
    heif.save(heic_path, quality=85)
    cases = [("tiny JPEG 64×48 (process overhead)", tiny, "image/jpeg", ".jpg"),
             ("JPEG 1600×1200", small, "image/jpeg", ".jpg"),
             ("JPEG 4032×3024 (12 MP)", big, "image/jpeg", ".jpg"),
             ("HEIC 4032×3024 (12 MP)", heic_path.read_bytes(), "image/heic", ".heic")]
    rows = []
    for label, data, mime, ext in cases:
        src = tmp_path / f"in{ext}"
        src.write_bytes(data)
        row: dict[str, Any] = {"image": label, "size": f"{len(data) / 1e6:.1f} MB"}
        for mode, px in (("validate", 0), ("thumb", 320), ("preview", 1600)):
            runs = []
            for _ in range(3):
                t = time.perf_counter()
                res = await dec.run(mode, src, tmp_path / "out.webp" if px else None, mime, px)
                runs.append(time.perf_counter() - t)
                assert res.ok, (label, mode, res)
            row[f"{mode}_ms"] = statistics.median(runs) * 1000
        rows.append(row)
    bench.add("3. Image validation and thumbnails (sandboxed subprocess, median of 3)", rows,
              "validate + thumb run on every upload's complete; preview runs once, on the first preview "
              "download. A 12 MP photo costs about validate + thumb of CPU on the image server.")


# --------------------------------------------------------------------------- 4. Announce over HTTP

async def test_announce_http(env: Stack, login: Login, bench: Report) -> None:
    s = env.settings
    s.announce_per_minute = 10**7
    s.session_quota_bytes = 10**15  # thousands of 3 MB photos per session: latency, not quota, is measured here
    users = [await login(f"a{i}") for i in range(64)]
    sids = [(await u.create_session()).session_id for u in users]
    fn = Functions(env.redis)
    rows = []
    for conc in (1, 16, 64):
        total = 1500
        per = total // conc
        samples: list[float] = []

        async def worker(w: int, conc: int = conc, per: int = per, samples: list[float] = samples) -> None:
            c, sid = users[w], sids[w]
            for i in range(per):
                sha = hashlib.sha256(f"{conc}-{w}-{i}".encode()).digest()
                t = time.perf_counter()
                ann = await c.announce(sid, sha, 3_000_000, "image/jpeg", clock.now_ms())
                samples.append(time.perf_counter() - t)
                # Complete it server-side so live reservations stay realistic (at most a few per user)
                await fn.call("report_stored", K.session_keys(sid), sid, clock.now_ms(), RET, sha.hex(),
                              ann.upload_id, clock.now_ms(), clock.now_ms() + RET)

        t = time.perf_counter()
        await asyncio.gather(*(worker(w) for w in range(conc)))
        wall = time.perf_counter() - t
        rows.append({"concurrent clients": conc, "requests/s": len(samples) / wall, **latency(samples)})

    dup: list[float] = []
    sha = hashlib.sha256(b"1-0-0").digest()
    sem = asyncio.Semaphore(16)

    async def dup_one() -> None:
        async with sem:
            t = time.perf_counter()
            r = await users[0].announce(sids[0], sha, 3_000_000, "image/jpeg", clock.now_ms())
            dup.append(time.perf_counter() - t)
            assert r.result == pb.AnnounceResponse.ALREADY_UPLOADED

    t = time.perf_counter()
    await asyncio.gather(*(dup_one() for _ in range(1500)))
    rows.append({"concurrent clients": "16 (duplicate)", "requests/s": 1500 / (time.perf_counter() - t),
                 **latency(dup)})
    bench.add("4. Announce over HTTP (JWT check, protobuf, Lua, upload-token signing)", rows,
              "One uvicorn worker per service, all in one Python process with the client: the services "
              "and the load generator share the same event loop and CPU.")


# --------------------------------------------------------------------------- 5. Upload end to end by size

async def upload_timed(c: PhotonClient, sid: str, data: bytes) -> dict[str, float]:
    sha = hashlib.sha256(data).digest()
    t0 = time.perf_counter()
    ann = await c.announce(sid, sha, len(data), "image/jpeg", clock.now_ms())
    t1 = time.perf_counter()
    parts = [data[i:i + CHUNK] for i in range(0, len(data), CHUNK)]
    sem = asyncio.Semaphore(3)

    async def put(i: int) -> None:
        async with sem:
            await c.put_chunk(ann.upload_url, ann.upload_token, i, parts[i], crc32c_b64(parts[i]))

    await asyncio.gather(*(put(i) for i in range(len(parts))))
    t2 = time.perf_counter()
    while True:
        code, _ = await c.complete(ann.upload_url, ann.upload_token, len(parts))
        if code == 200:
            break
        await asyncio.sleep(0.05)
    t3 = time.perf_counter()
    return {"announce": t1 - t0, "chunks": t2 - t1, "complete": t3 - t2, "total": t3 - t0}


async def test_upload_sizes(env: Stack, login: Login, bench: Report) -> None:
    host = await login("uploader")
    sid = (await host.create_session()).session_id
    specs = [("1600×1200", 1600, 1200, 0.12, 90), ("3000×2250", 3000, 2250, 0.12, 90),
             ("4032×3024 (12 MP)", 4032, 3024, 0.12, 92), ("4032×3024, heavy grain", 4032, 3024, 0.45, 97)]
    rows = []
    for label, w, h, noise, q in specs:
        runs = []
        size = 0
        for r in range(3):
            data = photo_like(w, h, seed=100 + r + w, noise=noise, quality=q)
            size = len(data)
            runs.append(await upload_timed(host, sid, data))
        med = {k: statistics.median(x[k] for x in runs) for k in runs[0]}
        rows.append({"photo": label, "size": f"{size / 1e6:.1f} MB", "announce_ms": med["announce"] * 1000,
                     "chunks_ms": med["chunks"] * 1000, "chunk MB/s": size / 1e6 / med["chunks"],
                     "complete_ms": med["complete"] * 1000, "total_ms": med["total"] * 1000})
    bench.add("5. Upload end to end by photo size (median of 3; 1 MiB chunks, 3 in parallel)", rows,
              "complete = assemble + SHA-256 + validate + thumbnail + metadata + the fenced report to the "
              "orchestrator. Over loopback; on a phone the chunk phase is bounded by the network.")


# --------------------------------------------------------------------------- 6. Live delivery to a session

async def ws_listen(url: str, token: str, sid: str, cursor: str, sink: list[tuple[str, float]],
                    ready: asyncio.Event, stop: asyncio.Event) -> None:
    try:
        await _ws_listen(url, token, sid, cursor, sink, ready, stop)
    except Exception as e:  # recorded, so a failed socket shows up in the report instead of a stray traceback
        sink.append((f"closed:{type(e).__name__}", time.time() * 1000))


async def _ws_listen(url: str, token: str, sid: str, cursor: str, sink: list[tuple[str, float]],
                     ready: asyncio.Event, stop: asyncio.Event) -> None:
    async with connect(url, open_timeout=30) as ws:
        await ws.send(pb.ClientFrame(hello=pb.Hello(access_token=token, session_id=sid,
                                                    last_event_id=cursor)).SerializeToString())
        while not stop.is_set():
            try:
                raw = await asyncio.wait_for(ws.recv(), 0.5)
            except TimeoutError:
                continue
            f = pb.ServerFrame()
            f.ParseFromString(raw)  # type: ignore[arg-type]
            if f.WhichOneof("f") == "ready_cursor":
                ready.set()
            elif f.WhichOneof("f") == "error":
                sink.append((f"error:{f.error.code}", time.time() * 1000))
            elif f.WhichOneof("f") == "event":
                sink.append((f.event.event_id, time.time() * 1000))


def ws_url(env: Stack) -> str:
    return "ws://" + env.api_url.removeprefix("http://") + "/v1/ws"


async def test_session_delivery(env: Stack, login: Login, bench: Report) -> None:
    env.settings.ws_block_ms = 15_000  # production value (the tests use 300 ms)
    host = await login("dhost")
    info = await host.create_session()
    sid = info.session_id
    members = [host]
    for i in range(9):
        m = await login(f"d{i}")
        await m.join(code=info.join_code)
        members.append(m)
    cursor = (await host.snapshot(sid)).cursor
    sinks: list[list[tuple[str, float]]] = [[] for _ in members]
    stop = asyncio.Event()
    readies = [asyncio.Event() for _ in members]
    tasks = [asyncio.create_task(ws_listen(ws_url(env), m.tokens.access_token, sid, cursor, sinks[i], readies[i], stop))
             for i, m in enumerate(members)]
    await asyncio.wait_for(asyncio.gather(*(r.wait() for r in readies)), 10)
    e2e: list[float] = []
    for i in range(20):
        data = photo_like(800, 600, seed=500 + i)
        t = time.time() * 1000
        await upload_timed(host, sid, data)
        # wait until every member has the event
        for _ in range(200):
            entries = await env.redis.xrevrange(K.events(sid), count=1)
            eid = entries[0][0] if entries else ""
            if all(any(e == eid for e, _ in s) for s in sinks):
                break
            await asyncio.sleep(0.01)
        last = max(ts for s in sinks for e, ts in s if e == eid)
        e2e.append((last - t) / 1000)
    stop.set()
    await asyncio.gather(*tasks, return_exceptions=True)
    deliveries = [(ts - int(eid.split("-")[0])) / 1000 for s in sinks for eid, ts in s]
    bench.add("6. Live delivery to a 10-member session (20 photos of 800×600)", [
        {"measure": "event appended → frame received (each member)", **latency(deliveries)},
        {"measure": "upload start → last of 10 members received", **latency(e2e)},
    ], "The first row is the fan-out path alone: Redis stream → XREAD → WebSocket. The second adds the upload.")


# --------------------------------------------------------------------------- 7. WebSocket fan-out at scale

async def test_websocket_fanout(env: Stack, login: Login, bench: Report) -> None:
    env.settings.ws_block_ms = 15_000
    sessions, per = 17, 9  # 9 members each, leaving room for the ghost member that makes the events
    clients: list[tuple[PhotonClient, str]] = []
    t = time.perf_counter()
    for s in range(sessions):
        host = await login(f"f{s}h")
        info = await host.create_session()
        clients.append((host, info.session_id))
        for m in range(per - 1):
            c = await login(f"f{s}m{m}")
            await c.join(code=info.join_code)
            clients.append((c, info.session_id))
    setup_s = time.perf_counter() - t
    before = int((await env.redis.info("clients"))["connected_clients"])
    sinks: list[list[tuple[str, float]]] = [[] for _ in clients]
    readies = [asyncio.Event() for _ in clients]
    stop = asyncio.Event()
    cursors = {sid: (await c.snapshot(sid)).cursor for c, sid in clients[::per]}
    t = time.perf_counter()
    tasks = [asyncio.create_task(ws_listen(ws_url(env), c.tokens.access_token, sid, cursors[sid], sinks[i],
                                           readies[i], stop)) for i, (c, sid) in enumerate(clients)]
    try:
        await asyncio.wait_for(asyncio.gather(*(r.wait() for r in readies)), 30)
    except TimeoutError:
        problems = [e for s in sinks for e, _ in s if ":" in e]
        raise AssertionError(f"{sum(r.is_set() for r in readies)}/{len(readies)} sockets ready; "
                             f"problems: {sorted(set(problems))[:10]}") from None
    connect_s = time.perf_counter() - t
    after = int((await env.redis.info("clients"))["connected_clients"])
    fn = Functions(env.redis)
    rounds = 10
    for r in range(rounds):
        for verb, args in (("join", (10**9,)), ("leave", ())):
            await asyncio.gather(*(fn.call(verb, K.session_keys(sid), sid, clock.now_ms(), RET, f"ghost{r}", *args)
                                   for _, sid in clients[::per]))
            await asyncio.sleep(0.15)
    await asyncio.sleep(1.0)
    stop.set()
    await asyncio.gather(*tasks, return_exceptions=True)
    lat = [(ts - int(eid.split("-")[0])) / 1000 for s in sinks for eid, ts in s]
    expected = len(clients) * rounds * 2  # a join and a leave per round
    bench.add(f"7. WebSocket fan-out: {len(clients)} sockets in {sessions} sessions", [
        {"measure": "event appended → frame received", **latency(lat)},
    ], f"{len(lat)}/{expected} deliveries. All {len(clients)} sockets connected and ready in {connect_s:.2f} s "
       f"(sign-in and session setup took {setup_s:.1f} s before that). Redis connections: {before} → {after} "
       f"(+{after - before}): each open socket holds one blocking Redis connection (XREAD BLOCK). The pool is "
       f"PHOTON_REDIS_BLOCKING_POOL (default 200) per orchestrator replica, so it caps open sockets per "
       f"replica; add replicas or raise it for more.")
    assert len(lat) == expected


# --------------------------------------------------------------------------- 8. Upload throughput

async def test_upload_throughput(env: Stack, login: Login, bench: Report) -> None:
    decoder = env.img.state.pipeline.decoder
    saved = decoder.sem
    decoder.sem = asyncio.Semaphore(os.cpu_count() or 4)
    try:
        host = await login("thost")
        info = await host.create_session()
        users = [host]
        for i in range(7):
            u = await login(f"t{i}")
            await u.join(code=info.join_code)
            users.append(u)
        photos = {u.uid: [photo_like(2400, 1800, seed=900 + j * 13 + k) for k in range(6)]
                  for j, u in enumerate(users)}
        total_bytes = sum(len(p) for ps in photos.values() for p in ps)
        per_photo: list[float] = []

        async def run(u: PhotonClient) -> None:
            for data in photos[u.uid]:
                per_photo.append((await upload_timed(u, info.session_id, data))["total"])

        t = time.perf_counter()
        await asyncio.gather(*(run(u) for u in users))
        wall = time.perf_counter() - t
    finally:
        decoder.sem = saved
    n = len(per_photo)
    bench.add("8. Upload throughput: 8 members uploading at once (48 photos of 2400×1800)", [
        {"photos": n, "wall_s": wall, "photos/s": n / wall, "MB/s": total_bytes / 1e6 / wall,
         "per-photo p50_ms": pct([x * 1000 for x in per_photo], 50),
         "per-photo p95_ms": pct([x * 1000 for x in per_photo], 95)},
    ], f"{total_bytes / 1e6:.0f} MB in total. Bound by CPU: hashing, and the two decode subprocesses per photo "
       f"(validate, thumb), with {os.cpu_count()} decoders allowed at once.")
