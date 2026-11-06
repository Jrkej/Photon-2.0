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

from photon.client import images
from photon.client.api import PhotonClient
from photon.client.uploader import build_file, crc32c_b64
from photon.common import clock, e2ee, ids, jwtutil
from photon.common.pb import photon_pb2 as pb
from photon.common.redisutil import Functions
from photon.orchestrator import keys as K
from tests.bench.conftest import Report, latency, pct, photo_like
from tests.conftest import Login, Stack, share_keys

pytestmark = pytest.mark.bench
RET = 30 * 60 * 1000
CHUNK = 1024 * 1024


async def timed(fn: Callable[[], Awaitable[Any]]) -> float:
    t = time.perf_counter()
    await fn()
    return time.perf_counter() - t


def sha_of(i: int, tag: str = "b") -> str:
    return hashlib.sha256(f"{tag}{i}".encode()).hexdigest()


def tag_of(sha: str) -> str:
    """A photo tag per hash (e2ee.md 6.2): each benchmarked file is a distinct photo."""
    return hashlib.sha256(b"tag" + sha.encode()).hexdigest()


PK = hashlib.sha256(b"bench member key").hexdigest()  # the Lua functions store member keys as given
BOX = "ab" * e2ee.BOX_LEN


# --------------------------------------------------------------------------- 1. Lua state transitions

async def test_lua_functions(env: Stack, bench: Report) -> None:
    """Each transition is one Redis function; measured from Python (client overhead included)."""
    fn = Functions(env.redis)
    sid = ids.new_sid()
    keys = K.session_keys(sid)
    now = clock.now_ms
    await fn.call("create_session", keys, sid, now(), RET, "host", 12 * 3600_000, 10, "CODE", "TOKEN", PK)
    rows = []

    join_s = [await timed(lambda i=i: fn.call("join", keys, sid, now(), RET, f"m{i}", 12 * 3600_000, PK))
              for i in range(9)]
    rows.append({"function": "join", **latency(join_s)})

    # The host seals epoch 1 for each newcomer, one call per member as a phone does on MEMBER_JOINED
    share_s = [await timed(lambda i=i: fn.call("put_keys", keys, sid, now(), RET, "host", 1, "0", 1, f"m{i}", BOX))
               for i in range(9)]
    rows.append({"function": "put_keys (share with one member)", **latency(share_s)})
    state_s = [await timed(lambda: fn.call_ro("key_state", keys, sid, now(), RET, "m1")) for _ in range(200)]
    rows.append({"function": "key_state (10 members)", **latency(state_s)})

    def announce(member: str, sha: str, tag: str, up: str | None = None) -> Awaitable[list[Any]]:
        return fn.call("announce", keys, sid, now(), RET, member, sha, 3_000_000, e2ee.MIME, now(),
                       up or ids.new_upload_id(), 10**15, 3, 20, 3, 900_000, 60_000, tag, 1)

    n = 1000
    announce_s, stored_s = [], []
    for i in range(n):
        sha, up = sha_of(i), ids.new_upload_id()
        announce_s.append(await timed(lambda sha=sha, up=up: announce("host", sha, tag_of(sha), up)))
        stored_s.append(await timed(lambda sha=sha, up=up: fn.call(
            "report_stored", keys, sid, now(), RET, sha, up, now(), now() + RET)))
    rows.append({"function": "announce (new photo)", **latency(announce_s)})
    rows.append({"function": "report_stored (+ event)", **latency(stored_s)})

    dup_s = [await timed(lambda i=i: announce("m1", sha_of(i % n), tag_of(sha_of(i % n)))) for i in range(1000)]
    rows.append({"function": "announce (duplicate file)", **latency(dup_s)})
    # Another member's copy of the same photo: a different file and hash, the same tag
    tag_s = [await timed(lambda i=i: announce("m2", sha_of(i, "other"), tag_of(sha_of(i % n)))) for i in range(1000)]
    rows.append({"function": "announce (same photo, another file: tag dedupe)", **latency(tag_s)})

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


# --------------------------------------------------------------------------- 3. End-to-end encryption on the phone

def median_ms(fn: Callable[[], Any], runs: int = 3) -> float:
    samples = []
    for _ in range(runs):
        t = time.perf_counter()
        fn()
        samples.append(time.perf_counter() - t)
    return statistics.median(samples) * 1000


