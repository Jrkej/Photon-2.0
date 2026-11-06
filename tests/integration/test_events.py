"""Phase 5: gap-free events across reconnects, resync, snapshot, WebSocket close codes, downloads (of end-to-end
encrypted files, e2ee.md 6.3)."""

from __future__ import annotations

import asyncio
import json
import random

import pytest
import pytest_asyncio
from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

from photon.client.api import ClientError, PhotonClient
from photon.client.listener import Listener
from photon.client.uploader import upload_bytes
from photon.common import clock, e2ee, jwtutil
from photon.common.pb import photon_pb2 as pb
from photon.orchestrator import keys as K
from tests.conftest import Login, Stack, eventually, jpeg, share_keys

GHOST_KEY = "00" * 32  # a member key for members joined straight through the function library


@pytest_asyncio.fixture(loop_scope="session")
async def pair(login: Login) -> tuple[str, PhotonClient, PhotonClient]:
    host, member = await login("host"), await login("member")
    info = await host.create_session()
    await member.join(code=info.join_code)
    await share_keys(info.session_id, host, member)
    return info.session_id, host, member


def ws_url(env: Stack) -> str:
    return "ws://" + env.api_url.removeprefix("http://") + "/v1/ws"


async def hello(ws: object, token: str, sid: str, cursor: str) -> None:
    await ws.send(pb.ClientFrame(hello=pb.Hello(access_token=token, session_id=sid,  # type: ignore[attr-defined]
                                                last_event_id=cursor)).SerializeToString())


async def frames_until_close(env: Stack, token: str, sid: str, cursor: str,
                             timeout: float = 5.0) -> tuple[list[pb.ServerFrame], int]:
    frames: list[pb.ServerFrame] = []
    async with connect(ws_url(env)) as ws:
        await hello(ws, token, sid, cursor)
        try:
            async with asyncio.timeout(timeout):
                async for raw in ws:
                    f = pb.ServerFrame()
                    f.ParseFromString(raw)  # type: ignore[arg-type]
                    frames.append(f)
        except ConnectionClosed:
            pass
        return frames, ws.close_code or 0


async def test_gap_free_delivery_across_reconnects(pair: tuple[str, PhotonClient, PhotonClient], env: Stack) -> None:
    sid, _host, member = pair
    snap = await member.snapshot(sid)
    produced = 0

    async def producer() -> None:
        nonlocal produced
        keys = K.session_keys(sid)
        for i in range(600):
            uid = f"ghost{i % 5}"
            await env.svc.fn.call("join", keys, sid, clock.now_ms(), env.settings.retention_ms, uid, 10**9,
                                  GHOST_KEY)
            await env.svc.fn.call("leave", keys, sid, clock.now_ms(), env.settings.retention_ms, uid)
            produced += 2
            if i % 50 == 0:
                await asyncio.sleep(0)

    received: list[str] = []
    cursor = snap.cursor

    async def consumer() -> None:
        nonlocal cursor
        rnd = random.Random(7)
        while True:
            take = rnd.randint(1, 120)
            async with connect(ws_url(env)) as ws:
                await hello(ws, member.tokens.access_token, sid, cursor)
                got = 0
                async for raw in ws:
                    f = pb.ServerFrame()
                    f.ParseFromString(raw)  # type: ignore[arg-type]
                    if f.WhichOneof("f") != "event":
                        continue
                    if received and tuple(map(int, f.event.event_id.split("-"))) <= \
                            tuple(map(int, received[-1].split("-"))):
                        continue  # at-least-once: drop what was already processed
                    received.append(f.event.event_id)
                    cursor = f.event.event_id
                    got += 1
                    if produced == 1200 and len(received) >= 1200:
                        return
                    if got >= take:
                        break  # disconnect at a random point

    await asyncio.wait_for(asyncio.gather(producer(), consumer()), 60)
    all_ids = [i for i, _ in await env.redis.xrange(K.events(sid), min=f"({snap.cursor}")]
    assert received == all_ids  # every event, once, in order


