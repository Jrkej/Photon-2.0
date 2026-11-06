"""The whole path with the reference client: login, create, join, the key relay, encrypted upload, live event,
download and decrypt, recall, end."""

from __future__ import annotations

import asyncio
import hashlib

from photon.client.listener import Listener
from photon.client.uploader import upload_bytes
from photon.common import clock, e2ee
from photon.common.pb import photon_pb2 as pb
from tests.conftest import Login, eventually, jpeg


async def test_two_members_share_a_photo(login: Login) -> None:
    alice, bob = await login("alice"), await login("bob")
    info = await alice.create_session()
    assert info.role == "host" and len(info.join_code) == 8
    joined = await bob.join(code=info.join_code)
    assert joined.session_id == info.session_id and joined.role == "member"
    sid = info.session_id
    assert not bob.keys.bundles(sid)  # joining brings no key: a member must seal it for Bob

    # Bob listens first. When Alice's listener starts, it sees Bob without the key and seals it to his public key;
    # Bob's listener hears KEY_SHARED and opens it. The server relays the box but can't open it.
    alice_listener, bob_listener = Listener(alice, sid), Listener(bob, sid)
    stop = asyncio.Event()
    tasks = [asyncio.create_task(bob_listener.run(stop))]
    await asyncio.wait_for(bob_listener.connected.wait(), 5)
    tasks.append(asyncio.create_task(alice_listener.run(stop)))

    async def bob_has_the_key() -> bool:
        return bob.keys.has(sid, 1)

    await eventually(bob_has_the_key)
    assert bob.keys.bundles(sid) == alice.keys.bundles(sid)

    data = jpeg(1)
    res = await upload_bytes(alice, sid, data, "image/jpeg", clock.now_ms())
    assert res.outcome == "STORED", res
    sha = bytes.fromhex(res.sha256)

    async def bob_has_it() -> bool:
        return sha in bob_listener.hashes

    await eventually(bob_has_it)
    stored = next(e for e in bob_listener.events if e.type == pb.PhotoEvent.PHOTO_STORED)
    assert stored.uploader_id == alice.uid and stored.photo_tag == res.photo_tag and stored.key_epoch == 1

    full = await bob.download(sid, sha.hex(), "full")
    assert full.headers["cache-control"] == "private, no-store"
    assert full.headers["content-type"] == "application/octet-stream"
    assert len(full.content) == stored.size and hashlib.sha256(full.content).digest() == sha
    assert data not in full.content  # the server holds only ciphertext
    photo = await bob.download_photo(sid, sha.hex(), photo_tag=stored.photo_tag)
    assert photo.original == data and photo.meta["mime"] == "image/jpeg"
    thumb = await bob.download_photo(sid, sha.hex(), "thumb")
    assert thumb.thumb and thumb.thumb[:4] == b"RIFF" and thumb.original is None
    raw_thumb = await bob.download(sid, sha.hex(), "thumb")
    assert raw_thumb.content == full.content[:e2ee.parse_header(full.content).prefix_len("thumb")]

    # The same photo again, from another member: a different encrypted file, the same photo tag, one copy per session
    again = await upload_bytes(bob, sid, data, "image/jpeg", clock.now_ms())
    assert again.outcome == "ALREADY_UPLOADED" and again.sha256 != res.sha256 and again.photo_tag == res.photo_tag

    await alice.recall(sid, sha.hex())

    async def recalled() -> bool:
        return any(e.type == pb.PhotoEvent.PHOTO_RECALLED for e in bob_listener.events)

    await eventually(recalled)
    await alice.end(sid)
    assert await asyncio.wait_for(asyncio.gather(*tasks), 5) == ["SESSION_ENDED", "SESSION_ENDED"]
