"""Phase 4: announce, dedupe, quota, live limits, fencing by upload_id, recall, strikes, Redis-loss recovery.
Files are end-to-end encrypted (e2ee.md): announces carry the photo tag and key epoch, and dedupe is by tag."""

from __future__ import annotations

import asyncio
import hashlib
import json

import httpx
import pytest
import pytest_asyncio

from photon.client.api import ClientError, PhotonClient
from photon.client.uploader import crc32c_b64, upload_bytes, upload_file
from photon.common import clock, e2ee, jwtutil
from photon.common.pb import photon_pb2 as pb
from photon.common.proto import PROTO
from photon.orchestrator import keys as K
from tests.conftest import Built, Login, Stack, announce_built, build, jpeg, share_keys


@pytest_asyncio.fixture(loop_scope="session")
async def room(login: Login) -> tuple[str, list[PhotonClient]]:
    """A session with the host and nine members."""
    host = await login("host")
    info = await host.create_session()
    members = [host]
    for i in range(9):
        m = await login(f"m{i}")
        await m.join(code=info.join_code)
        members.append(m)
    await share_keys(info.session_id, *members)
    return info.session_id, members


def sha(data: bytes) -> bytes:
    return hashlib.sha256(data).digest()


def tag(data: bytes) -> bytes:
    """A stand-in photo tag for announces that never upload a file: distinct data, distinct photo."""
    return hashlib.sha256(b"tag:" + data).digest()


async def fake_announce(c: PhotonClient, sid: str, data: bytes, size: int | None = None) -> pb.AnnounceResponse:
    """Announces a photo by its stand-in hash and tag, at epoch 1, without building a file."""
    return await c.announce(sid, sha(data), len(data) if size is None else size, e2ee.MIME, clock.now_ms(),
                            tag(data), 1)


async def report(env: Stack, kind: str, msg: pb.PhotoStored | pb.PhotoRejected) -> httpx.Response:
    token = jwtutil.ServiceTokens("imageserver", env.keys.svc_img).token_for("orchestrator")
    async with httpx.AsyncClient() as h:
        return await h.post(f"{env.api_url}/internal/v1/photos/{kind}", content=msg.SerializeToString(),
                            headers={"Authorization": f"Bearer {token}", "Content-Type": PROTO})


def stored_msg(sid: str, file: bytes, upload_id: str, uploader: str) -> pb.PhotoStored:
    now = clock.now_ms()
    return pb.PhotoStored(session_id=sid, sha256=sha(file), upload_id=upload_id, uploader_id=uploader,
                          size=len(file), mime=e2ee.MIME, taken_at_ms=now, stored_at_ms=now,
                          expires_at_ms=now + 30 * 60 * 1000)


async def test_concurrent_announce_one_upload(room: tuple[str, list[PhotonClient]]) -> None:
    sid, members = room
    built = await build(members[0], sid, jpeg(1))
    results = await asyncio.gather(*(announce_built(m, sid, built) for m in members))
    kinds = sorted(pb.AnnounceResponse.Result.Name(r.result) for r in results)
    assert kinds == ["IN_PROGRESS"] * 9 + ["UPLOAD"]


async def test_concurrent_announce_of_one_photo_in_different_files(room: tuple[str, list[PhotonClient]]) -> None:
    """Every member encrypts the same photo into its own file: ten hashes, one tag, still one upload."""
    sid, members = room
    files = [await build(m, sid, jpeg(31)) for m in members]
    assert len({f.sha for f in files}) == 10 and len({f.tag for f in files}) == 1
    results = await asyncio.gather(*(announce_built(m, sid, f) for m, f in zip(members, files, strict=True)))
    kinds = sorted(pb.AnnounceResponse.Result.Name(r.result) for r in results)
    assert kinds == ["IN_PROGRESS"] * 9 + ["UPLOAD"]