async def test_trimmed_cursor_needs_resync(pair: tuple[str, PhotonClient, PhotonClient], env: Stack) -> None:
    sid, _host, member = pair
    old = (await member.snapshot(sid)).cursor
    clock.advance(env.settings.retention_ms + 3_600_000 + 60_000)
    await env.svc.fn.call("join", K.session_keys(sid), sid, clock.now_ms(), env.settings.retention_ms, "late", 10**9,
                          GHOST_KEY)
    with pytest.raises(ClientError) as e:
        await member.events_since(sid, old)
    assert (e.value.status, e.value.code) == (410, "RESYNC_REQUIRED")
    frames, code = await frames_until_close(env, member.tokens.access_token, sid, old)
    assert code == 4409 and frames[0].error.code == "RESYNC_REQUIRED"
    fresh = (await member.snapshot(sid)).cursor
    assert (await member.events_since(sid, fresh)).cursor == fresh


async def test_lost_stream_or_client_ahead_needs_resync(pair: tuple[str, PhotonClient, PhotonClient],
                                                       env: Stack) -> None:
    sid, _, member = pair
    cursor = (await member.snapshot(sid)).cursor
    ms, seq = cursor.split("-")
    with pytest.raises(ClientError) as e:
        await member.events_since(sid, f"{int(ms) + 5000}-0")  # saw events Redis no longer has
    assert e.value.code == "RESYNC_REQUIRED"
    await env.redis.delete(K.events(sid))  # stream lost in a restore
    with pytest.raises(ClientError) as e:
        await member.events_since(sid, cursor)
    assert e.value.code == "RESYNC_REQUIRED"
    _ = seq


async def test_events_pagination(pair: tuple[str, PhotonClient, PhotonClient], env: Stack) -> None:
    sid, _, member = pair
    start = (await member.snapshot(sid)).cursor
    for i in range(7):
        await env.svc.fn.call("join", K.session_keys(sid), sid, clock.now_ms(), env.settings.retention_ms, f"p{i}",
                              10**9, GHOST_KEY)
    seen, cursor = [], start
    while True:
        batch = await member.events_since(sid, cursor, limit=3)
        seen += [e.member_id for e in batch.events]
        cursor = batch.cursor
        if len(batch.events) < 3:
            break
    assert seen == [f"p{i}" for i in range(7)]


async def test_photo_stored_between_snapshot_and_connect_arrives_once(
        pair: tuple[str, PhotonClient, PhotonClient]) -> None:
    sid, host, member = pair
    listener = Listener(member, sid)
    await listener.load_snapshot()
    res = await upload_bytes(host, sid, jpeg(1), "image/jpeg", clock.now_ms())
    assert res.outcome == "STORED"
    listener.cursor = None  # force another snapshot that already contains the photo...
    await listener.load_snapshot()
    stop = asyncio.Event()
    task = asyncio.create_task(listener.run(stop))
    await asyncio.wait_for(listener.connected.wait(), 5)
    stop.set()
    await task
    stored = [e for e in listener.events if e.sha256 == bytes.fromhex(res.sha256)]
    assert len(stored) == 1  # ...and the duplicate from the stream was dropped by hash
    assert stored[0].photo_tag == res.photo_tag and stored[0].key_epoch == 1


@pytest.mark.parametrize("case", ["bad_hello", "bad_token", "not_member"])
async def test_connect_refusals(pair: tuple[str, PhotonClient, PhotonClient], env: Stack, login: Login,
                                case: str) -> None:
    sid, _, member = pair
    cursor = (await member.snapshot(sid)).cursor
    if case == "bad_hello":
        async with connect(ws_url(env)) as ws:
            await ws.send(pb.ClientFrame(ping=1).SerializeToString())
            with pytest.raises(ConnectionClosed):
                await asyncio.wait_for(ws.recv(), 5)
                await asyncio.wait_for(ws.recv(), 5)
            assert ws.close_code == 4400
        return
    token = "garbage" if case == "bad_token" else (await login("stranger")).tokens.access_token
    _frames, code = await frames_until_close(env, token, sid, cursor)
    assert code == {"bad_token": 4401, "not_member": 4403}[case]


