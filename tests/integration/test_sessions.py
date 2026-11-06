"""Phase 2: create, join, leave, kick, end, the one-session pointer, idle expiry, races with the end."""

from __future__ import annotations

import asyncio

import pytest

from photon.client.api import ClientError
from photon.common import clock, e2ee
from photon.common.pb import photon_pb2 as pb
from photon.orchestrator import jobs
from photon.orchestrator import keys as K
from tests.conftest import Login, Stack, announce_built, build, jpeg


async def test_create_and_join_by_code_and_invite(login: Login) -> None:
    alice, bob, carol = await login("alice"), await login("bob"), await login("carol")
    info = await alice.create_session()
    assert info.invite_url.startswith("https://photon.test/j/") and info.qr_payload == info.invite_url
    assert info.host_id == alice.uid
    messy = info.join_code.lower()[:4] + "-" + info.join_code.lower()[4:]
    assert (await bob.join(code=messy)).session_id == info.session_id
    token = info.invite_url.rsplit("/", 1)[1]
    joined = await carol.join(invite_token=token)
    assert set(joined.member_ids) == {alice.uid, bob.uid, carol.uid}
    assert (await bob.join(code=info.join_code)).role == "member"  # joining again is idempotent
    members = {m.member_id: m for m in (await alice.key_state(info.session_id)).members}
    assert members[bob.uid].member_key == bob.keys.member_key and members[alice.uid].key_epoch == 1
    assert members[carol.uid].key_epoch == 0  # nobody has sealed the key for Carol yet


@pytest.mark.parametrize("member_key", [b"", b"\1" * 31, b"\1" * 33])
async def test_create_and_join_need_a_member_key(login: Login, member_key: bytes) -> None:
    """Every member sends its X25519 public key, so members can seal the session key for it (e2ee.md 5)."""
    alice, bob = await login("alice"), await login("bob")
    with pytest.raises(ClientError) as e:
        await alice.call("POST", "/v1/sessions", pb.CreateSessionRequest(member_key=member_key), pb.SessionInfo)
    assert (e.value.status, e.value.code) == (400, "INVALID_REQUEST")
    info = await alice.create_session()
    with pytest.raises(ClientError) as e:
        await bob.call("POST", "/v1/sessions/join", pb.JoinRequest(code=info.join_code, member_key=member_key),
                       pb.SessionInfo)
    assert (e.value.status, e.value.code) == (400, "INVALID_REQUEST")
    assert set((await alice.session(info.session_id)).member_ids) == {alice.uid}


async def test_unknown_code(login: Login) -> None:
    bob = await login("bob")
    with pytest.raises(ClientError) as e:
        await bob.join(code="ZZZZZZZZ")
    assert (e.value.status, e.value.code) == (404, "SESSION_NOT_FOUND")


async def test_full_session_admits_exactly_capacity(login: Login, env: Stack) -> None:
    host = await login("host")
    info = await host.create_session(max_members=3)
    users = [await login(f"u{i}") for i in range(10)]
    results = await asyncio.gather(*(u.join(code=info.join_code) for u in users), return_exceptions=True)
    ok = [u for u, r in zip(users, results, strict=True) if not isinstance(r, Exception)]
    refused = [r for r in results if isinstance(r, ClientError)]
    assert len(ok) == 2 and len(refused) == 8
    assert all(r.code == "FORBIDDEN" and "full" in r.error.message.lower() for r in refused)
    # every refused user's pointer was released: they can create their own session
    loser = next(u for u in users if u not in ok)
    assert await env.redis.get(K.user_session(loser.uid)) is None
    await loser.create_session()


async def test_one_session_per_user(login: Login, env: Stack) -> None:
    alice, bob = await login("alice"), await login("bob")
    results = await asyncio.gather(alice.create_session(), alice.create_session(), return_exceptions=True)
    ok = [r for r in results if not isinstance(r, Exception)]
    errors = [r for r in results if isinstance(r, ClientError)]
    assert len(ok) == 1 and len(errors) == 1 and errors[0].code == "ALREADY_IN_SESSION"
    assert len([k async for k in env.redis.scan_iter("code:*")]) == 1  # no orphan code
    other = await bob.create_session()
    with pytest.raises(ClientError) as e:
        await alice.join(code=other.join_code)
    assert e.value.code == "ALREADY_IN_SESSION"
    mine = await alice.current_session()  # the refused client can find the session it is in, and leave it
    assert mine.session_id == ok[0].session_id
    await alice.leave(mine.session_id)
    with pytest.raises(ClientError) as e:
        await alice.current_session()
    assert e.value.code == "SESSION_NOT_FOUND"
    assert (await alice.join(code=other.join_code)).session_id == other.session_id


async def test_pointer_to_an_ended_session_is_overwritten(login: Login, env: Stack) -> None:
    alice, bob = await login("alice"), await login("bob")
    s1 = await alice.create_session()
    await bob.join(code=s1.join_code)
    await env.redis.hset(K.session(s1.session_id), "status", "ENDED")  # ended without clean-up (crash)
    assert await env.redis.get(K.user_session(bob.uid)) == s1.session_id
    s2 = await bob.create_session()
    assert await env.redis.get(K.user_session(bob.uid)) == s2.session_id