async def test_announce_is_idempotent_with_fresh_tokens(room: tuple[str, list[PhotonClient]]) -> None:
    sid, (alice, *_) = room
    built = await build(alice, sid, jpeg(2, (200, 200)))
    data = built.file
    a1 = await announce_built(alice, sid, built)
    await asyncio.sleep(1.1)  # a later token
    a2 = await announce_built(alice, sid, built)
    assert a1.upload_id == a2.upload_id and a1.upload_token != a2.upload_token
    half = len(data) // 2
    await alice.put_chunk(a1.upload_url, a1.upload_token, 0, data[:half], crc32c_b64(data[:half]))
    await alice.put_chunk(a2.upload_url, a2.upload_token, 1, data[half:], crc32c_b64(data[half:]))
    code, _ = await alice.complete(a2.upload_url, a2.upload_token, 2)
    assert code == 200


async def test_quota_race(room: tuple[str, list[PhotonClient]], env: Stack) -> None:
    sid, members = room
    s = env.settings
    s.session_quota_bytes, s.max_live_uploads_user, s.max_live_uploads_session = 100_000, 100, 100
    results = await asyncio.gather(*(fake_announce(members[i % 10], sid, str(i).encode(), 10_000)
                                     for i in range(20)), return_exceptions=True)
    ok = [r for r in results if isinstance(r, pb.AnnounceResponse)]
    refused = [r for r in results if isinstance(r, ClientError)]
    assert len(ok) == 10 and all(r.code == "QUOTA_EXCEEDED" and r.status == 429 for r in refused)
    reserved = int(await env.redis.hget(K.session(sid), "reserved_bytes"))
    assert reserved == 100_000


async def test_live_upload_limits(room: tuple[str, list[PhotonClient]]) -> None:
    sid, (alice, *_) = room
    for i in range(3):
        await fake_announce(alice, sid, f"live{i}".encode(), 100)
    with pytest.raises(ClientError) as e:
        await fake_announce(alice, sid, b"live3", 100)
    assert (e.value.code, e.value.retryable) == ("TOO_MANY_UPLOADS", True)


async def test_stale_reports_are_fenced(room: tuple[str, list[PhotonClient]], env: Stack) -> None:
    sid, (alice, bob, *_) = room
    built = await build(alice, sid, jpeg(3))
    data = built.file
    a = await announce_built(alice, sid, built)
    await alice.put_chunk(a.upload_url, a.upload_token, 0, data, crc32c_b64(data))
    # Alice's uploader dies: the lease expires and, 60 s after the reservation, Bob takes over (with the same file)
    await env.redis.delete(K.lease(sid, a.upload_id))
    clock.advance(61_000)
    b = await announce_built(bob, sid, built)
    assert b.result == pb.AnnounceResponse.UPLOAD and b.upload_id != a.upload_id
    late_rejected = pb.PhotoRejected(session_id=sid, sha256=sha(data), upload_id=a.upload_id, reason="HASH_MISMATCH")
    assert (await report(env, "rejected", late_rejected)).status_code == 409
    assert (await report(env, "stored", stored_msg(sid, data, a.upload_id, alice.uid))).status_code == 409
    entry = json.loads(await env.redis.hget(K.photos(sid), sha(data).hex()))
    assert entry["upload_id"] == b.upload_id and entry["status"] == "UPLOADING"
    # Alice's own complete is refused too, and her files are cleaned
    with pytest.raises(ClientError) as e:
        await alice.complete(a.upload_url, a.upload_token, 1)
    assert e.value.code == "UPLOAD_SUPERSEDED"
    await bob.put_chunk(b.upload_url, b.upload_token, 0, data, crc32c_b64(data))
    code, _ = await bob.complete(b.upload_url, b.upload_token, 1)
    assert code == 200
    assert int(await env.redis.hget(K.session(sid), "reserved_bytes")) == 0
    assert int(await env.redis.hget(K.session(sid), "stored_bytes")) == len(data)


async def test_report_after_end_is_refused(room: tuple[str, list[PhotonClient]], env: Stack) -> None:
    sid, (host, bob, *_) = room
    data = jpeg(4)
    a = await fake_announce(bob, sid, data)
    await host.end(sid)
    resp = await report(env, "stored", stored_msg(sid, data, a.upload_id, bob.uid))
    assert resp.status_code == 410
    entries = await env.redis.xrange(K.events(sid))
    assert not any(f.get("t") == "PHOTO_STORED" for _, f in entries)