async def test_socket_survives_reads_longer_than_the_redis_timeout(
        pair: tuple[str, PhotonClient, PhotonClient], env: Stack) -> None:
    """Regression: redis-py's default 5 s socket timeout cut every XREAD BLOCK longer than that, closing idle
    sockets with 1011 (found on the first phone run, where the dev stack blocks for 15 s)."""
    sid, _, member = pair
    env.settings.ws_block_ms = 6_000
    cursor = (await member.snapshot(sid)).cursor
    async with connect(ws_url(env)) as ws:
        await hello(ws, member.tokens.access_token, sid, cursor)
        ready = pb.ServerFrame()
        ready.ParseFromString(await asyncio.wait_for(ws.recv(), 5))  # type: ignore[arg-type]
        assert ready.WhichOneof("f") == "ready_cursor"
        with pytest.raises(TimeoutError):  # nothing to deliver: the socket must simply stay open
            await asyncio.wait_for(ws.recv(), 8)
        assert ws.close_code is None


async def test_token_expiry_closes_4001(pair: tuple[str, PhotonClient, PhotonClient], env: Stack) -> None:
    sid, _, member = pair
    cursor = (await member.snapshot(sid)).cursor
    now_s = clock.now_ms() // 1000
    short = jwtutil.sign({"sub": member.uid, "iss": "photon-auth", "aud": "photon", "iat": now_s,
                          "exp": now_s + 2}, env.keys.auth)
    frames, code = await frames_until_close(env, short, sid, cursor, timeout=6)
    assert code == 4001 and frames[-1].error.code == "TOKEN_EXPIRED"


async def test_kick_and_end_close_sockets(login: Login, env: Stack) -> None:
    host, bob, carol = await login("host"), await login("bob"), await login("carol")
    info = await host.create_session()
    sid = info.session_id
    await bob.join(code=info.join_code)
    await carol.join(code=info.join_code)
    lb, lc = Listener(bob, sid), Listener(carol, sid)
    tb, tc = asyncio.create_task(lb.run()), asyncio.create_task(lc.run())
    await asyncio.wait_for(asyncio.gather(lb.connected.wait(), lc.connected.wait()), 5)
    await host.kick(sid, bob.uid)
    assert await asyncio.wait_for(tb, 5) == "NOT_A_MEMBER"
    await host.end(sid)
    assert await asyncio.wait_for(tc, 5) == "SESSION_ENDED"
    assert lc.events[-1].type == pb.PhotoEvent.SESSION_ENDED


async def test_downloads(pair: tuple[str, PhotonClient, PhotonClient], env: Stack, login: Login) -> None:
    sid, host, member = pair
    res = await upload_bytes(host, sid, jpeg(3, (2000, 1500)), "image/jpeg", clock.now_ms())
    assert res.outcome == "STORED"
    h = res.sha256
    data = (env.settings.storage_root / "photos" / sid / h / "file").read_bytes()  # the encrypted file
    stranger = await login("stranger")
    with pytest.raises(ClientError) as e:
        await stranger.download(sid, h, "thumb")
    assert (e.value.status, e.value.code) == (404, "PHOTO_NOT_FOUND")  # existence is not leaked
    part = await member.download(sid, h, "full", "bytes=0-9")
    assert part.status_code == 206 and part.content == data[:10]
    assert part.headers["content-range"] == f"bytes 0-9/{len(data)}"
    tail = await member.download(sid, h, "full", "bytes=-5")
    assert tail.content == data[-5:]
    bad = await member.download(sid, h, "full", f"bytes={len(data) + 10}-")
    assert bad.status_code == 416
    full = await member.download(sid, h)
    assert full.content == data and full.headers["content-type"] == "application/octet-stream"
    # thumb and preview are byte prefixes of the same file: the server cuts them at the header's section lengths
    header = e2ee.parse_header(data)
    for size in ("thumb", "preview"):
        prefix = await member.download(sid, h, size)
        assert prefix.content == data[:header.prefix_len(size)]
        assert prefix.headers["content-type"] == "application/octet-stream"
        assert prefix.headers["cache-control"] == "private, no-store"
    assert header.prefix_len("thumb") < header.prefix_len("preview") < len(data)
    assert sorted(p.name for p in (env.settings.storage_root / "photos" / sid / h).iterdir()) == ["file", "meta.json"]
    with pytest.raises(ClientError) as e:
        await member.download(sid, "ab" * 32, "thumb")
    assert e.value.code == "PHOTO_NOT_FOUND"


