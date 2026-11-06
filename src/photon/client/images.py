"""The image work the server no longer does (e2ee.md 6.1): detect the type, then make the thumbnail and preview that
go, encrypted, into the photo file. Runs on the uploader's own photo, before encryption."""

from __future__ import annotations

import io
from dataclasses import dataclass

import pillow_heif
from PIL import Image, ImageCms, ImageOps

pillow_heif.register_heif_opener()

THUMB_PX, PREVIEW_PX = 320, 1600
MAX_PIXELS = 100_000_000
HEIC_BRANDS = {b"heic", b"heix", b"hevc", b"hevx", b"heim", b"heis"}
HEIF_BRANDS = {b"mif1", b"msf1"}
EQUIVALENT = {"image/heic": "heif", "image/heif": "heif"}


class ImageError(ValueError):
    """Not an image this app shares: unknown type, a type other than declared, too many pixels, or undecodable."""


@dataclass(frozen=True)
class Prepared:
    mime: str
    width: int  # after EXIF orientation, as a viewer shows it
    height: int
    thumb: bytes  # WebP, longest side at most 320 px, no metadata
    preview: bytes  # WebP, longest side at most 1600 px; empty when the original is no larger


def detect(head: bytes) -> str | None:
    if head.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "image/webp"
    if head[4:8] == b"ftyp":
        if head[8:12] in HEIC_BRANDS:
            return "image/heic"
        if head[8:12] in HEIF_BRANDS:
            return "image/heif"
    return None


def same_type(declared: str, detected: str) -> bool:
    return declared == detected or EQUIVALENT.get(declared, declared) == EQUIVALENT.get(detected, detected)


def _webp(im: Image.Image, px: int, quality: int) -> bytes:
    small = im.copy()
    small.thumbnail((px, px), Image.Resampling.LANCZOS)
    out = io.BytesIO()
    small.save(out, "WEBP", quality=quality, method=4)  # a new image saved without exif= or icc_profile=
    return out.getvalue()


def prepare(original: bytes, declared: str | None = None) -> Prepared:
    mime = detect(original[:32])
    if mime is None:
        raise ImageError("not a JPEG, PNG, WebP, HEIC or HEIF image")
    if declared and not same_type(declared, mime):
        raise ImageError(f"declared {declared} but the file is {mime}")
    try:
        with Image.open(io.BytesIO(original)) as opened:
            w, h = opened.size
            if w <= 0 or h <= 0 or w * h > MAX_PIXELS:
                raise ImageError("too many pixels")
            if getattr(opened, "n_frames", 1) > 1:
                raise ImageError("animated or multi-frame")
            im: Image.Image = ImageOps.exif_transpose(opened) or opened
            icc = opened.info.get("icc_profile")
            if icc:
                try:
                    profile = ImageCms.ImageCmsProfile(io.BytesIO(icc))
                    im = ImageCms.profileToProfile(im, profile, ImageCms.createProfile("sRGB"), outputMode="RGB") or im
                except (ImageCms.PyCMSError, OSError, ValueError):
                    pass
            if im.mode not in ("RGB", "RGBA"):
                im = im.convert("RGBA" if "A" in im.getbands() else "RGB")
            thumb = _webp(im, THUMB_PX, 70)
            preview = _webp(im, PREVIEW_PX, 80) if max(im.size) > PREVIEW_PX else b""
            return Prepared(mime, im.width, im.height, thumb, preview)
    except ImageError:
        raise
    except (OSError, SyntaxError, ValueError, EOFError, Image.DecompressionBombError) as e:
        raise ImageError(f"undecodable: {e}") from e