async def test_duplicate_report_is_noop(room: tuple[str, list[PhotonClient]], env: Stack) -> None:
    sid, (alice, *_) = room
    data = jpeg(5)
    a = await fake_announce(alice, sid, data)
    msg = stored_msg(sid, data, a.upload_id, alice.uid)
    acks = []
    for _ in range(2):
        resp = await report(env, "stored", msg)
        ack = pb.ReportAck()
        ack.ParseFromString(resp.content)
        acks.append(ack.result)
    assert acks == [pb.ReportAck.APPLIED, pb.ReportAck.NOOP]
    events = await env.redis.xrange(K.events(sid))
    assert sum(1 for _, f in events if f.get("t") == "PHOTO_STORED") == 1


async def test_announce_racing_end(login: Login, env: Stack) -> None:
    users = [await login(f"x{i}") for i in range(3)]
    for it in range(30):
        host = await login("racer")
        info = await host.create_session()
        sid = info.session_id
        for u in users:
            await u.join(code=info.join_code)
        await host.sync_keys(sid)  # the server counts the users as holding epoch 1, so announces get through
        await asyncio.gather(host.end(sid), *(fake_announce(u, sid, f"{it}-{i}".encode(), 100)
                                              for i, u in enumerate(users)), return_exceptions=True)
        photos = await env.redis.hgetall(K.photos(sid))
        assert not any(json.loads(v)["status"] == "UPLOADING" for v in photos.values())
        counters = await env.redis.hmget(K.session(sid), "stored_bytes", "reserved_bytes")
        assert counters == ["0", "0"]
        assert not [k async for k in env.redis.scan_iter(f"upload:{{{sid}}}:*")]
        for u in users:
            await u.leave(sid)


async def test_redis_loss_after_storage_reuses_the_file(room: tuple[str, list[PhotonClient]], env: Stack) -> None:
    sid, (alice, *_) = room
    built = await build(alice, sid, jpeg(6))
    first = await upload_file(alice, sid, built.file, clock.now_ms(), built.tag, built.epoch)
    assert first.outcome == "STORED" and first.response
    # Redis loses the photo entry (the last second before a failover)
    await env.redis.hdel(K.photos(sid), built.sha_hex)
    await env.redis.hset(K.session(sid), "stored_bytes", 0)
    a = await announce_built(alice, sid, built)
    assert a.result == pb.AnnounceResponse.UPLOAD and a.upload_id != first.upload_id
    st = await alice.upload_status(a.upload_url, a.upload_token)
    assert st.server_has_file
    code, resp = await alice.complete(a.upload_url, a.upload_token, 1)
    assert code == 200 and resp and resp.expires_at_ms == first.response.expires_at_ms
    assert len(list((env.settings.storage_root / "photos").rglob("file"))) == 1


async def test_kick_during_upload(room: tuple[str, list[PhotonClient]], env: Stack) -> None:
    sid, (host, bob, *_) = room
    built = await build(bob, sid, jpeg(7, (300, 300)))
    data = built.file
    a = await announce_built(bob, sid, built)
    await bob.put_chunk(a.upload_url, a.upload_token, 0, data[:1000], crc32c_b64(data[:1000]))
    await host.kick(sid, bob.uid)
    assert int(await env.redis.hget(K.session(sid), "reserved_bytes")) == 0
    with pytest.raises(ClientError) as e:
        await bob.put_chunk(a.upload_url, a.upload_token, 1, data[1000:], crc32c_b64(data[1000:]))
    assert e.value.code in ("NOT_A_MEMBER", "UPLOAD_EXPIRED")
    with pytest.raises(ClientError) as e:
        await bob.complete(a.upload_url, a.upload_token, 2)
    assert e.value.code == "NOT_A_MEMBER"


