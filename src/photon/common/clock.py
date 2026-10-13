"""Unix milliseconds. Every timestamp in PHOTON comes from here, so tests can move time forward."""

from __future__ import annotations

import time

_offset_ms = 0


def now_ms() -> int:
    return time.time_ns() // 1_000_000 + _offset_ms


def advance(ms: int) -> None:
    global _offset_ms
    _offset_ms += ms


def reset() -> None:
    global _offset_ms
    _offset_ms = 0
