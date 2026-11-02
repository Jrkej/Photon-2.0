"""Benchmark harness: shared helpers and a session-wide report written to tests/bench/results/.

Benchmarks run on the same stack as the tests (real Redis, embedded Postgres, the three services on random ports,
all in one process), so they never touch a running dev backend. Run them with scripts/bench.sh."""

from __future__ import annotations

import io
import json
import os
import platform
import statistics
import subprocess
import time
from collections.abc import Iterator
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import pytest
from PIL import Image, ImageChops, ImageFilter

RESULTS = Path(__file__).parent / "results"


def pct(samples: list[float], p: float) -> float:
    if not samples:
        return float("nan")
    s = sorted(samples)
    k = (len(s) - 1) * p / 100
    lo, hi = int(k), min(int(k) + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def latency(samples_s: list[float]) -> dict[str, float]:
    """Milliseconds: p50, p95, p99, mean, max."""
    ms = [x * 1000 for x in samples_s]
    return {"n": len(ms), "p50_ms": pct(ms, 50), "p95_ms": pct(ms, 95), "p99_ms": pct(ms, 99),
            "mean_ms": statistics.fmean(ms) if ms else float("nan"), "max_ms": max(ms, default=float("nan"))}


def photo_like(width: int, height: int, seed: int = 0, noise: float = 0.12, quality: int = 90) -> bytes:
    """A JPEG that compresses roughly like a camera photo: smooth gradients plus some sensor-like noise."""
    base = [Image.linear_gradient("L").rotate(angle).resize((width, height), Image.Resampling.BILINEAR)
            for angle in (seed % 360, (seed * 37 + 90) % 360, (seed * 71 + 200) % 360)]
    img = Image.merge("RGB", base).filter(ImageFilter.GaussianBlur(3))
    grain = Image.effect_noise((width, height), 80).convert("RGB")
    img = Image.blend(img, ImageChops.add(img, grain, scale=2.0), noise)
    img.putpixel((seed % width, (seed // width) % height), (seed % 256, 255 - seed % 256, 7))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=quality)
    return buf.getvalue()


@dataclass
class Report:
    started: float = field(default_factory=time.time)
    sections: list[dict[str, Any]] = field(default_factory=list)

    def add(self, name: str, rows: list[dict[str, Any]], note: str = "") -> None:
        self.sections.append({"name": name, "rows": rows, "note": note})
        print(f"\n=== {name}")
        for row in rows:
            print("   ", "  ".join(f"{k}={_fmt(v)}" for k, v in row.items()))
        if note:
            print("   ", note)


def _fmt(v: Any) -> str:
    if isinstance(v, float):
        return f"{v:,.2f}" if abs(v) < 100 else f"{v:,.0f}"
    return str(v)


def machine() -> dict[str, str]:
    cpu = "unknown"
    try:
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.startswith("model name"):
                cpu = line.split(":", 1)[1].strip()
                break
    except OSError:
        pass
    mem = ""
    try:
        kb = int(next(line for line in Path("/proc/meminfo").read_text().splitlines()
                      if line.startswith("MemTotal")).split()[1])
        mem = f"{kb / 1024 / 1024:.0f} GB"
    except (OSError, StopIteration, ValueError):
        pass
    redis_v = ""
    try:
        out = subprocess.run([str(Path(__file__).resolve().parents[2] / ".devtools/redis/bin/redis-server"),
                              "--version"], capture_output=True, text=True, timeout=5).stdout
        redis_v = out.split()[2].removeprefix("v=")
    except (OSError, IndexError, subprocess.SubprocessError):
        pass
    return {"cpu": cpu, "threads": str(os.cpu_count()), "memory": mem, "python": platform.python_version(),
            "redis": redis_v, "os": platform.platform()}


def to_markdown(report: Report, info: dict[str, str]) -> str:
    lines = ["# PHOTON benchmark results", "",
             f"Run {time.strftime('%Y-%m-%d %H:%M', time.localtime(report.started))} · "
             f"{info['cpu']}, {info['threads']} threads, {info['memory']} · Python {info['python']} · "
             f"Redis {info['redis']}", "",
             "Everything runs on one machine in one process (Redis and Postgres alongside), so these are "
             "relative numbers for this build, not production capacity.", ""]
    for s in report.sections:
        lines += [f"## {s['name']}", ""]
        rows = s["rows"]
        if rows:
            cols = list(rows[0].keys())
            lines.append("| " + " | ".join(cols) + " |")
            lines.append("| " + " | ".join("---" for _ in cols) + " |")
            for r in rows:
                lines.append("| " + " | ".join(_fmt(r.get(c, "")) for c in cols) + " |")
            lines.append("")
        if s["note"]:
            lines += [s["note"], ""]
    return "\n".join(lines)


@pytest.fixture(scope="session")
def bench() -> Iterator[Report]:
    report = Report()
    yield report
    if not report.sections:
        return
    info = machine()
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "latest.json").write_text(json.dumps({"machine": info, "started": report.started,
                                                    "sections": report.sections}, indent=2))
    (RESULTS / "latest.md").write_text(to_markdown(report, info))
    print(f"\nResults written to {RESULTS / 'latest.md'} and latest.json")