async def test_recall(room: tuple[str, list[PhotonClient]]) -> None:
    sid, (host, alice, bob, *_) = room
    data = jpeg(8)
    res = await upload_bytes(alice, sid, data, "image/jpeg", clock.now_ms())
    assert res.outcome == "STORED"
    with pytest.raises(ClientError) as e:
        await bob.recall(sid, res.sha256)
    assert e.value.code == "FORBIDDEN"
    await host.recall(sid, res.sha256)  # the host may recall anyone's photo
    await alice.recall(sid, res.sha256)  # idempotent
    with pytest.raises(ClientError) as e:  # Bob's own encryption of the same photo: another file, the same tag
        await announce_built(bob, sid, await build(bob, sid, data))
    assert (e.value.status, e.value.code) == (410, "PHOTO_RECALLED")
    with pytest.raises(ClientError) as e:
        await bob.download(sid, res.sha256, "thumb")
    assert e.value.code == "PHOTO_RECALLED"


async def test_strikes_block_uploads(room: tuple[str, list[PhotonClient]]) -> None:
    sid, (_, mallory, *_) = room
    for i in range(3):
        res = await upload_bytes(mallory, sid, jpeg(20 + i), "image/jpeg", clock.now_ms(),
                                 announced_sha=sha(f"victim{i}".encode()))
        assert res.outcome == "HASH_MISMATCH"
    with pytest.raises(ClientError) as e:
        await fake_announce(mallory, sid, b"anything", 10)
    assert e.value.code == "FORBIDDEN"


async def test_announce_validation(room: tuple[str, list[PhotonClient]], env: Stack) -> None:
    sid, (alice, *_) = room
    cases: list[tuple[dict[str, object], int, str]] = [
        (dict(size=env.settings.max_photo_bytes + 1), 413, "TOO_LARGE"),
        (dict(mime="image/gif"), 415, "UNSUPPORTED_FORMAT"),
        (dict(mime="image/jpeg"), 415, "UNSUPPORTED_FORMAT"),  # plaintext photos are refused
        (dict(mime=""), 415, "UNSUPPORTED_FORMAT"),
        (dict(size=0), 400, "INVALID_REQUEST"),
        (dict(sha=b"short"), 400, "INVALID_REQUEST"),
        (dict(tag=b""), 400, "INVALID_REQUEST"),
        (dict(tag=b"\1" * 31), 400, "INVALID_REQUEST"),
        (dict(epoch=0), 400, "INVALID_REQUEST"),
        (dict(epoch=2), 409, "KEY_EPOCH_STALE"),  # an epoch the session hasn't reached
    ]
    for over, status, code in cases:
        args: dict[str, object] = {"sha": sha(b"v"), "size": 100, "mime": e2ee.MIME, "tag": tag(b"v"), "epoch": 1,
                                   **over}
        with pytest.raises(ClientError) as e:
            await alice.announce(sid, args["sha"], args["size"], args["mime"], clock.now_ms(),  # type: ignore[arg-type]
                                 args["tag"], args["epoch"])  # type: ignore[arg-type]
        assert (e.value.status, e.value.code) == (status, code), over


async def test_announce_needs_the_key(login: Login) -> None:
    """A newcomer nobody has sealed the key for can't announce, and nobody can while a rotation is pending."""
    host, bob, carol = await login("host"), await login("bob"), await login("carol")
    info = await host.create_session()
    sid = info.session_id
    await bob.join(code=info.join_code)
    built: Built = await build(host, sid, jpeg(32))
    with pytest.raises(ClientError) as e:  # Bob even has the file, but holds no epoch
        await announce_built(bob, sid, built)
    assert (e.value.status, e.value.code) == (403, "KEY_REQUIRED")
    await carol.join(code=info.join_code)
    await share_keys(sid, host, bob, carol)
    await carol.leave(sid)
    for c in (host, bob):
        with pytest.raises(ClientError) as e:
            await announce_built(c, sid, built)
        assert (e.value.status, e.value.code) == (409, "KEY_ROTATION_REQUIRED")
