"""Writes the E2EE version-1 test vectors (tests/vectors/e2ee_v1.json) from fixed inputs.

Every client implementation (Python, Kotlin, Swift) must reproduce these bytes exactly: the sealed box from the
fixed ephemeral key, the photo file from the fixed photo key and wrap nonce, the photo tag and the security code.
Run from the repository root: python tools/e2ee_vectors.py
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from photon.common import e2ee


def h(label: str) -> bytes:
    return hashlib.sha256(label.encode()).digest()


def build() -> dict[str, object]:
    alice = e2ee.identity_from_bytes(h("alice identity"))
    bob = e2ee.identity_from_bytes(h("bob identity"))
    ephemeral = e2ee.identity_from_bytes(h("ephemeral"))
    bundle = e2ee.Bundle(1, h("epoch 1 key"), h("session tag key"))
    sid, bob_id = "VECTORsession0000000001", "22222222-2222-4222-8222-222222222222"
    box = e2ee.seal(bundle, e2ee.public_bytes(bob), sid, bob_id, ephemeral=ephemeral)
    assert e2ee.open_box(box, bob, sid, bob_id, 1) == bundle

    original = bytes(range(256)) * 4
    thumb, preview = b"thumbnail bytes " * 8, b"preview bytes " * 16
    photo_key, wrap_nonce = h("photo key"), h("wrap nonce")[:12]
    file = e2ee.encrypt_photo(bundle, original=original, mime="image/jpeg", width=4032, height=3024,
                              taken_at_ms=1_793_700_000_000, thumb=thumb, preview=preview,
                              photo_key=photo_key, wrap_nonce=wrap_nonce)
    header = e2ee.parse_header(file)
    photo = e2ee.decrypt_photo(file, {1: bundle.key})
    assert (photo.original, photo.thumb, photo.preview) == (original, thumb, preview)

    return {
        "description": "PHOTON E2EE v1 test vectors. All byte values are lowercase hex. See e2ee.md.",
        "identities": {
            "alice_private": h("alice identity").hex(),
            "alice_public": e2ee.public_bytes(alice).hex(),
            "bob_private": h("bob identity").hex(),
            "bob_public": e2ee.public_bytes(bob).hex(),
            "bob_security_code": e2ee.security_code(e2ee.public_bytes(bob)),
        },
        "seal": {
            "session_id": sid,
            "member_id": bob_id,
            "epoch": bundle.epoch,
            "epoch_key": bundle.key.hex(),
            "tag_key": bundle.tag_key.hex(),
            "bundle": bundle.encode().hex(),
            "ephemeral_private": h("ephemeral").hex(),
            "box": box.hex(),
        },
        "photo": {
            "epoch": bundle.epoch,
            "epoch_key": bundle.key.hex(),
            "original": original.hex(),
            "thumb": thumb.hex(),
            "preview": preview.hex(),
            "mime": "image/jpeg",
            "width": 4032,
            "height": 3024,
            "taken_at_ms": 1_793_700_000_000,
            "meta_json": photo.meta and json.dumps(photo.meta, separators=(",", ":"), sort_keys=True),
            "photo_key": photo_key.hex(),
            "wrap_nonce": wrap_nonce.hex(),
            "file": file.hex(),
            "file_sha256": hashlib.sha256(file).hexdigest(),
            "header_section_lengths": list(header.lengths),
            "prefix_len_thumb": header.prefix_len("thumb"),
            "prefix_len_preview": header.prefix_len("preview"),
            "tag_key": bundle.tag_key.hex(),
            "photo_tag": e2ee.photo_tag(bundle.tag_key, original).hex(),
        },
    }


def main() -> None:
    out = Path(__file__).resolve().parent.parent / "tests" / "vectors" / "e2ee_v1.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(build(), indent=2) + "\n")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
