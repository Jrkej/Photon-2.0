"""Phase 6: exact logical expiry, bounded physical deletion by two independent sweepers, orphans, recount."""

from __future__ import annotations

import json
import os
import time
from pathlib import Path

import pytest
import pytest_asyncio

from photon.client.api import ClientError, PhotonClient
from photon.client.uploader import crc32c_b64, upload_bytes
from photon.common import clock
from photon.common.pb import photon_pb2 as pb
from photon.imageserver import upload_state as U
from photon.orchestrator import jobs
from photon.orchestrator import keys as K
from tests.conftest import Login, Stack, announce_built, build, eventually, group, jpeg


@pytest_asyncio.fixture(loop_scope="session")
async def pair(login: Login) -> tuple[str, PhotonClient, PhotonClient]:
    sid, (host, member) = await group(login, "host", "member")  # both hold the session key
    return sid, host, member


async def store(c: PhotonClient, sid: str, seed: int) -> tuple[str, pb.CompleteResponse]:
    """Uploads an encrypted photo; returns its id (the hash of the encrypted file) and the complete answer."""
    res = await upload_bytes(c, sid, jpeg(seed), "image/jpeg", clock.now_ms())
    assert res.outcome == "STORED" and res.response
    return res.sha256, res.response


async def announce_new(c: PhotonClient, sid: str, seed: int) -> tuple[pb.AnnounceResponse, bytes]:
    built = await build(c, sid, jpeg(seed))
    ann = await announce_built(c, sid, built)
    assert ann.result == pb.AnnounceResponse.UPLOAD
    return ann, built.file


def photo_dir(env: Stack, sid: str, sha: str) -> Path:
    return env.settings.storage_root / "photos" / sid / sha


def set_now(ms: int) -> None:
    clock.reset()
    clock.advance(ms - clock.now_ms())


async def test_expiry_boundary_from_redis(pair: tuple[str, PhotonClient, PhotonClient], env: Stack) -> None:
    sid, host, member = pair
    sha, resp = await store(host, sid, 1)
    meta_path = photo_dir(env, sid, sha) / "meta.json"
    meta = json.loads(meta_path.read_text())
    meta["expires_at_ms"] += 3_600_000  # only Redis decides in this test
    meta_path.write_text(json.dumps(meta))
    set_now(resp.expires_at_ms - 1000)  # real time keeps running between here and the check
    assert (await member.download(sid, sha, "thumb")).status_code == 200
    set_now(resp.expires_at_ms)
    with pytest.raises(ClientError) as e:
        await member.download(sid, sha, "thumb")
    assert e.value.code == "PHOTO_EXPIRED"


async def test_orchestrator_sweeper_deletes_after_expiry(pair: tuple[str, PhotonClient, PhotonClient],
                                                          env: Stack) -> None:
    sid, host, _ = pair
    sha, resp = await store(host, sid, 2)
    await jobs.sweep_expiry(env.svc)
    assert photo_dir(env, sid, sha).exists()  # not due yet
    set_now(resp.expires_at_ms + 1000)
    await jobs.sweep_expiry(env.svc)
    assert not photo_dir(env, sid, sha).exists()
    entry = json.loads(await env.redis.hget(K.photos(sid), sha))
    assert entry["status"] == "EXPIRED"
    assert int(await env.redis.hget(K.session(sid), "stored_bytes")) == 0
    shard = K.expiry(K.shard(sid, env.settings.expiry_shards))
    assert await env.redis.zcard(shard) == 0
    # A late announce of an expired photo is not re-uploaded: neither the same file nor a rebuilt one (a new hash,
    # the same photo tag)
    rebuilt = await build(host, sid, jpeg(2))
    assert rebuilt.sha_hex != sha
    assert (await announce_built(host, sid, rebuilt)).result == pb.AnnounceResponse.ALREADY_UPLOADED


async def test_image_server_sweeper_alone(pair: tuple[str, PhotonClient, PhotonClient], env: Stack) -> None:
    sid, host, _ = pair
    sha, resp = await store(host, sid, 3)
    set_now(resp.expires_at_ms + 1)
    await env.img.state.sweeper.sweep()
    assert not photo_dir(env, sid, sha).exists()


async def test_failed_deletes_are_retried_until_acknowledged(
        pair: tuple[str, PhotonClient, PhotonClient], env: Stack, monkeypatch: pytest.MonkeyPatch) -> None:
    sid, host, _ = pair
    sha, resp = await store(host, sid, 4)
    real = env.svc.delete_file
    failures = {"left": 3}

    async def flaky(s: str, h: str) -> bool:
        if failures["left"]:
            failures["left"] -= 1
            return False
        return await real(s, h)

    monkeypatch.setattr(env.svc, "delete_file", flaky)
    shard = K.expiry(K.shard(sid, env.settings.expiry_shards))
    set_now(resp.expires_at_ms + 1)
    for _ in range(3):
        await jobs.sweep_expiry(env.svc)
        assert await env.redis.zscore(shard, f"{sid}:{sha}") is not None
        clock.advance(301_000)  # past the backoff
    await jobs.sweep_expiry(env.svc)
    assert await env.redis.zscore(shard, f"{sid}:{sha}") is None
    assert not photo_dir(env, sid, sha).exists()


