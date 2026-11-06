"""The E2EE v1 reference implementation (e2ee.md) against its fixed test vectors, plus round trips and tampering.
Every client (Python, Kotlin, Swift) must reproduce tests/vectors/e2ee_v1.json byte for byte."""

from __future__ import annotations

import hashlib
import json
import struct
from pathlib import Path

import pytest

from photon.client.keyring import Keyring
from photon.common import e2ee
from tools import e2ee_vectors

VECTORS = json.loads((Path(__file__).resolve().parents[1] / "vectors" / "e2ee_v1.json").read_text())


def test_vectors_file_is_current() -> None:
    """The committed vectors are what the reference implementation produces now."""
    assert e2ee_vectors.build() == VECTORS


def test_seal_vector() -> None:
    v, ids = VECTORS["seal"], VECTORS["identities"]
    bob = e2ee.identity_from_bytes(bytes.fromhex(ids["bob_private"]))
    assert e2ee.public_bytes(bob).hex() == ids["bob_public"]
    bundle = e2ee.Bundle(v["epoch"], bytes.fromhex(v["epoch_key"]), bytes.fromhex(v["tag_key"]))
    assert bundle.encode().hex() == v["bundle"] and len(bundle.encode()) == e2ee.BUNDLE_LEN
    eph = e2ee.identity_from_bytes(bytes.fromhex(v["ephemeral_private"]))
    box = e2ee.seal(bundle, bytes.fromhex(ids["bob_public"]), v["session_id"], v["member_id"], ephemeral=eph)
    assert box.hex() == v["box"] and len(box) == e2ee.BOX_LEN
    assert e2ee.open_box(box, bob, v["session_id"], v["member_id"], v["epoch"]) == bundle
    assert e2ee.security_code(bytes.fromhex(ids["bob_public"])) == ids["bob_security_code"]


def test_photo_vector() -> None:
    v = VECTORS["photo"]
    bundle = e2ee.Bundle(v["epoch"], bytes.fromhex(v["epoch_key"]), bytes.fromhex(v["tag_key"]))
    file = e2ee.encrypt_photo(
        bundle, original=bytes.fromhex(v["original"]), mime=v["mime"], width=v["width"], height=v["height"],
        taken_at_ms=v["taken_at_ms"], thumb=bytes.fromhex(v["thumb"]), preview=bytes.fromhex(v["preview"]),
        photo_key=bytes.fromhex(v["photo_key"]), wrap_nonce=bytes.fromhex(v["wrap_nonce"]))
    assert file.hex() == v["file"] and hashlib.sha256(file).hexdigest() == v["file_sha256"]
    header = e2ee.parse_header(file)
    assert list(header.lengths) == v["header_section_lengths"] and header.size == len(file)
    assert header.prefix_len("thumb") == v["prefix_len_thumb"]
    assert header.prefix_len("preview") == v["prefix_len_preview"]
    photo = e2ee.decrypt_photo(file, {bundle.epoch: bundle.key})
    assert json.dumps(photo.meta, separators=(",", ":"), sort_keys=True) == v["meta_json"]
    assert e2ee.photo_tag(bundle.tag_key, bytes.fromhex(v["original"])).hex() == v["photo_tag"]


def test_header_layout() -> None:
    v = VECTORS["photo"]
    file = bytes.fromhex(v["file"])
    assert file[:4] == b"PHE1" and file[4] == 1 and struct.unpack(">I", file[5:9])[0] == v["epoch"]
    assert file[9:21].hex() == v["wrap_nonce"]
    assert list(struct.unpack(">IIII", file[69:85])) == v["header_section_lengths"]
    assert e2ee.HEADER_LEN == 85 and e2ee.PREFIX_LEN == 21


def test_prefixes_decrypt_without_the_rest() -> None:
    v = VECTORS["photo"]
    file, keys = bytes.fromhex(v["file"]), {1: bytes.fromhex(v["epoch_key"])}
    thumb = e2ee.decrypt_photo(file[:v["prefix_len_thumb"]], keys)
    assert thumb.thumb == bytes.fromhex(v["thumb"]) and thumb.preview is None and thumb.original is None
    preview = e2ee.decrypt_photo(file[:v["prefix_len_preview"]], keys)
    assert preview.preview == bytes.fromhex(v["preview"]) and preview.original is None
    with pytest.raises(e2ee.E2eeError, match="truncated"):
        e2ee.decrypt_photo(file[:e2ee.HEADER_LEN + 3], keys)


def test_round_trip_with_random_keys() -> None:
    bundle = e2ee.new_session_bundle()
    a = e2ee.encrypt_photo(bundle, original=b"photo" * 100, mime="image/png", width=10, height=20, taken_at_ms=5,
                           thumb=b"t" * 40)
    b = e2ee.encrypt_photo(bundle, original=b"photo" * 100, mime="image/png", width=10, height=20, taken_at_ms=5,
                           thumb=b"t" * 40)
    assert a != b  # a fresh photo key and wrap nonce for every file: no AES-GCM key and nonce pair repeats
    photo = e2ee.decrypt_photo(a, {1: bundle.key})
    assert photo.original == b"photo" * 100 and photo.preview is None
    assert photo.meta == {"v": 1, "mime": "image/png", "width": 10, "height": 20, "taken_at_ms": 5}


