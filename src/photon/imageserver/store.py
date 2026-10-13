"""Disk layout and durable writes. Everything lives on one filesystem, so every rename is atomic.

    tmp/{upload_id}/state.json      authoritative upload state
    tmp/{upload_id}/chunks/{n}      received chunks
    tmp/{upload_id}/assembled       being hashed
    tmp/{upload_id}/staged/         verified upload, kept under its upload_id until the orchestrator accepts it
    photos/{sid}/{sha256}/          promoted: original, thumb.webp, preview.webp, meta.json
    outbox/{upload_id}.json         pending stored/rejected reports
    trash/                          renamed here, removed in the background
"""

from __future__ import annotations

import json
import os
import re
import shutil
import uuid
from pathlib import Path
from typing import Any

from photon.common import ids
from photon.common.errors import ApiError

UPLOAD_ID = re.compile(r"^[0-9a-f]{32}$")
SHA = re.compile(r"^[0-9a-f]{64}$")


def check_upload_id(value: str) -> str:
    if not UPLOAD_ID.match(value):
        raise ApiError("UNKNOWN_UPLOAD")
    return value


def check_sha(value: str) -> str:
    if not SHA.match(value):
        raise ApiError("INVALID_REQUEST", "Invalid hash")
    return value


def check_sid(value: str) -> str:
    if not ids.is_valid_sid(value):
        raise ApiError("INVALID_REQUEST", "Invalid session id")
    return value


def fsync_dir(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic_write(path: Path, data: bytes) -> None:
    tmp = path.with_name(f"{path.name}.{uuid.uuid4().hex[:8]}.tmp")
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)
    fsync_dir(path.parent)


def write_json(path: Path, value: Any) -> None:
    atomic_write(path, json.dumps(value, separators=(",", ":")).encode())


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_bytes())
    except (FileNotFoundError, json.JSONDecodeError):
        return None


class Store:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.tmp = root / "tmp"
        self.photos = root / "photos"
        self.outbox = root / "outbox"
        self.trash = root / "trash"
        for d in (self.tmp, self.photos, self.outbox, self.trash):
            d.mkdir(parents=True, exist_ok=True)

    def upload_dir(self, upload_id: str) -> Path:
        return self.tmp / check_upload_id(upload_id)

    def chunks_dir(self, upload_id: str) -> Path:
        return self.upload_dir(upload_id) / "chunks"

    def staged_dir(self, upload_id: str) -> Path:
        return self.upload_dir(upload_id) / "staged"

    def photo_dir(self, sid: str, sha: str) -> Path:
        return self.photos / check_sid(sid) / check_sha(sha)

    def outbox_file(self, upload_id: str) -> Path:
        return self.outbox / f"{check_upload_id(upload_id)}.json"

    def chunk_sizes(self, upload_id: str) -> dict[int, int]:
        out: dict[int, int] = {}
        d = self.chunks_dir(upload_id)
        if not d.exists():
            return out
        for entry in os.scandir(d):
            if entry.name.isdigit():
                out[int(entry.name)] = entry.stat().st_size
        return out

    def to_trash(self, path: Path) -> bool:
        """Atomically removes `path` from view, then deletes it. False when it did not exist."""
        if not path.exists():
            return False
        dest = self.trash / uuid.uuid4().hex
        try:
            os.replace(path, dest)
        except FileNotFoundError:
            return False
        shutil.rmtree(dest, ignore_errors=True)
        return True

    def clear_upload_files(self, upload_id: str) -> None:
        """Deletes an upload's chunks, assembled file and staged files, keeping state.json for repeated calls."""
        d = self.upload_dir(upload_id)
        for name in ("chunks", "staged"):
            if (d / name).exists():
                self.to_trash(d / name)
        (d / "assembled").unlink(missing_ok=True)

    def dir_bytes(self, path: Path) -> int:
        total = 0
        for root, _, files in os.walk(path):
            for f in files:
                try:
                    total += os.stat(os.path.join(root, f)).st_size
                except FileNotFoundError:
                    pass
        return total