async def test_sweeper_never_deletes_a_live_photo(pair: tuple[str, PhotonClient, PhotonClient], env: Stack) -> None:
    sid, host, _ = pair
    sha, resp = await store(host, sid, 5)
    shard = K.expiry(K.shard(sid, env.settings.expiry_shards))
    await env.redis.zadd(shard, {f"{sid}:{sha}": clock.now_ms() - 10})  # a wrong, early entry
    await jobs.sweep_expiry(env.svc)
    assert photo_dir(env, sid, sha).exists()
    assert int(await env.redis.zscore(shard, f"{sid}:{sha}")) == resp.expires_at_ms


async def test_recall_and_end_delete_at_once(pair: tuple[str, PhotonClient, PhotonClient], env: Stack) -> None:
    sid, host, member = pair
    sha1, _ = await store(host, sid, 6)
    sha2, _ = await store(member, sid, 7)
    await member.recall(sid, sha2)

    async def gone(sha: str) -> bool:
        return not photo_dir(env, sid, sha).exists()

    await eventually(lambda: gone(sha2))
    await host.end(sid)
    await eventually(lambda: gone(sha1))


async def test_redis_loss_does_not_keep_files(pair: tuple[str, PhotonClient, PhotonClient], env: Stack) -> None:
    sid, host, _ = pair
    shas = [(await store(host, sid, 10 + i))[0] for i in range(3)]
    await env.redis.flushdb()  # every key gone; the functions survive
    latest = max(json.loads((photo_dir(env, sid, s) / "meta.json").read_text())["expires_at_ms"] for s in shas)
    set_now(int(latest) + 1)
    await env.img.state.sweeper.sweep()
    assert not any(photo_dir(env, sid, s).exists() for s in shas)


async def test_orphan_rules(pair: tuple[str, PhotonClient, PhotonClient], env: Stack) -> None:
    sid, host, member = pair
    root = env.settings.storage_root
    # A photo directory without meta.json, older than an hour
    orphan = root / "photos" / sid / ("f" * 64)
    orphan.mkdir(parents=True)
    (orphan / "file").write_bytes(b"x")
    old = time.time() - 2 * 3600
    os.utime(orphan, (old, old))
    # An abandoned upload whose lease is gone, and one protected by a pending report
    uploads = []
    for seed in (20, 21):
        ann, data = await announce_new(member, sid, seed)
        await member.put_chunk(ann.upload_url, ann.upload_token, 0, data, crc32c_b64(data))
        await env.redis.delete(K.lease(sid, ann.upload_id))
        uploads.append(ann.upload_id)
    abandoned, protected = uploads
    (root / "outbox" / f"{protected}.json").write_text("{}")
    sweeper = env.img.state.sweeper
    await sweeper.sweep()
    assert not orphan.exists()
    st = U.load(env.img.state.store, abandoned)
    assert st and st.lease_missing_since  # first sighting is recorded, nothing deleted yet
    clock.advance(16 * 60 * 1000)
    await sweeper.sweep()
    assert not (root / "tmp" / abandoned).exists()
    assert (root / "tmp" / protected).exists()
    (root / "outbox" / f"{protected}.json").unlink()
    # tmp of an ended session goes on the next sweep
    ann, data = await announce_new(host, sid, 22)
    await host.put_chunk(ann.upload_url, ann.upload_token, 0, data, crc32c_b64(data))
    await host.end(sid)
    await sweeper.sweep()
    assert not (root / "tmp" / ann.upload_id).exists()


async def test_recount_fixes_drift_and_stale_reservations(pair: tuple[str, PhotonClient, PhotonClient],
                                                          env: Stack) -> None:
    sid, host, member = pair
    await store(host, sid, 30)
    built = await build(member, sid, jpeg(31))
    ann = await announce_built(member, sid, built)
    good = await env.redis.hmget(K.session(sid), "stored_bytes", "reserved_bytes")
    await env.redis.hset(K.session(sid), mapping={"stored_bytes": 999_999, "reserved_bytes": 5})
    await jobs.recount(env.svc)
    assert await env.redis.hmget(K.session(sid), "stored_bytes", "reserved_bytes") == good
    # a reservation whose lease is gone is released once it is older than 60 s
    await env.redis.delete(K.lease(sid, ann.upload_id))
    clock.advance(61_000)
    await jobs.recount(env.svc)
    assert int(await env.redis.hget(K.session(sid), "reserved_bytes")) == 0
    assert await env.redis.hget(K.photos(sid), built.sha_hex) is None
    assert await env.redis.hget(K.session_keys(sid)[6], built.tag.hex()) is None  # its photo tag is free again
