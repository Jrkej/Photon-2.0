"""End-to-end encryption through the real stack (e2ee.md): key relay, rotation, the encrypted file, and what the
server can and cannot see."""

from __future__ import annotations

import hashlib

import pytest

from photon.client import images
from photon.client.api import ClientError, PhotonClient
from photon.client.uploader import build_file, upload_bytes, upload_file
from photon.common import clock, e2ee
from photon.common.pb import photon_pb2 as pb
from photon.orchestrator import keys as K
from tests.conftest import Built, Login, Stack, announce_built, build, group, jpeg, share_keys


async def test_three_people_join_one_after_another_and_all_read_every_photo(login: Login) -> None:
    alice, bob, carol = await login("alice"), await login("bob"), await login("carol")
    info = await alice.create_session()
    sid = info.session_id
    assert alice.keys.has(sid, 1)

    await bob.join(code=info.join_code)
    # Bob holds nothing until a member seals the key for him: he can't upload or read yet.
    assert (await upload_bytes(bob, sid, jpeg(1), "image/jpeg", clock.now_ms())).outcome == "KEY_REQUIRED"
    await alice.sync_keys(sid)  # Alice sees Bob without key epoch 1 and seals it to his public key
    await bob.sync_keys(sid)
    assert bob.keys.bundles(sid) == alice.keys.bundles(sid)

    first = await upload_bytes(bob, sid, jpeg(2), "image/jpeg", clock.now_ms())
    assert first.outcome == "STORED" and first.key_epoch == 1

    await carol.join(code=info.join_code)
    await bob.sync_keys(sid)  # any member who holds the key can seal it for the newcomer, not only the host
    await carol.sync_keys(sid)
    state = await carol.key_state(sid)
    assert [(s.epoch, s.from_member_id) for s in state.sealed] == [(1, bob.uid)]

    photo = await carol.download_photo(sid, first.sha256, photo_tag=first.photo_tag)
    assert photo.original == jpeg(2) and photo.meta["mime"] == "image/jpeg"


async def test_server_holds_only_ciphertext(login: Login, env: Stack) -> None:
    sid, (alice, bob) = await group(login, "alice", "bob")
    data = jpeg(3, (900, 700))
    res = await upload_bytes(alice, sid, data, "image/jpeg", clock.now_ms())
    assert res.outcome == "STORED"
    stored = env.settings.storage_root / "photos" / sid / res.sha256 / "file"
    on_disk = stored.read_bytes()
    assert hashlib.sha256(on_disk).hexdigest() == res.sha256  # the checksum is of the encrypted file
    assert on_disk[:4] == e2ee.MAGIC and data not in on_disk and data[:64] not in on_disk
    assert not any(p.name in ("original", "thumb.webp", "preview.webp") for p in stored.parent.iterdir())
    # Nothing the server keeps opens a box or a file: Redis has public keys and sealed boxes only.
    boxes = await env.redis.hgetall(K.session_keys(sid)[7])
    for bundle in alice.keys.bundles(sid).values():
        assert bundle.key.hex() not in str(boxes) and bundle.tag_key.hex() not in str(boxes)
    resp = await bob.download(sid, res.sha256)
    assert resp.headers["content-type"] == "application/octet-stream" and resp.content == on_disk


async def test_thumbnail_and_preview_are_prefixes_the_receiver_decrypts(login: Login) -> None:
    sid, (alice, bob) = await group(login, "alice", "bob")
    data = jpeg(4, (2000, 1500))
    res = await upload_bytes(alice, sid, data, "image/jpeg", clock.now_ms())
    full = (await bob.download(sid, res.sha256)).content
    header = e2ee.parse_header(full)
    thumb_bytes = (await bob.download(sid, res.sha256, "thumb")).content
    assert thumb_bytes == full[:header.prefix_len("thumb")]
    thumb = await bob.download_photo(sid, res.sha256, "thumb")
    assert thumb.thumb and thumb.thumb[:4] == b"RIFF" and thumb.original is None
    preview = await bob.download_photo(sid, res.sha256, "preview")
    assert preview.preview and preview.original is None
    assert preview.meta["width"] == 2000 and preview.meta["height"] == 1500


