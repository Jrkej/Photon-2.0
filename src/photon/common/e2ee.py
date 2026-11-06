"""PHOTON end-to-end encryption, version 1: identity keys, sealed session keys, and the encrypted photo file.

The server never holds a key that opens anything. It stores sealed key bundles it cannot open and photo files it
cannot read. It parses only the file's plaintext header (epoch and section sizes) to validate an upload and to
serve the thumbnail and preview as byte prefixes. The full specification, with byte layouts, is e2ee.md.

Primitives: X25519 (key agreement), HKDF-SHA256, HMAC-SHA256, AES-256-GCM, SHA-256.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import struct
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

MIME = "application/vnd.photon.e2ee"  # the only type the server accepts; the real type is inside, encrypted
INNER_MIMES = ("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")

KEY_LEN = 32
TAG_LEN = 16  # AES-GCM tag
NONCE_LEN = 12

# Sealed key bundles
SEAL_INFO = b"photon/seal/v1"
BUNDLE_VERSION = 1
BUNDLE_LEN = 1 + 4 + KEY_LEN + KEY_LEN  # version, epoch, epoch key, tag key = 69
BOX_LEN = KEY_LEN + BUNDLE_LEN + TAG_LEN  # ephemeral public key, sealed bundle = 117

# The photo file
MAGIC = b"PHE1"
FILE_VERSION = 1
PREFIX_LEN = 4 + 1 + 4 + NONCE_LEN  # magic, version, epoch, wrap nonce = 21 (the locked key's AAD)
HEADER_LEN = PREFIX_LEN + KEY_LEN + TAG_LEN + 4 * 4  # + locked photo key + four section lengths = 85
SECTIONS = ("meta", "thumb", "preview", "original")
MAX_META_LEN = 4096 + TAG_LEN
TAG_INFO = b"photon/tag/v1"
FP_INFO = b"photon/fp/v1"


class E2eeError(Exception):
    """A malformed, tampered or undecryptable sealed box or photo file."""


# ---------------------------------------------------------------------------------------------- identity keys


def new_identity() -> X25519PrivateKey:
    return X25519PrivateKey.generate()


def identity_from_bytes(raw: bytes) -> X25519PrivateKey:
    if len(raw) != KEY_LEN:
        raise E2eeError("an identity key is 32 bytes")
    return X25519PrivateKey.from_private_bytes(raw)


def private_bytes(key: X25519PrivateKey) -> bytes:
    return key.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw,
                             serialization.NoEncryption())


def public_bytes(key: X25519PrivateKey) -> bytes:
    return key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def security_code(member_key: bytes) -> str:
    """A short fingerprint of a member's public key, for people to compare out of band."""
    digest = hashlib.sha256(FP_INFO + member_key).hexdigest()[:20]
    return " ".join(digest[i:i + 4] for i in range(0, 20, 4))


# ---------------------------------------------------------------------------------------------- session keys


@dataclass(frozen=True)
class Bundle:
    """What a sealed box carries: one epoch's key, and the session's tag key (the same in every epoch)."""

    epoch: int
    key: bytes
    tag_key: bytes

    def encode(self) -> bytes:
        return struct.pack(">BI", BUNDLE_VERSION, self.epoch) + self.key + self.tag_key

    @classmethod
    def decode(cls, raw: bytes) -> Bundle:
        if len(raw) != BUNDLE_LEN or raw[0] != BUNDLE_VERSION:
            raise E2eeError("not a version-1 key bundle")
        (epoch,) = struct.unpack(">I", raw[1:5])
        return cls(epoch, raw[5:37], raw[37:69])


def new_session_bundle() -> Bundle:
    """Epoch 1 of a new session: made by the creator's phone, never sent anywhere unsealed."""
    return Bundle(1, os.urandom(KEY_LEN), os.urandom(KEY_LEN))


def next_epoch_bundle(current: Bundle) -> Bundle:
    """A fresh key for the next epoch, keeping the session's tag key (so dedupe still works across epochs)."""
    return Bundle(current.epoch + 1, os.urandom(KEY_LEN), current.tag_key)