async def test_image_server_enforces_expiry_even_if_redis_disagrees(
        pair: tuple[str, PhotonClient, PhotonClient], env: Stack) -> None:
    sid, host, member = pair
    res = await upload_bytes(host, sid, jpeg(4), "image/jpeg", clock.now_ms())
    assert res.outcome == "STORED"
    h = res.sha256
    entry = json.loads(await env.redis.hget(K.photos(sid), h))
    entry["expires_at"] = entry["expires_at"] + 10 * 3_600_000  # stale or tampered state
    await env.redis.hset(K.photos(sid), h, json.dumps(entry))
    clock.advance(env.settings.retention_ms + 1)
    with pytest.raises(ClientError) as e:
        await member.download(sid, h, "thumb")
    assert (e.value.status, e.value.code) == (410, "PHOTO_EXPIRED")


async def test_live_delivery(pair: tuple[str, PhotonClient, PhotonClient]) -> None:
    sid, host, member = pair
    listener = Listener(member, sid)
    task = asyncio.create_task(listener.run())
    await asyncio.wait_for(listener.connected.wait(), 5)
    shas = []
    for i in range(3):
        res = await upload_bytes(host, sid, jpeg(10 + i), "image/jpeg", clock.now_ms())
        assert res.outcome == "STORED"
        shas.append(bytes.fromhex(res.sha256))

    async def all_arrived() -> bool:
        return all(s in listener.hashes for s in shas)

    await eventually(all_arrived)
    for i, sha in enumerate(shas):  # the member decrypts what arrived
        assert (await member.download_photo(sid, sha.hex())).original == jpeg(10 + i)
    await host.end(sid)
    await asyncio.wait_for(task, 5)


async def test_many_sockets_at_once(pair: tuple[str, PhotonClient, PhotonClient], env: Stack) -> None:
    """Regression: redis-py 8's default pool failed the 101st concurrent Redis command, so 150 sockets connecting
    together left 50 closed with 1000. Every socket must become ready."""
    sid, _, member = pair
    cursor = (await member.snapshot(sid)).cursor

    async def one() -> int:
        async with connect(ws_url(env), open_timeout=30) as ws:
            await hello(ws, member.tokens.access_token, sid, cursor)
            f = pb.ServerFrame()
            f.ParseFromString(await asyncio.wait_for(ws.recv(), 20))  # type: ignore[arg-type]
            return 1 if f.WhichOneof("f") == "ready_cursor" else 0

    assert sum(await asyncio.gather(*(one() for _ in range(160)))) == 160


async def test_listener_shares_and_receives_keys(login: Login) -> None:
    """Key maintenance by listeners alone (e2ee.md 5.3): the host's listener seals for a newcomer on MEMBER_JOINED,
    and the newcomer's listener opens its box on KEY_SHARED, with no explicit sync by either."""
    host, bob = await login("host"), await login("bob")
    info = await host.create_session()
    sid = info.session_id
    lh = Listener(host, sid)
    th = asyncio.create_task(lh.run())
    await asyncio.wait_for(lh.connected.wait(), 5)
    await bob.join(code=info.join_code)
    lb = Listener(bob, sid)
    tb = asyncio.create_task(lb.run())

    async def bob_has_key() -> bool:
        return bob.keys.has(sid, 1)

    await eventually(bob_has_key)
    assert any(e.type == pb.PhotoEvent.KEY_SHARED and e.member_id == bob.uid for e in lh.events)
    res = await upload_bytes(bob, sid, jpeg(30), "image/jpeg", clock.now_ms())
    assert res.outcome == "STORED"
    await host.end(sid)
    await asyncio.wait_for(asyncio.gather(th, tb), 5)