async def test_client_crypto(bench: Report, tmp_path: Any) -> None:
    """The work that moved from the image server to the uploader's and receivers' devices (e2ee.md 6, 7)."""
    bundle = e2ee.new_session_bundle()
    keys = {bundle.epoch: bundle.key}
    big = photo_like(4032, 3024, seed=2)
    heif = pillow_heif.from_pillow(Image.open(io.BytesIO(big)))
    heic_path = tmp_path / "big.heic"
    heif.save(heic_path, quality=85)
    cases = [("JPEG 1600×1200", photo_like(1600, 1200, seed=1)),
             ("JPEG 4032×3024 (12 MP)", big),
             ("HEIC 4032×3024 (12 MP)", heic_path.read_bytes())]
    rows = []
    for label, data in cases:
        prepared = images.prepare(data)
        file = build_file(bundle, data, prepared, 0)
        header = e2ee.parse_header(file)
        thumb = file[:header.prefix_len("thumb")]
        rows.append({
            "image": label, "size": f"{len(data) / 1e6:.1f} MB",
            "thumb+preview_ms": median_ms(lambda data=data: images.prepare(data)),
            "encrypt_ms": median_ms(lambda data=data, prepared=prepared: build_file(bundle, data, prepared, 0)),
            "tag_ms": median_ms(lambda data=data: e2ee.photo_tag(bundle.tag_key, data)),
            "decrypt_full_ms": median_ms(lambda file=file: e2ee.decrypt_photo(file, keys)),
            "decrypt_thumb_ms": median_ms(lambda thumb=thumb: e2ee.decrypt_photo(thumb, keys)),
            "thumb prefix": f"{len(thumb) / 1e3:.0f} kB",
            "overhead": f"{len(file) - len(data) - len(prepared.thumb) - len(prepared.preview)} B",
        })
    member = e2ee.new_identity()
    member_pub = e2ee.public_bytes(member)
    n = 500
    t = time.perf_counter()
    boxes = [e2ee.seal(bundle, member_pub, "S" * 22, "member") for _ in range(n)]
    seal_rate = n / (time.perf_counter() - t)
    t = time.perf_counter()
    for box in boxes:
        e2ee.open_box(box, member, "S" * 22, "member", 1)
    open_rate = n / (time.perf_counter() - t)
    t = time.perf_counter()
    for _ in range(20_000):
        e2ee.parse_header(file)
    header_rate = 20_000 / (time.perf_counter() - t)
    bench.add("3. End-to-end encryption on the device (one core, median of 3)", rows,
              f"thumb+preview is the image work the server used to do; it now runs on the uploader's phone, before "
              f"AES-256-GCM. Receivers decrypt only the prefix they download. Sealing a session key for one member "
              f"(X25519 + HKDF + AES-GCM): {seal_rate:,.0f}/s; opening one: {open_rate:,.0f}/s. The image server's "
              f"only per-upload check, parsing the header: {header_rate:,.0f}/s.")


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
                ann = await c.announce(sid, sha, 3_000_000, e2ee.MIME, clock.now_ms(),
                                       hashlib.sha256(b"tag" + sha).digest(), 1)
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
            r = await users[0].announce(sids[0], sha, 3_000_000, e2ee.MIME, clock.now_ms(),
                                        hashlib.sha256(b"tag" + sha).digest(), 1)
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

async def encrypt(c: PhotonClient, sid: str, data: bytes) -> tuple[bytes, bytes, int]:
    """What the phone does before announcing: thumbnail, preview, encrypt, tag (e2ee.md 7)."""
    bundle = await c.current_bundle(sid)
    assert bundle is not None
    return build_file(bundle, data, images.prepare(data), 0), e2ee.photo_tag(bundle.tag_key, data), bundle.epoch


async def upload_timed(c: PhotonClient, sid: str, data: bytes,
                       built: tuple[bytes, bytes, int] | None = None) -> dict[str, float]:
    """Encrypts `data` (unless `built` is given), then announce to STORED; "total" is the server path only."""
    te = time.perf_counter()
    file, tag, epoch = built or await encrypt(c, sid, data)
    sha = hashlib.sha256(file).digest()
    t0 = time.perf_counter()
    ann = await c.announce(sid, sha, len(file), e2ee.MIME, clock.now_ms(), tag, epoch)
    assert ann.result == pb.AnnounceResponse.UPLOAD, ann
    t1 = time.perf_counter()
    parts = [file[i:i + CHUNK] for i in range(0, len(file), CHUNK)]
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
    return {"encrypt": t0 - te, "announce": t1 - t0, "chunks": t2 - t1, "complete": t3 - t2, "total": t3 - t0}


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
        rows.append({"photo": label, "size": f"{size / 1e6:.1f} MB", "encrypt_ms": med["encrypt"] * 1000,
                     "announce_ms": med["announce"] * 1000,
                     "chunks_ms": med["chunks"] * 1000, "chunk MB/s": size / 1e6 / med["chunks"],
                     "complete_ms": med["complete"] * 1000, "total_ms": med["total"] * 1000})
    bench.add("5. Upload end to end by photo size (median of 3; 1 MiB chunks, 3 in parallel)", rows,
              "encrypt = the client's thumbnail, preview and AES-GCM, before the announce (not in total). "
              "complete = assemble + SHA-256 + header check + metadata + the fenced report to the orchestrator; "
              "the server no longer decodes images. Over loopback; on a phone the chunk phase is bounded by the "
              "network.")


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
        for verb, args in (("join", (10**9, PK)), ("leave", ())):
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
    host = await login("thost")
    info = await host.create_session()
    users = [host]
    for i in range(7):
        u = await login(f"t{i}")
        await u.join(code=info.join_code)
        users.append(u)
    await share_keys(info.session_id, *users)
    # Encrypted before the clock starts: the phones' work is in section 3, this measures the servers
    photos = {u.uid: [await encrypt(u, info.session_id, photo_like(2400, 1800, seed=900 + j * 13 + k))
                      for k in range(6)] for j, u in enumerate(users)}
    total_bytes = sum(len(p[0]) for ps in photos.values() for p in ps)
    per_photo: list[float] = []

    async def run(u: PhotonClient) -> None:
        for built in photos[u.uid]:
            per_photo.append((await upload_timed(u, info.session_id, b"", built))["total"])

    t = time.perf_counter()
    await asyncio.gather(*(run(u) for u in users))
    wall = time.perf_counter() - t
    n = len(per_photo)
    bench.add("8. Upload throughput: 8 members uploading at once (48 photos of 2400×1800)", [
        {"photos": n, "wall_s": wall, "photos/s": n / wall, "MB/s": total_bytes / 1e6 / wall,
         "per-photo p50_ms": pct([x * 1000 for x in per_photo], 50),
         "per-photo p95_ms": pct([x * 1000 for x in per_photo], 95)},
    ], f"{total_bytes / 1e6:.0f} MB of encrypted files in total. The image server only hashes, checks the "
       f"header and moves files: no decoding, so it is bound by hashing and disk, not image work.")