async def test_small_original_is_its_own_preview(login: Login) -> None:
    sid, (alice, bob) = await group(login, "alice", "bob")
    res = await upload_bytes(alice, sid, jpeg(5), "image/jpeg", clock.now_ms())
    photo = await bob.download_photo(sid, res.sha256, "preview")
    assert photo.preview is None and photo.original == jpeg(5)


async def test_leave_requires_rotation_and_the_leaver_gets_no_new_key(login: Login) -> None:
    sid, (alice, bob, carol) = await group(login, "alice", "bob", "carol")
    old = alice.keys.bundles(sid)[1]
    await carol.leave(sid)
    state = await alice.key_state(sid)
    assert state.rotation_required and state.epoch == 1

    built = await build(bob, sid, jpeg(6))
    with pytest.raises(ClientError) as e:
        await announce_built(bob, sid, built)
    assert (e.value.status, e.value.code) == (409, "KEY_ROTATION_REQUIRED")

    # upload_bytes rotates by itself, then re-encrypts with the new epoch
    res = await upload_bytes(bob, sid, jpeg(6), "image/jpeg", clock.now_ms())
    assert res.outcome == "STORED" and res.key_epoch == 2
    new = bob.keys.bundles(sid)[2]
    assert new.key != old.key and new.tag_key == old.tag_key  # same tag key: dedupe still works across epochs
    await alice.sync_keys(sid)
    assert alice.keys.bundles(sid)[2] == new
    assert (await alice.download_photo(sid, res.sha256)).original == jpeg(6)
    # Alice's own copy of an epoch-1 photo, uploaded now, must use epoch 2
    with pytest.raises(ClientError) as e:
        await announce_built(alice, sid, await build_with(alice, sid, jpeg(7), epoch=1))
    assert e.value.code == "KEY_EPOCH_STALE"


async def build_with(c: PhotonClient, sid: str, original: bytes, epoch: int) -> Built:
    """Encrypts with a chosen epoch's key, not the newest."""
    bundle = c.keys.bundles(sid)[epoch]
    return Built(build_file(bundle, original, images.prepare(original), 0), e2ee.photo_tag(bundle.tag_key, original),
                 epoch, original)


async def test_kicked_member_is_left_out_of_the_new_epoch(login: Login) -> None:
    sid, (host, bob, mallory) = await group(login, "host", "bob", "mallory")
    await host.kick(sid, mallory.uid)
    assert await host.rotate_keys(sid) == 2
    state = await host.key_state(sid)
    assert {m.member_id for m in state.members} == {host.uid, bob.uid}
    assert all(m.key_epoch == 2 for m in state.members) and not state.rotation_required
    await bob.sync_keys(sid)
    assert bob.keys.has(sid, 2)
    with pytest.raises(ClientError) as e:
        await mallory.key_state(sid)
    assert e.value.code == "NOT_A_MEMBER"


async def test_concurrent_rotation_has_one_winner(login: Login) -> None:
    sid, (alice, bob, carol) = await group(login, "alice", "bob", "carol")
    await carol.leave(sid)
    state = await alice.sync_keys(sid)
    base = alice.keys.bundles(sid)[1]
    mine = e2ee.Bundle(2, b"\x01" * 32, base.tag_key)
    boxes = {m.member_id: e2ee.seal(mine, m.member_key, sid, m.member_id) for m in state.members}
    await alice.put_keys(sid, 2, boxes, rotate=True)
    alice.keys.add(sid, mine)
    with pytest.raises(ClientError) as e:  # Bob rotates from the same state a moment later
        await bob.put_keys(sid, 2, {alice.uid: e2ee.seal(mine, alice.keys.member_key, sid, alice.uid)}, rotate=True)
    assert (e.value.status, e.value.code) == (409, "EPOCH_TAKEN")
    assert await bob.rotate_keys(sid) == 2  # nothing left to rotate: Bob takes Alice's epoch 2
    assert bob.keys.bundles(sid)[2] == mine


