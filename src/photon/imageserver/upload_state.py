"""The upload state machine, persisted in tmp/{upload_id}/state.json before every response.

RECEIVING -> VERIFYING -> REPORTING -> STORED
failures: REJECTED; orchestrator answers: SUPERSEDED (409), ENDED (410).
"""

from __future__ import annotations

import asyncio
import fcntl
import os
import weakref
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import asdict, dataclass, field, fields
from typing import Any

from photon.common import clock
from photon.imageserver.store import Store, read_json, write_json

RECEIVING, VERIFYING, REPORTING = "RECEIVING", "VERIFYING", "REPORTING"
STORED, REJECTED, SUPERSEDED, ENDED = "STORED", "REJECTED", "SUPERSEDED", "ENDED"
FINAL = frozenset({STORED, REJECTED, SUPERSEDED, ENDED})


@dataclass
class UploadState:
    upload_id: str
    sid: str
    sha256: str
    size: int
    mime: str
    taken_at_ms: int
    uploader: str
    state: str = RECEIVING
    created_at: int = field(default_factory=clock.now_ms)
    updated_at: int = 0
    lease_missing_since: int = 0
    key_epoch: int = 0
    result: dict[str, Any] = field(default_factory=dict)


def load(store: Store, upload_id: str) -> UploadState | None:
    raw = read_json(store.upload_dir(upload_id) / "state.json")
    if raw is None:
        return None
    known = {f.name for f in fields(UploadState)}
    return UploadState(**{k: v for k, v in raw.items() if k in known})


def save(store: Store, st: UploadState) -> None:
    st.updated_at = clock.now_ms()
    d = store.upload_dir(st.upload_id)
    d.mkdir(parents=True, exist_ok=True)
    write_json(d / "state.json", asdict(st))


_locks: weakref.WeakValueDictionary[str, asyncio.Lock] = weakref.WeakValueDictionary()


@asynccontextmanager
async def locked(store: Store, upload_id: str) -> AsyncIterator[None]:
    """One upload at a time: an asyncio lock in this process plus flock for any other worker process."""
    lock = _locks.get(upload_id)
    if lock is None:
        lock = asyncio.Lock()
        _locks[upload_id] = lock
    async with lock:
        d = store.upload_dir(upload_id)
        d.mkdir(parents=True, exist_ok=True)
        fd = os.open(d / ".lock", os.O_CREAT | os.O_RDWR, 0o600)
        try:
            await asyncio.to_thread(fcntl.flock, fd, fcntl.LOCK_EX)
            yield
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)
