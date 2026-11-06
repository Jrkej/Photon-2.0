"""Receive side: snapshot, then the WebSocket from the snapshot's cursor, with dedupe by event id and hash.
Delivery is at-least-once; dropping what was already processed makes the effect exactly-once.

The listener also does key maintenance (e2ee.md 5.3): on start, when a member joins, when a key is shared with this
member and when the key rotates, it fetches sealed keys and seals the ones it holds for members who lack them."""

from __future__ import annotations

import asyncio
import random
from collections.abc import Awaitable, Callable

from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

from photon.client.api import ClientError, PhotonClient
from photon.common.pb import photon_pb2 as pb

OnEvent = Callable[[pb.PhotoEvent], Awaitable[None]]
STOP_CODES = {4403: "NOT_A_MEMBER", 4410: "SESSION_ENDED", 4401: "UNAUTHENTICATED"}


def id_tuple(event_id: str) -> tuple[int, int]:
    ms, seq = event_id.split("-")
    return int(ms), int(seq)


class Listener:
    def __init__(self, client: PhotonClient, sid: str, on_event: OnEvent | None = None,
                 ping_interval_s: float = 30.0, keys: bool = True) -> None:
        self.client, self.sid, self.on_event = client, sid, on_event
        self.ping_interval_s = ping_interval_s
        self.keys = keys
        self.cursor: str | None = None
        self.hashes: set[bytes] = set()
        self.recalled: set[bytes] = set()
        self.members: set[str] = set()
        self.host: str | None = None
        self.events: list[pb.PhotoEvent] = []  # processed, in order
        self.stopped_with: str | None = None
        self.connected = asyncio.Event()

    @property
    def ws_url(self) -> str:
        base = self.client.api_base
        return ("wss://" + base[8:] if base.startswith("https://") else "ws://" + base[7:]) + "/v1/ws"

    async def sync_keys(self) -> None:
        if not self.keys:
            return
        try:
            await self.client.sync_keys(self.sid)
        except ClientError:
            pass  # the next key event, reconnect or upload runs it again

    def _needs_keys(self, ev: pb.PhotoEvent) -> bool:
        if ev.type in (pb.PhotoEvent.MEMBER_JOINED, pb.PhotoEvent.KEY_ROTATED):
            return ev.member_id != self.client.uid  # someone to seal for, or a new box for this member
        return ev.type == pb.PhotoEvent.KEY_SHARED and ev.member_id == self.client.uid

    async def load_snapshot(self) -> None:
        # Snapshot first, then keys: a key shared after the snapshot's cursor still arrives as an event, while one
        # shared between a key sync and a later snapshot would fall behind the cursor and never be seen.
        snap = await self.client.snapshot(self.sid)
        await self.sync_keys()
        self.members = set(snap.member_ids)
        for sha in snap.recalled_sha256:
            self.recalled.add(sha)
        for photo in snap.live_photos:
            await self._photo(photo)
        self.cursor = snap.cursor

    async def process(self, ev: pb.PhotoEvent) -> None:
        if self.cursor and ev.event_id and id_tuple(ev.event_id) <= id_tuple(self.cursor):
            return  # already processed
        if ev.type == pb.PhotoEvent.PHOTO_STORED:
            await self._photo(ev)
        else:
            if ev.type == pb.PhotoEvent.PHOTO_RECALLED:
                self.recalled.add(ev.sha256)
            elif ev.type == pb.PhotoEvent.MEMBER_JOINED:
                self.members.add(ev.member_id)
            elif ev.type == pb.PhotoEvent.MEMBER_LEFT:
                self.members.discard(ev.member_id)
            elif ev.type == pb.PhotoEvent.HOST_CHANGED:
                self.host = ev.member_id
            if self._needs_keys(ev):
                await self.sync_keys()
            self.events.append(ev)
            if self.on_event:
                await self.on_event(ev)
        if ev.event_id:
            self.cursor = ev.event_id

    async def _photo(self, ev: pb.PhotoEvent) -> None:
        if ev.sha256 in self.hashes or ev.sha256 in self.recalled:
            return  # a hash already held
        self.hashes.add(ev.sha256)
        self.events.append(ev)
        if self.on_event:
            await self.on_event(ev)

    async def run(self, stop: asyncio.Event | None = None) -> str:
        """Runs until the session ends, the user is removed, or `stop` is set. Returns why it stopped."""
        stop = stop or asyncio.Event()
        attempt = 0
        while not stop.is_set():
            if self.cursor is None:
                try:
                    await self.load_snapshot()
                except ClientError as e:
                    self.stopped_with = e.code
                    return e.code
            code = await self._connect_once(stop)
            if code in STOP_CODES:
                self.stopped_with = STOP_CODES[code]
                return self.stopped_with
            if code == 4001:
                await self.client.refresh(self.client.tokens.access_token)
                continue
            if code == 4409:
                self.cursor = None
                continue
            if stop.is_set():
                break
            attempt += 1
            await asyncio.sleep(random.uniform(0, min(5.0, 0.1 * 2**attempt)))
        self.stopped_with = "STOPPED"
        return "STOPPED"

    async def _connect_once(self, stop: asyncio.Event) -> int:
        try:
            async with connect(self.ws_url, max_size=1 << 20) as ws:
                hello = pb.ClientFrame(hello=pb.Hello(access_token=self.client.tokens.access_token,
                                                      session_id=self.sid, last_event_id=self.cursor or ""))
                await ws.send(hello.SerializeToString())

                async def pinger() -> None:
                    n = 0
                    while True:
                        await asyncio.sleep(self.ping_interval_s)
                        n += 1
                        await ws.send(pb.ClientFrame(ping=n).SerializeToString())

                async def stopper() -> None:
                    await stop.wait()
                    await ws.close()

                tasks = [asyncio.create_task(pinger()), asyncio.create_task(stopper())]
                try:
                    async for raw in ws:
                        frame = pb.ServerFrame()
                        frame.ParseFromString(raw if isinstance(raw, bytes) else raw.encode())
                        kind = frame.WhichOneof("f")
                        if kind == "ready_cursor":
                            self.connected.set()
                        elif kind == "event":
                            await self.process(frame.event)
                finally:
                    for t in tasks:
                        t.cancel()
                    self.connected.clear()
                return ws.close_code or 1006
        except ConnectionClosed as e:
            return e.rcvd.code if e.rcvd else 1006
        except OSError:
            return 1006