async def test_rotation_must_seal_for_every_member(login: Login) -> None:
    sid, (alice, bob, _carol, dave) = await group(login, "alice", "bob", "carol", "dave")
    await dave.leave(sid)
    nxt = e2ee.Bundle(2, b"\x02" * 32, alice.keys.bundles(sid)[1].tag_key)
    with pytest.raises(ClientError) as e:
        await alice.put_keys(sid, 2, {bob.uid: e2ee.seal(nxt, bob.keys.member_key, sid, bob.uid)}, rotate=True)
    assert e.value.code == "INVALID_REQUEST"
    assert (await alice.key_state(sid)).epoch == 1


async def test_last_member_rotates_alone(login: Login) -> None:
    sid, (alice, bob) = await group(login, "alice", "bob")
    await bob.leave(sid)
    res = await upload_bytes(alice, sid, jpeg(8), "image/jpeg", clock.now_ms())
    assert res.outcome == "STORED" and res.key_epoch == 2


async def test_sharing_rules(login: Login) -> None:
    alice, bob, carol = await login("alice"), await login("bob"), await login("carol")
    info = await alice.create_session()
    sid = info.session_id
    await bob.join(code=info.join_code)
    await carol.join(code=info.join_code)
    bundle = alice.keys.bundles(sid)[1]
    box = e2ee.seal(bundle, carol.keys.member_key, sid, carol.uid)
    with pytest.raises(ClientError) as e:  # Bob holds no epoch: he can't share one
        await bob.put_keys(sid, 1, {carol.uid: box})
    assert (e.value.status, e.value.code) == (403, "KEY_REQUIRED")
    with pytest.raises(ClientError) as e:  # an epoch that doesn't exist yet
        await alice.put_keys(sid, 2, {carol.uid: box})
    assert e.value.code == "KEY_EPOCH_STALE"
    with pytest.raises(ClientError) as e:  # a box of the wrong size
        await alice.put_keys(sid, 1, {carol.uid: box[:-1]})
    assert e.value.code == "INVALID_REQUEST"
    first = await alice.put_keys(sid, 1, {carol.uid: box})
    assert first.stored == 1
    again = await alice.put_keys(sid, 1, {carol.uid: e2ee.seal(bundle, carol.keys.member_key, sid, carol.uid)})
    assert again.stored == 0  # the first sealer wins


async def test_a_box_opens_only_for_its_member_session_and_epoch(login: Login) -> None:
    sid, (alice, bob) = await group(login, "alice", "bob")
    box = (await bob.key_state(sid)).sealed[0].box
    with pytest.raises(e2ee.E2eeError):
        e2ee.open_box(box, alice.keys.identity, sid, bob.uid, 1)  # someone else's private key
    with pytest.raises(e2ee.E2eeError):
        e2ee.open_box(box, bob.keys.identity, "Other" + sid[5:], bob.uid, 1)
    with pytest.raises(e2ee.E2eeError):
        e2ee.open_box(box, bob.keys.identity, sid, bob.uid, 2)


async def test_new_install_rekeys_and_members_seal_again(login: Login, env: Stack) -> None:
    sid, (alice, bob) = await group(login, "alice", "bob")
    reinstalled = PhotonClient(env.api_url, env.image_url, tokens=bob.tokens)  # same user, new identity key
    try:
        info = await alice.session(sid)
        await reinstalled.join(code=info.join_code)
        assert not (await reinstalled.key_state(sid)).sealed  # the old install's boxes are gone
        await share_keys(sid, alice, reinstalled)
        assert reinstalled.keys.has(sid, 1)
    finally:
        await reinstalled.aclose()