def _seal_aad(sid: str, member_id: str, epoch: int) -> bytes:
    return b"|".join((SEAL_INFO, sid.encode(), member_id.encode(), str(epoch).encode()))


def _seal_key(shared: bytes, eph_pub: bytes, recipient_pub: bytes) -> bytes:
    if shared == bytes(KEY_LEN):
        raise E2eeError("degenerate X25519 shared secret")
    return HKDF(algorithm=hashes.SHA256(), length=KEY_LEN, salt=eph_pub + recipient_pub,
                info=SEAL_INFO).derive(shared)


def seal(bundle: Bundle, recipient_key: bytes, sid: str, member_id: str,
         *, ephemeral: X25519PrivateKey | None = None) -> bytes:
    """Seals `bundle` for one member: only the holder of `recipient_key`'s private key can open it.
    The box is bound to the session, the recipient and the epoch, so it can't be replayed elsewhere."""
    if len(recipient_key) != KEY_LEN:
        raise E2eeError("a member key is 32 bytes")
    eph = ephemeral or X25519PrivateKey.generate()
    eph_pub = public_bytes(eph)
    try:
        shared = eph.exchange(X25519PublicKey.from_public_bytes(recipient_key))
    except ValueError as e:
        raise E2eeError("invalid member key") from e
    key = _seal_key(shared, eph_pub, recipient_key)
    sealed = AESGCM(key).encrypt(bytes(NONCE_LEN), bundle.encode(), _seal_aad(sid, member_id, bundle.epoch))
    return eph_pub + sealed


def open_box(box: bytes, identity: X25519PrivateKey, sid: str, member_id: str, epoch: int) -> Bundle:
    if len(box) != BOX_LEN:
        raise E2eeError("a sealed box is 117 bytes")
    eph_pub, sealed = box[:KEY_LEN], box[KEY_LEN:]
    try:
        shared = identity.exchange(X25519PublicKey.from_public_bytes(eph_pub))
    except ValueError as e:
        raise E2eeError("invalid ephemeral key") from e
    key = _seal_key(shared, eph_pub, public_bytes(identity))
    try:
        raw = AESGCM(key).decrypt(bytes(NONCE_LEN), sealed, _seal_aad(sid, member_id, epoch))
    except InvalidTag as e:
        raise E2eeError("sealed box does not open: wrong recipient, session or epoch, or tampered") from e
    bundle = Bundle.decode(raw)
    if bundle.epoch != epoch:
        raise E2eeError("sealed box carries another epoch")
    return bundle


# ---------------------------------------------------------------------------------------------- photo tag


def photo_tag(tag_key: bytes, original: bytes) -> bytes:
    """The session's dedupe identity of a photo: equal for the same photo whoever uploads it, and meaningless
    without the tag key (so the server can't test whether a known photo is in the session)."""
    return hmac.new(tag_key, TAG_INFO + hashlib.sha256(original).digest(), hashlib.sha256).digest()


# ---------------------------------------------------------------------------------------------- the photo file


@dataclass(frozen=True)
class Header:
    raw: bytes  # the 85 header bytes, which are also every section's AAD
    epoch: int
    wrap_nonce: bytes
    locked_key: bytes
    lengths: tuple[int, int, int, int]  # ciphertext lengths of meta, thumb, preview, original

    @property
    def size(self) -> int:
        return HEADER_LEN + sum(self.lengths)

    def offset(self, section: str) -> int:
        i = SECTIONS.index(section)
        return HEADER_LEN + sum(self.lengths[:i])

    def end(self, section: str) -> int:
        return self.offset(section) + self.lengths[SECTIONS.index(section)]

    def prefix_len(self, size: str) -> int:
        """Bytes a receiver needs for `size`: the header, the metadata and everything up to that section."""
        if size == "thumb":
            return self.end("thumb")
        if size == "preview":
            return self.end("preview")
        return self.size


