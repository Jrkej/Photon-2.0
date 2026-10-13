"""Phase 8: the design's limits table, rate limits, photo reports, and push as a hint."""

from __future__ import annotations

import asyncio
import hashlib

import pytest
from sqlalchemy import select

from photon import db
from photon.client.api import ClientError
from photon.client.listener import Listener
from photon.client.uploader import upload_bytes
from photon.common import clock
from photon.common.config import MIB, Settings
from tests.conftest import Login, Stack, jpeg


def test_defaults_match_the_design_limits_table() -> None:
    s = Settings(env="test")
    assert s.max_photo_bytes == 25 * MIB and s.max_pixels == 100_000_000
    assert s.max_members == 10 and s.session_quota_bytes == 1024 * MIB
    assert (s.max_live_uploads_user, s.max_live_uploads_session) == (3, 20)
    assert s.announce_per_minute == 20 and s.join_per_minute == 5 and s.max_strikes == 3
    assert s.retention_ms == 30 * 60 * 1000 and s.session_idle_ms == 12 * 3600 * 1000
    assert s.access_token_ttl_s == 15 * 60 and s.upload_token_ttl_s == 10 * 60 and s.refresh_grace_ms == 30_000
    assert s.allowed_mimes == ["image/jpeg", "image/png", "image/webp", "image/heic", "image/heif"]


async def test_announce_rate_limit(login: Login, env: Stack) -> None:
    env.settings.announce_per_minute = 3
    alice = await login("alice")
    sid = (await alice.create_session()).session_id
    for i in range(3):
        await alice.announce(sid, hashlib.sha256(bytes([i])).digest(), 10, "image/jpeg", clock.now_ms())
    with pytest.raises(ClientError) as e:
        await alice.announce(sid, hashlib.sha256(b"x").digest(), 10, "image/jpeg", clock.now_ms())
    assert (e.value.status, e.value.code, e.value.retryable) == (429, "RATE_LIMITED", True)
    assert 1 <= e.value.error.retry_after_s <= 60


async def test_join_rate_limit(login: Login, env: Stack) -> None:
    env.settings.join_per_minute = 2
    bob = await login("bob")
    for _ in range(2):
        with pytest.raises(ClientError) as e:
            await bob.join(code="00000000")
        assert e.value.code == "SESSION_NOT_FOUND"
    with pytest.raises(ClientError) as e:
        await bob.join(code="00000000")
    assert e.value.code == "RATE_LIMITED"


async def test_photo_report_is_recorded(login: Login, env: Stack) -> None:
    host, bob, stranger = await login("host"), await login("bob"), await login("stranger")
    info = await host.create_session()
    await bob.join(code=info.join_code)
    data = jpeg(1)
    assert (await upload_bytes(host, info.session_id, data, "image/jpeg", clock.now_ms())).outcome == "STORED"
    sha = hashlib.sha256(data).hexdigest()
    await bob.report(info.session_id, sha, "not appropriate")
    with pytest.raises(ClientError) as e:
        await stranger.report(info.session_id, sha, "spam")
    assert e.value.code == "NOT_A_MEMBER"
    engine = db.make_engine(env.settings.database_url)
    async with engine.connect() as conn:
        rows = (await conn.execute(select(db.photo_reports).where(db.photo_reports.c.sha256 == sha))).all()
    await engine.dispose()
    assert len(rows) == 1 and rows[0].reporter_id == bob.uid and rows[0].uploader_id == host.uid


class Recorder:
    def __init__(self) -> None:
        self.sent: list[tuple[str, str, dict[str, str]]] = []

    async def send(self, platform: str, token: str, data: dict[str, str]) -> bool:
        self.sent.append((platform, token, data))
        return token != "dead-token-000"


async def test_push_hint_only_for_members_without_a_socket(login: Login, env: Stack) -> None:
    host, online, offline = await login("host"), await login("online"), await login("offline")
    await online.register_device("android", "online-token-1")
    await offline.register_device("android", "offline-token-1")
    await offline.register_device("ios", "dead-token-000")
    info = await host.create_session()
    sid = info.session_id
    for c in (online, offline):
        await c.join(code=info.join_code)
    recorder = Recorder()
    real = env.svc.pusher.sender
    env.svc.pusher.sender = recorder
    listener = Listener(online, sid)
    task = asyncio.create_task(listener.run())
    try:
        await asyncio.wait_for(listener.connected.wait(), 5)
        for seed in (1, 2):  # two photos in a row: one push per user per window
            assert (await upload_bytes(host, sid, jpeg(seed), "image/jpeg", clock.now_ms())).outcome == "STORED"
        await asyncio.sleep(0.3)
        tokens = sorted(t for _, t, _ in recorder.sent)
        assert tokens == ["dead-token-000", "offline-token-1"]  # host is offline too but has no device
        assert all(d == {"sid": sid, "kind": "stored"} for _, _, d in recorder.sent)
        engine = db.make_engine(env.settings.database_url)
        async with engine.connect() as conn:
            left = (await conn.execute(select(db.devices.c.push_token)
                                       .where(db.devices.c.user_id == offline.uid))).scalars().all()
        await engine.dispose()
        assert left == ["offline-token-1"]  # the invalid token was forgotten
    finally:
        env.svc.pusher.sender = real
        await host.end(sid)
        await asyncio.wait_for(task, 5)
