"""The whole path with the reference client: login, create, join, upload, live event, download, recall, end."""

from __future__ import annotations

import asyncio
import hashlib

from photon.client.listener import Listener
from photon.client.uploader import upload_bytes
from photon.common import clock
from photon.common.pb import photon_pb2 as pb
from tests.conftest import Login, eventually, jpeg


async def test_two_members_share_a_photo(login: Login) -> None:
    alice, bob = await login("alice"), await login("bob")
    info = await alice.create_session()
    assert info.role == "host" and len(info.join_code) == 8
    joined = await bob.join(code=info.join_code)
    assert joined.session_id == info.session_id and joined.role == "member"
    sid = info.session_id

    listener = Listener(bob, sid)
    stop = asyncio.Event()
    task = asyncio.create_task(listener.run(stop))
    await asyncio.wait_for(listener.connected.wait(), 5)

    data = jpeg(1)
    res = await upload_bytes(alice, sid, data, "image/jpeg", clock.now_ms())
    assert res.outcome == "STORED", res
    sha = hashlib.sha256(data).digest()

    async def bob_has_it() -> bool:
        return sha in listener.hashes

    await eventually(bob_has_it)
    stored = next(e for e in listener.events if e.type == pb.PhotoEvent.PHOTO_STORED)
    assert stored.uploader_id == alice.uid and stored.size == len(data)

    full = await bob.download(sid, sha.hex(), "full")
    assert full.content == data
    assert full.headers["cache-control"] == "private, no-store"
    thumb = await bob.download(sid, sha.hex(), "thumb")
    assert thumb.headers["content-type"] == "image/webp" and thumb.content[:4] == b"RIFF"

    # The same bytes again: one copy per session
    again = await upload_bytes(bob, sid, data, "image/jpeg", clock.now_ms())
    assert again.outcome == "ALREADY_UPLOADED"

    await alice.recall(sid, sha.hex())

    async def recalled() -> bool:
        return any(e.type == pb.PhotoEvent.PHOTO_RECALLED for e in listener.events)

    await eventually(recalled)
    await alice.end(sid)
    assert await asyncio.wait_for(task, 5) == "SESSION_ENDED"
