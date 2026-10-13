"""Sandboxed image work, run as a subprocess so a hostile file can only exhaust this process.

    python -I -m photon.imageserver.decode_worker <mode> <src> <dst> <mime> <max_pixels> <px> <mem> <cpu_s>

mode: validate | thumb | preview. Prints one JSON line. Exit 0 ok, 10 invalid image, 11 transient failure.
Death by a signal (CPU or memory limit) or a timeout is read by the caller as a limit hit.
"""

from __future__ import annotations

import errno
import io
import json
import resource
import sys

EXIT_INVALID, EXIT_TRANSIENT = 10, 11
HEIC_BRANDS = {b"heic", b"heix", b"hevc", b"hevx", b"heim", b"heis"}
HEIF_BRANDS = {b"mif1", b"msf1"}
EQUIVALENT = {"image/heic": "heif", "image/heif": "heif"}


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


def same_type(declared: str, detected: str | None) -> bool:
    if detected is None:
        return False
    return declared == detected or EQUIVALENT.get(declared, declared) == EQUIVALENT.get(detected, detected)


def limit(mem: int, cpu_s: int) -> None:
    resource.setrlimit(resource.RLIMIT_AS, (mem, mem))
    resource.setrlimit(resource.RLIMIT_CPU, (cpu_s, cpu_s + 1))
    resource.setrlimit(resource.RLIMIT_FSIZE, (64 * 1024 * 1024,) * 2)
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def out(value: dict[str, object], code: int = 0) -> None:
    sys.stdout.write(json.dumps(value) + "\n")
    sys.stdout.flush()
    sys.exit(code)


def main(argv: list[str]) -> None:
    mode, src, dst, mime, max_pixels, px, mem, cpu_s = argv
    limit(int(mem), int(cpu_s))

    import pillow_heif
    from PIL import Image, ImageCms, ImageOps

    pillow_heif.register_heif_opener()
    Image.MAX_IMAGE_PIXELS = None  # replaced by the explicit pixel check below

    try:
        with open(src, "rb") as f:
            head = f.read(32)
        if mode == "validate":
            if not same_type(mime, detect(head)):
                out({"ok": False, "error": "declared type does not match the file"}, EXIT_INVALID)
            with Image.open(src) as source:
                w, h = source.size
                if w * h > int(max_pixels) or w <= 0 or h <= 0:
                    out({"ok": False, "error": "too many pixels"}, EXIT_INVALID)
                if getattr(source, "n_frames", 1) > 1:
                    out({"ok": False, "error": "animated or multi-frame"}, EXIT_INVALID)
                source.load()
                out({"ok": True, "format": source.format, "width": w, "height": h})
        else:
            quality = 70 if mode == "thumb" else 80
            with Image.open(src) as opened:
                im: Image.Image = ImageOps.exif_transpose(opened) or opened
                icc = opened.info.get("icc_profile")
                if icc:
                    try:
                        src_profile = ImageCms.ImageCmsProfile(io.BytesIO(icc))
                        converted = ImageCms.profileToProfile(im, src_profile, ImageCms.createProfile("sRGB"),
                                                              outputMode="RGB")
                        im = converted or im
                    except (ImageCms.PyCMSError, OSError, ValueError):
                        pass
                if im.mode not in ("RGB", "RGBA"):
                    im = im.convert("RGBA" if "A" in im.getbands() else "RGB")
                im.thumbnail((int(px), int(px)), Image.Resampling.LANCZOS)
                # A new image saved without exif=/icc_profile= carries no metadata.
                im.save(dst, "WEBP", quality=quality, method=4)
                out({"ok": True, "width": im.width, "height": im.height})
    except MemoryError:
        out({"ok": False, "error": "memory"}, EXIT_TRANSIENT)
    except OSError as e:
        if e.errno in (errno.ENOSPC, errno.EDQUOT, errno.EMFILE):
            out({"ok": False, "error": f"transient: {e}"}, EXIT_TRANSIENT)
        out({"ok": False, "error": f"invalid: {e}"}, EXIT_INVALID)
    except (SyntaxError, ValueError, Image.DecompressionBombError, EOFError) as e:
        out({"ok": False, "error": f"invalid: {e}"}, EXIT_INVALID)
    except Exception as e:  # any other decoder failure is a bad file, not our problem
        out({"ok": False, "error": f"invalid: {e!r}"}, EXIT_INVALID)


if __name__ == "__main__":
    main(sys.argv[1:])
