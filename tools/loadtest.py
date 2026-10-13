#!/usr/bin/env python3
"""Load test (implementation 9.5): S sessions x M members. Every member listens; each uploads P photos.

    python tools/loadtest.py --sessions 20 --members 10 --photos 5

Measures the upload time (announce to STORED) and the end-to-end delivery time (from the start of an upload to each
other member's listener receiving its event; members often hear about a photo before the uploader's own complete
returns, because the event is appended before the image server answers), and counts failures. Uses the dev login
provider, so run it against a dev or staging stack. All clients come from one IP, so raise the per-IP limits on
the stack under test:

    PHOTON_OAUTH_PER_MINUTE=100000 PHOTON_JOIN_PER_MINUTE=100000 PHOTON_CREATE_PER_HOUR=100000 photon-dev
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import io
import random
import statistics
import time

from PIL import Image

from photon.client.api import PhotonClient
from photon.client.listener import Listener
from photon.client.uploader import upload_bytes
from photon.common.pb import photon_pb2 as pb


def photo(seed: int, width: int, height: int) -> bytes:
    rnd = random.Random(seed)
    img = Image.effect_noise((width, height), 40 + rnd.random() * 30).convert("RGB")
    img.putpixel((seed % width, (seed // width) % height), (rnd.randrange(256), 0, 0))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=85)
    return buf.getvalue()


def pct(values: list[float], p: float) -> float:
    if not values:
        return float("nan")
    values = sorted(values)
    return values[min(len(values) - 1, int(p / 100 * len(values)))]


async def run(args: argparse.Namespace) -> None:
    started_at: dict[bytes, float] = {}
    arrivals: dict[bytes, list[float]] = {}
    upload_s: list[float] = []
    outcomes: dict[str, int] = {}
    clients: list[PhotonClient] = []
    listeners: list[asyncio.Task[str]] = []
    stop = asyncio.Event()
    run_id = f"{int(time.time())}"

    async def session(s: int) -> list[tuple[PhotonClient, str]]:
        members = []
        host = PhotonClient(args.api, args.image)
        await host.login_dev(f"lt{run_id}-{s}-0")
        info = await host.create_session(max_members=min(args.members, 10))
        members.append((host, info.session_id))
        for m in range(1, args.members):
            c = PhotonClient(args.api, args.image)
            await c.login_dev(f"lt{run_id}-{s}-{m}")
            await c.join(code=info.join_code)
            members.append((c, info.session_id))
        return members

    print(f"setting up {args.sessions} sessions x {args.members} members ...")
    groups = await asyncio.gather(*(session(s) for s in range(args.sessions)))
    for group in groups:
        for c, sid in group:
            clients.append(c)

            async def on_event(ev: pb.PhotoEvent, me: str = c.uid) -> None:
                if ev.type == pb.PhotoEvent.PHOTO_STORED and ev.uploader_id != me:
                    arrivals.setdefault(ev.sha256, []).append(time.monotonic())

            lst = Listener(c, sid, on_event)
            listeners.append(asyncio.create_task(lst.run(stop)))
    await asyncio.sleep(1.0)

    print(f"uploading {args.sessions * args.members * args.photos} photos over ~{args.duration_s} s ...")
    t0 = time.monotonic()

    async def uploader(c: PhotonClient, sid: str, index: int) -> None:
        for p in range(args.photos):
            await asyncio.sleep(random.uniform(0, 2 * args.duration_s / max(1, args.photos)))
            data = await asyncio.to_thread(photo, index * 1000 + p, args.width, args.height)
            start = time.monotonic()
            started_at[hashlib.sha256(data).digest()] = start
            res = await upload_bytes(c, sid, data, "image/jpeg", int(time.time() * 1000))
            outcomes[res.outcome] = outcomes.get(res.outcome, 0) + 1
            if res.outcome == "STORED":
                upload_s.append(time.monotonic() - start)

    flat = [(c, sid) for group in groups for c, sid in group]
    await asyncio.gather(*(uploader(c, sid, i) for i, (c, sid) in enumerate(flat)))
    elapsed = time.monotonic() - t0
    await asyncio.sleep(3.0)  # let the last events arrive
    stop.set()
    await asyncio.gather(*listeners, return_exceptions=True)
    for c in clients:
        await c.aclose()

    expected = outcomes.get("STORED", 0) * (args.members - 1)
    delivery_s = [t - started_at[sha] for sha, ts in arrivals.items() if sha in started_at for t in ts]
    print(f"\nuploads: {outcomes}  in {elapsed:.1f} s  ({outcomes.get('STORED', 0) / elapsed:.1f} photos/s)")
    print(f"upload time (announce -> STORED): p50 {pct(upload_s, 50):.3f} s  p95 {pct(upload_s, 95):.3f} s  "
          f"max {max(upload_s, default=float('nan')):.3f} s")
    print(f"upload start -> delivered to each member: p50 {pct(delivery_s, 50):.3f} s  p95 {pct(delivery_s, 95):.3f} s"
          f"  deliveries {len(delivery_s)}/{expected}")
    if delivery_s:
        print(f"mean delivery {statistics.mean(delivery_s) * 1000:.1f} ms")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--api", default="http://localhost:8080")
    ap.add_argument("--image", default="http://localhost:8081")
    ap.add_argument("--sessions", type=int, default=10)
    ap.add_argument("--members", type=int, default=5)
    ap.add_argument("--photos", type=int, default=3)
    ap.add_argument("--duration-s", type=float, default=20.0)
    ap.add_argument("--width", type=int, default=1600)
    ap.add_argument("--height", type=int, default=1200)
    asyncio.run(run(ap.parse_args()))


if __name__ == "__main__":
    main()
