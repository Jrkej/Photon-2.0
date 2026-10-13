"""3.7 / 3.12: the sandboxed decoder accepts the allow-list and refuses hostile files without hurting the server."""

from __future__ import annotations

import io
import struct
import zlib
from pathlib import Path

import pillow_heif
import pytest
from PIL import Image

from photon.common.config import Settings
from photon.imageserver.decode import Decoder

pillow_heif.register_heif_opener()


def png_chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def raw_png(w: int, h: int, rows: int | None = None) -> bytes:
    """A solid RGB PNG built row by row (tiny on disk, w*h*3 bytes once decoded)."""
    z = zlib.compressobj(9)
    body = bytearray()
    row = b"\x00" + b"\x00" * (w * 3)
    for _ in range(h if rows is None else rows):
        body += z.compress(row)
    body += z.flush()
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", ihdr) + png_chunk(b"IDAT", bytes(body)) + png_chunk(b"IEND", b"")


def encode(img: Image.Image, fmt: str, **kw: object) -> bytes:
    buf = io.BytesIO()
    img.save(buf, fmt, **kw)
    return buf.getvalue()


@pytest.fixture
def decoder() -> Decoder:
    return Decoder(Settings(env="test", decode_concurrency=4))


def put(tmp_path: Path, name: str, data: bytes) -> Path:
    p = tmp_path / name
    p.write_bytes(data)
    return p


@pytest.mark.parametrize("fmt, mime", [("JPEG", "image/jpeg"), ("PNG", "image/png"), ("WEBP", "image/webp"),
                                       ("HEIF", "image/heic")])
async def test_allowed_formats_validate(decoder: Decoder, tmp_path: Path, fmt: str, mime: str) -> None:
    data = encode(Image.new("RGB", (40, 30), (200, 10, 10)), fmt)
    res = await decoder.run("validate", put(tmp_path, "x", data), None, mime)
    assert res.ok, res.info
    assert (res.info["width"], res.info["height"]) == (40, 30)


HOSTILE = {
    "header 30000x30000": (lambda: raw_png(30000, 30000, rows=1), "image/png"),
    "animated webp": (lambda: encode(Image.new("RGB", (8, 8)), "WEBP", save_all=True,
                                     append_images=[Image.new("RGB", (8, 8), (255, 0, 0))]), "image/webp"),
    "apng": (lambda: encode(Image.new("RGB", (8, 8)), "PNG", save_all=True,
                            append_images=[Image.new("RGB", (8, 8), (0, 255, 0))]), "image/png"),
    "truncated jpeg": (lambda: encode(Image.effect_noise((256, 256), 64).convert("RGB"), "JPEG")[:1500],
                       "image/jpeg"),
    "png declared as jpeg": (lambda: encode(Image.new("RGB", (8, 8)), "PNG"), "image/jpeg"),
    "gif (not allowed)": (lambda: encode(Image.new("RGB", (8, 8)), "GIF"), "image/jpeg"),
    "random bytes": (lambda: b"\xff\xd8\xff" + bytes(range(256)) * 8, "image/jpeg"),
    "empty": (lambda: b"", "image/jpeg"),
}


@pytest.mark.parametrize("name", sorted(HOSTILE))
async def test_hostile_files_are_refused(decoder: Decoder, tmp_path: Path, name: str) -> None:
    make, mime = HOSTILE[name]
    res = await decoder.run("validate", put(tmp_path, "x", make()), None, mime)
    assert not res.ok, (name, res)


async def test_multi_image_heic_is_refused(decoder: Decoder, tmp_path: Path) -> None:
    heif = pillow_heif.from_pillow(Image.new("RGB", (16, 16), (1, 2, 3)))
    heif.add_from_pillow(Image.new("RGB", (16, 16), (4, 5, 6)))
    buf = io.BytesIO()
    heif.save(buf)
    res = await decoder.run("validate", put(tmp_path, "x", buf.getvalue()), None, "image/heic")
    assert not res.ok


async def test_memory_limit_kills_only_the_worker(tmp_path: Path) -> None:
    """81 MP is under the pixel limit but needs ~250 MB to decode: with a 128 MB limit the worker fails."""
    small = Decoder(Settings(env="test", decode_mem_bytes=128 * 1024 * 1024))
    res = await small.run("validate", put(tmp_path, "big.png", raw_png(9000, 9000)), None, "image/png")
    assert not res.ok and res.kind in ("transient", "limit")
    roomy = Decoder(Settings(env="test"))
    assert (await roomy.run("validate", put(tmp_path, "ok.png", raw_png(300, 200)), None, "image/png")).ok


async def test_wall_clock_limit(tmp_path: Path) -> None:
    slow = Decoder(Settings(env="test", decode_wall_s=0.01))
    res = await slow.run("validate", put(tmp_path, "x.png", raw_png(50, 50)), None, "image/png")
    assert res.kind == "limit"


async def test_trailing_data_after_a_valid_jpeg_is_accepted(decoder: Decoder, tmp_path: Path) -> None:
    """Motion photos append a video after the JPEG; the design checks format and decodability, not trailers."""
    data = encode(Image.new("RGB", (16, 16)), "JPEG") + b"PK\x03\x04" + b"\x00" * 64
    assert (await decoder.run("validate", put(tmp_path, "x", data), None, "image/jpeg")).ok


async def test_thumbnail_applies_orientation_and_strips_metadata(decoder: Decoder, tmp_path: Path) -> None:
    img = Image.new("RGB", (64, 32), (0, 0, 255))
    exif = Image.Exif()
    exif[0x0112] = 6  # rotate 90 degrees when shown
    exif[0x8825] = {1: "N", 2: (52.0, 31.0, 0.0)}  # GPS
    src = put(tmp_path, "x.jpg", encode(img, "JPEG", exif=exif.tobytes()))
    dst = tmp_path / "thumb.webp"
    res = await decoder.run("thumb", src, dst, "image/jpeg", 320)
    assert res.ok, res.info
    with Image.open(dst) as t:
        assert t.format == "WEBP" and t.size == (32, 64)
        assert not t.info.get("exif") and not t.getexif()


async def test_preview_is_bounded(decoder: Decoder, tmp_path: Path) -> None:
    src = put(tmp_path, "x.png", raw_png(2400, 1200))
    dst = tmp_path / "p.webp"
    assert (await decoder.run("preview", src, dst, "image/png", 1600)).ok
    with Image.open(dst) as p:
        assert max(p.size) == 1600
