"""Runs decode_worker in a subprocess with memory, CPU-time and wall-clock limits."""

from __future__ import annotations

import asyncio
import contextlib
import json
import os
import signal
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Literal

from photon.common.config import Settings

Kind = Literal["ok", "invalid", "transient", "limit"]


@dataclass
class DecodeResult:
    kind: Kind
    info: dict[str, Any] = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return self.kind == "ok"


class Decoder:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.sem = asyncio.Semaphore(settings.decode_concurrency)

    async def run(self, mode: str, src: Path, dst: Path | None, mime: str, px: int = 0) -> DecodeResult:
        s = self.settings
        async with self.sem:
            proc = await asyncio.create_subprocess_exec(
                sys.executable, "-I", "-m", "photon.imageserver.decode_worker",
                mode, str(src), str(dst or ""), mime, str(s.max_pixels), str(px),
                str(s.decode_mem_bytes), str(s.decode_cpu_s),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                stdin=asyncio.subprocess.DEVNULL,
                env={"PATH": os.environ.get("PATH", "/usr/bin:/bin")},
                start_new_session=True,
            )
            try:
                stdout, _ = await asyncio.wait_for(proc.communicate(), s.decode_wall_s)
            except TimeoutError:
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(proc.pid, signal.SIGKILL)
                await proc.wait()
                return DecodeResult("limit", {"error": "wall-clock limit"})
        info: dict[str, Any] = {}
        with contextlib.suppress(ValueError, IndexError):
            info = json.loads(stdout.decode().strip().splitlines()[-1])
        if proc.returncode == 0:
            return DecodeResult("ok", info)
        if proc.returncode == 10:
            return DecodeResult("invalid", info)
        if proc.returncode == 11:
            return DecodeResult("transient", info)
        return DecodeResult("limit", {"error": f"exit {proc.returncode}", **info})