async def test_host_leaves_and_last_member_ends(login: Login) -> None:
    a, b, c = await login("a"), await login("b"), await login("c")
    info = await a.create_session()
    sid = info.session_id
    await b.join(code=info.join_code)
    await asyncio.sleep(0.01)
    await c.join(code=info.join_code)
    cursor = (await b.snapshot(sid)).cursor
    await a.leave(sid)
    assert (await b.session(sid)).host_id == b.uid  # the longest-present member
    batch = await b.events_since(sid, cursor)
    assert [e.member_id for e in batch.events if e.type == pb.PhotoEvent.HOST_CHANGED] == [b.uid]
    await a.leave(sid)  # idempotent
    await b.leave(sid)
    assert (await c.session(sid)).role == "host"
    await c.leave(sid)
    with pytest.raises(ClientError) as e:
        await c.snapshot(sid)
    assert e.value.code == "SESSION_ENDED"


async def test_kick(login: Login, env: Stack) -> None:
    host, bob, eve = await login("host"), await login("bob"), await login("eve")
    info = await host.create_session()
    sid = info.session_id
    await bob.join(code=info.join_code)
    await eve.join(code=info.join_code)
    await host.sync_keys(sid)
    await eve.sync_keys(sid)
    built = await build(eve, sid, jpeg(7))
    await announce_built(eve, sid, built)
    assert int(await env.redis.hget(K.session(sid), "reserved_bytes")) == len(built.file)
    with pytest.raises(ClientError) as e:
        await bob.kick(sid, eve.uid)
    assert e.value.code == "FORBIDDEN"
    await host.kick(sid, eve.uid)
    assert int(await env.redis.hget(K.session(sid), "reserved_bytes")) == 0
    assert await env.redis.get(K.user_session(eve.uid)) is None
    with pytest.raises(ClientError) as e:
        await eve.join(code=info.join_code)
    assert e.value.code == "FORBIDDEN"
    with pytest.raises(ClientError) as e:
        await eve.snapshot(sid)
    assert e.value.code == "NOT_A_MEMBER"
    state = await host.key_state(sid)  # Eve still holds epoch 1: the next upload must rotate it away
    assert state.rotation_required and eve.uid not in {m.member_id for m in state.members}


async def test_end_is_host_only_and_tombstoned(login: Login, env: Stack) -> None:
    host, bob = await login("host"), await login("bob")
    info = await host.create_session()
    sid = info.session_id
    await bob.join(code=info.join_code)
    with pytest.raises(ClientError) as e:
        await bob.end(sid)
    assert e.value.code == "FORBIDDEN"
    await host.end(sid)
    await host.end(sid)  # idempotent
    for call in (bob.snapshot(sid), bob.announce(sid, b"x" * 32, 10, e2ee.MIME, clock.now_ms(), b"t" * 32, 1),
                 bob.key_state(sid)):
        with pytest.raises(ClientError) as e:
            await call
        assert (e.value.status, e.value.code) == (410, "SESSION_ENDED")
    with pytest.raises(ClientError) as e:
        await bob.join(code=info.join_code)  # the code was revoked with the session
    assert e.value.code == "SESSION_NOT_FOUND"
    ttl = await env.redis.pttl(K.session(sid))
    assert 0 < ttl <= 3_600_000  # the 1-hour tombstone
    assert await env.redis.get(K.user_session(bob.uid)) is None


async def test_joins_race_the_end(login: Login, env: Stack) -> None:
    users = [await login(f"r{i}") for i in range(4)]
    for _ in range(25):
        host = await login("racehost")
        info = await host.create_session()
        sid = info.session_id
        results = await asyncio.gather(host.end(sid), *(u.join(code=info.join_code) for u in users),
                                       return_exceptions=True)
        for r in results[1:]:
            assert not isinstance(r, Exception) or (isinstance(r, ClientError) and
                                                    r.code in ("SESSION_ENDED", "SESSION_NOT_FOUND")), r
        # Every member that got in joined before SESSION_ENDED: nothing succeeds after the end
        entries = await env.redis.xrange(K.events(sid))
        types = [f["t"] for _, f in entries]
        assert types[-1] == "SESSION_ENDED" and types.count("SESSION_ENDED") == 1
        for u in users:
            if await env.redis.get(K.user_session(u.uid)) == sid:
                raise AssertionError("a pointer to the ended session survived the clean-up")


async def test_idle_sessions_end(login: Login, env: Stack) -> None:
    a, b = await login("a"), await login("b")
    idle = await a.create_session()
    busy = await b.create_session()
    clock.advance(env.settings.session_idle_ms - 60_000)
    await env.svc.touch(busy.session_id, force=True)
    clock.advance(120_000)
    await jobs.sweep_sessions(env.svc)
    with pytest.raises(ClientError) as e:
        await a.snapshot(idle.session_id)
    assert e.value.code == "SESSION_ENDED"
    assert (await b.snapshot(busy.session_id)).cursor


async def test_rotate_invites(login: Login) -> None:
    host, bob, carol = await login("host"), await login("bob"), await login("carol")
    info = await host.create_session()
    await bob.join(code=info.join_code)
    with pytest.raises(ClientError) as e:
        await bob.rotate_invites(info.session_id)
    assert e.value.code == "FORBIDDEN"
    new = await host.rotate_invites(info.session_id)
    assert new.join_code != info.join_code
    with pytest.raises(ClientError):
        await carol.join(code=info.join_code)
    assert (await carol.join(code=new.join_code)).session_id == info.session_id