async def test_same_photo_from_two_members_dedupes_by_tag(login: Login) -> None:
    sid, (alice, bob) = await group(login, "alice", "bob")
    data = jpeg(9)
    a = await upload_bytes(alice, sid, data, "image/jpeg", clock.now_ms())
    b = await upload_bytes(bob, sid, data, "image/jpeg", clock.now_ms())
    assert a.outcome == "STORED" and b.outcome == "ALREADY_UPLOADED"
    assert a.sha256 != b.sha256 and a.photo_tag == b.photo_tag  # different files, the same photo
    snap = await bob.snapshot(sid)
    assert [p.photo_tag for p in snap.live_photos] == [a.photo_tag] and snap.key_epoch == 1


async def test_uploader_rebuilding_its_file_replaces_its_own_reservation(login: Login) -> None:
    sid, (alice,) = await group(login, "alice")
    first = await build(alice, sid, jpeg(10))
    ann = await announce_built(alice, sid, first)
    assert ann.result == pb.AnnounceResponse.UPLOAD
    second = await build(alice, sid, jpeg(10))  # the file was lost: a new photo key, a new hash, the same tag
    assert second.sha != first.sha and second.tag == first.tag
    res = await upload_file(alice, sid, second.file, clock.now_ms(), second.tag, second.epoch)
    assert res.outcome == "STORED"


async def test_receiver_detects_a_mislabelled_tag(login: Login) -> None:
    sid, (alice, bob) = await group(login, "alice", "bob")
    real = await build(alice, sid, jpeg(11))
    blocked_tag = (await build(alice, sid, jpeg(12))).tag  # claims another photo's tag to block it
    res = await upload_file(alice, sid, real.file, clock.now_ms(), blocked_tag, real.epoch)
    assert res.outcome == "STORED"
    with pytest.raises(e2ee.E2eeError, match="photo tag"):
        await bob.download_photo(sid, res.sha256, photo_tag=blocked_tag)


@pytest.mark.parametrize("mime", ["image/jpeg", ""])
async def test_plaintext_announce_is_refused(login: Login, mime: str) -> None:
    sid, (alice,) = await group(login, "alice")
    data = jpeg(13)
    with pytest.raises(ClientError) as e:
        await alice.announce(sid, hashlib.sha256(data).digest(), len(data), mime, clock.now_ms(), b"\0" * 32, 1)
    assert (e.value.status, e.value.code) == (415, "UNSUPPORTED_FORMAT")


async def test_plaintext_file_announced_as_encrypted_is_rejected(login: Login, env: Stack) -> None:
    sid, (alice,) = await group(login, "alice")
    data = jpeg(14)
    res = await upload_file(alice, sid, data, clock.now_ms(), b"\x07" * 32, 1)
    assert res.outcome == "INVALID_IMAGE"
    assert not any((env.settings.storage_root / "photos").rglob("file"))


async def test_header_epoch_must_match_the_announce(login: Login) -> None:
    sid, (alice, _bob, carol) = await group(login, "alice", "bob", "carol")
    await carol.leave(sid)
    await alice.rotate_keys(sid)
    stale = await build_with(alice, sid, jpeg(15), epoch=1)
    res = await upload_file(alice, sid, stale.file, clock.now_ms(), stale.tag, 2)  # announced as epoch 2
    assert res.outcome == "INVALID_IMAGE"


async def test_tampered_file_does_not_decrypt(login: Login) -> None:
    sid, (alice,) = await group(login, "alice")
    built = await build(alice, sid, jpeg(16))
    keys = alice.keys.epoch_keys(sid)
    assert e2ee.decrypt_photo(built.file, keys).original == jpeg(16)
    for i in (5, 30, 80, e2ee.HEADER_LEN + 3, len(built.file) - 1):
        bad = bytearray(built.file)
        bad[i] ^= 1
        with pytest.raises(e2ee.E2eeError):
            e2ee.decrypt_photo(bytes(bad), keys)