def parse_header(data: bytes) -> Header:
    """Parses and checks the plaintext header. Needs only the first 85 bytes; never needs a key."""
    if len(data) < HEADER_LEN:
        raise E2eeError("too short for a PHOTON file header")
    if data[:4] != MAGIC or data[4] != FILE_VERSION:
        raise E2eeError("not a PHOTON version-1 encrypted file")
    (epoch,) = struct.unpack(">I", data[5:9])
    lengths = struct.unpack(">IIII", data[69:85])
    if epoch < 1:
        raise E2eeError("epoch must be at least 1")
    if any(n < TAG_LEN for n in lengths) or lengths[0] > MAX_META_LEN:
        raise E2eeError("section lengths are invalid")
    if lengths[1] == TAG_LEN or lengths[3] == TAG_LEN:
        raise E2eeError("the thumbnail and the original must not be empty")
    return Header(bytes(data[:HEADER_LEN]), epoch, bytes(data[9:21]), bytes(data[21:69]), lengths)


def _section_nonce(i: int) -> bytes:
    return i.to_bytes(NONCE_LEN, "big")


def encrypt_photo(
    bundle: Bundle,
    *,
    original: bytes,
    mime: str,
    width: int,
    height: int,
    taken_at_ms: int,
    thumb: bytes,
    preview: bytes = b"",
    photo_key: bytes | None = None,
    wrap_nonce: bytes | None = None,
) -> bytes:
    """Builds the encrypted file. A fresh random photo key per file: the same photo encrypted twice, even on two
    phones, never reuses an AES-GCM key and nonce. (photo_key and wrap_nonce are parameters only for test vectors.)"""
    if mime not in INNER_MIMES:
        raise E2eeError(f"unsupported image type {mime}")
    if not thumb or not original:
        raise E2eeError("the thumbnail and the original must not be empty")
    photo_key = photo_key or os.urandom(KEY_LEN)
    wrap_nonce = wrap_nonce or os.urandom(NONCE_LEN)
    meta = json.dumps({"v": 1, "mime": mime, "width": width, "height": height, "taken_at_ms": taken_at_ms},
                      separators=(",", ":"), sort_keys=True).encode()
    plaintexts = (meta, thumb, preview, original)
    prefix = MAGIC + struct.pack(">BI", FILE_VERSION, bundle.epoch) + wrap_nonce
    locked = AESGCM(bundle.key).encrypt(wrap_nonce, photo_key, prefix)
    header = prefix + locked + struct.pack(">IIII", *(len(p) + TAG_LEN for p in plaintexts))
    aead = AESGCM(photo_key)
    return header + b"".join(aead.encrypt(_section_nonce(i), p, header) for i, p in enumerate(plaintexts))


@dataclass
class Photo:
    epoch: int
    meta: dict[str, Any]
    thumb: bytes | None = None
    preview: bytes | None = None
    original: bytes | None = None


def unlock_photo_key(header: Header, keys: Mapping[int, bytes]) -> bytes:
    key = keys.get(header.epoch)
    if key is None:
        raise E2eeError(f"no key for epoch {header.epoch}")
    try:
        return AESGCM(key).decrypt(header.wrap_nonce, header.locked_key, header.raw[:PREFIX_LEN])
    except InvalidTag as e:
        raise E2eeError("photo key does not unlock: wrong session key or tampered header") from e


def decrypt_photo(data: bytes, keys: Mapping[int, bytes]) -> Photo:
    """Decrypts a whole file or a prefix (as served for size=thumb or preview). `keys` maps epoch to epoch key."""
    header = parse_header(data)
    aead = AESGCM(unlock_photo_key(header, keys))
    out: dict[str, bytes] = {}
    for i, name in enumerate(SECTIONS):
        start, end = header.offset(name), header.end(name)
        if end > len(data):
            if name == "meta":
                raise E2eeError("file is truncated before its metadata")
            break
        try:
            out[name] = aead.decrypt(_section_nonce(i), data[start:end], header.raw)
        except InvalidTag as e:
            raise E2eeError(f"{name} section does not decrypt: tampered or corrupt") from e
    try:
        meta = json.loads(out["meta"])
    except ValueError as e:
        raise E2eeError("metadata is not JSON") from e
    if not isinstance(meta, dict) or meta.get("mime") not in INNER_MIMES:
        raise E2eeError("metadata names an unsupported image type")
    return Photo(header.epoch, meta, out.get("thumb"), out.get("preview") or None, out.get("original"))