def test_next_epoch_keeps_the_tag_key() -> None:
    one = e2ee.new_session_bundle()
    two = e2ee.next_epoch_bundle(one)
    assert two.epoch == 2 and two.key != one.key and two.tag_key == one.tag_key
    file = e2ee.encrypt_photo(two, original=b"x" * 10, mime="image/jpeg", width=1, height=1, taken_at_ms=0,
                              thumb=b"t")
    with pytest.raises(e2ee.E2eeError, match="no key for epoch 2"):
        e2ee.decrypt_photo(file, {1: one.key})
    with pytest.raises(e2ee.E2eeError, match="does not unlock"):
        e2ee.decrypt_photo(file, {2: one.key})


def test_every_byte_is_authenticated() -> None:
    bundle = e2ee.new_session_bundle()
    file = e2ee.encrypt_photo(bundle, original=b"o" * 64, mime="image/jpeg", width=1, height=1, taken_at_ms=0,
                              thumb=b"t" * 8, preview=b"p" * 8)
    keys = {1: bundle.key}
    for i in range(len(file)):
        bad = bytearray(file)
        bad[i] ^= 0x01
        with pytest.raises(e2ee.E2eeError):
            e2ee.decrypt_photo(bytes(bad), keys)


@pytest.mark.parametrize("change", ["magic", "version", "epoch0", "short_len", "empty_thumb", "big_meta"])
def test_bad_headers_are_refused_without_a_key(change: str) -> None:
    bundle = e2ee.new_session_bundle()
    file = bytearray(e2ee.encrypt_photo(bundle, original=b"o", mime="image/jpeg", width=1, height=1, taken_at_ms=0,
                                        thumb=b"t"))
    if change == "magic":
        file[0:4] = b"JFIF"
    elif change == "version":
        file[4] = 2
    elif change == "epoch0":
        file[5:9] = bytes(4)
    elif change == "short_len":
        file[69:73] = struct.pack(">I", 3)
    elif change == "empty_thumb":
        file[73:77] = struct.pack(">I", e2ee.TAG_LEN)
    else:
        file[69:73] = struct.pack(">I", e2ee.MAX_META_LEN + 1)
    with pytest.raises(e2ee.E2eeError):
        e2ee.parse_header(bytes(file))


def test_encrypt_refuses_unknown_types_and_empty_sections() -> None:
    bundle = e2ee.new_session_bundle()
    with pytest.raises(e2ee.E2eeError):
        e2ee.encrypt_photo(bundle, original=b"o", mime="image/gif", width=1, height=1, taken_at_ms=0, thumb=b"t")
    with pytest.raises(e2ee.E2eeError):
        e2ee.encrypt_photo(bundle, original=b"o", mime="image/jpeg", width=1, height=1, taken_at_ms=0, thumb=b"")


def test_box_is_bound_to_recipient_session_and_epoch() -> None:
    bob, eve = e2ee.new_identity(), e2ee.new_identity()
    bundle = e2ee.new_session_bundle()
    box = e2ee.seal(bundle, e2ee.public_bytes(bob), "S1", "bob")
    assert e2ee.open_box(box, bob, "S1", "bob", 1) == bundle
    for args in ((eve, "S1", "bob", 1), (bob, "S2", "bob", 1), (bob, "S1", "eve", 1), (bob, "S1", "bob", 2)):
        with pytest.raises(e2ee.E2eeError):
            e2ee.open_box(box, *args)
    with pytest.raises(e2ee.E2eeError):
        e2ee.seal(bundle, bytes(32), "S1", "bob")  # a low-order point gives an all-zero shared secret


def test_photo_tag_needs_the_tag_key() -> None:
    d1, d2 = e2ee.new_session_bundle().tag_key, e2ee.new_session_bundle().tag_key
    assert e2ee.photo_tag(d1, b"photo") == e2ee.photo_tag(d1, b"photo")
    assert e2ee.photo_tag(d1, b"photo") != e2ee.photo_tag(d2, b"photo")
    assert e2ee.photo_tag(d1, b"photo") != hashlib.sha256(b"photo").digest()


def test_keyring_persists_and_merges(tmp_path: Path) -> None:
    path = tmp_path / "alice.keys.json"
    a = Keyring(path)
    assert path.stat().st_mode & 0o777 == 0o600
    one = e2ee.new_session_bundle()
    a.add("S", one)
    b = Keyring(path)  # a second process on the same profile
    assert b.member_key == a.member_key and b.bundles("S") == {1: one}
    two = e2ee.next_epoch_bundle(one)
    b.add("S", two)
    assert a.has("S", 2)  # a miss reads the file again
    a.add("T", e2ee.new_session_bundle())  # saving merges, so b's epoch 2 survives
    assert set(Keyring(path).bundles("S")) == {1, 2}
    a.forget("S")
    assert Keyring(path).bundles("S") == {}
