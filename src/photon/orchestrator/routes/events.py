"""Snapshot, catch-up and the WebSocket: one durable source (the session stream), gap-free."""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import Awaitable, Callable
from typing import Any

from fastapi import APIRouter, Depends, Query, Request, Response, WebSocket
from google.protobuf.message import DecodeError
from starlette.websockets import WebSocketDisconnect, WebSocketState

from photon.common import clock, jwtutil, logs, metrics
from photon.common.errors import ApiError
from photon.common.pb import photon_pb2 as pb
from photon.common.proto import proto_response
from photon.orchestrator import keys as K
from photon.orchestrator.events import entry_to_event, id_ms, parse_id
from photon.orchestrator.routes.sessions import check_sid
from photon.orchestrator.svc import Svc

log = logs.get("ws")

CLOSE = {
    "TOKEN_EXPIRED": 4001,
    "INVALID_REQUEST": 4400,
    "UNAUTHENTICATED": 4401,
    "NOT_A_MEMBER": 4403,
    "RESYNC_REQUIRED": 4409,
    "SESSION_ENDED": 4410,
}


def router(svc: Svc, user: Callable[[Request], Awaitable[str]]) -> APIRouter:
    r = APIRouter()

    @r.get("/v1/sessions/{sid}/snapshot")
    async def snapshot(sid: str, uid: str = Depends(user)) -> Response:
        check_sid(sid)
        cursor, member_ids, live, recalled = await svc.fn.call_ro(
            "snapshot", K.session_keys(sid), sid, clock.now_ms(), svc.ret, uid
        )
        snap = pb.Snapshot(cursor=cursor, member_ids=member_ids,
                           recalled_sha256=[bytes.fromhex(h) for h in recalled])
        for sha, uploader, size, taken_at, expires_at in live:
            snap.live_photos.append(pb.PhotoEvent(
                type=pb.PhotoEvent.PHOTO_STORED, session_id=sid, sha256=bytes.fromhex(sha),
                uploader_id=uploader, size=int(size), taken_at_ms=int(taken_at), expires_at_ms=int(expires_at),
            ))
        return proto_response(snap)

    @r.get("/v1/sessions/{sid}/events")
    async def events_since(
        sid: str,
        since: str = Query(...),
        limit: int = Query(500, ge=1, le=1000),
        uid: str = Depends(user),
    ) -> Response:
        check_sid(sid)
        if parse_id(since) is None:
            raise ApiError("INVALID_REQUEST", "since must be an event id")
        try:
            (entries,) = await svc.fn.call_ro(
                "events_since", K.session_keys(sid), sid, clock.now_ms(), svc.ret, uid, since, limit
            )
        except ApiError as e:
            if e.code == "RESYNC_REQUIRED":
                metrics.resync_required_total.inc()
            raise
        batch = pb.EventBatch(cursor=since)
        for entry_id, fields in entries:
            batch.events.append(entry_to_event(sid, entry_id, fields))
            batch.cursor = entry_id
        return proto_response(batch)

    @r.websocket("/v1/ws")
    async def ws_endpoint(ws: WebSocket) -> None:
        await ws.accept()
        conn = Connection(svc, ws)
        try:
            await conn.run()
        except Exception as e:  # a server fault, not a normal end: 1011 tells the client to reconnect with backoff
            log.exception("ws_failed", error=repr(e))
            conn.close_code = 1011
        finally:
            await conn.close()

    return r


class Connection:
    """One client socket: a hello, then a reader (stream -> socket) and a receiver (pings) until one ends."""

    def __init__(self, svc: Svc, ws: WebSocket) -> None:
        self.svc, self.ws = svc, ws
        self.send_lock = asyncio.Lock()
        self.close_code = 1000
        self.uid = self.sid = ""
        self.last_presence = 0.0

    async def send(self, frame: pb.ServerFrame) -> None:
        async with self.send_lock:
            await self.ws.send_bytes(frame.SerializeToString())

    async def fail(self, err: ApiError) -> None:
        if err.code == "RESYNC_REQUIRED":
            metrics.resync_required_total.inc()
        self.close_code = CLOSE.get(err.code, 4400)
        with contextlib.suppress(Exception):
            await self.send(pb.ServerFrame(error=err.to_pb()))

    async def run(self) -> None:
        svc = self.svc
        try:
            raw = await asyncio.wait_for(self.ws.receive_bytes(), svc.settings.ws_hello_timeout_s)
            frame = pb.ClientFrame()
            frame.ParseFromString(raw)
            if frame.WhichOneof("f") != "hello":
                raise ApiError("INVALID_REQUEST", "The first frame must be hello")
        except (TimeoutError, DecodeError, KeyError, RuntimeError):
            await self.fail(ApiError("INVALID_REQUEST", "Expected a hello frame"))
            return
        except ApiError as e:
            await self.fail(e)
            return
        except WebSocketDisconnect:
            return
        hello = frame.hello
        try:
            claims = await jwtutil.verify(hello.access_token, svc.keys.auth, audience="photon", issuer="photon-auth")
            self.uid, self.sid, cursor = str(claims["sub"]), hello.session_id, hello.last_event_id
            check_sid(self.sid)
            await self.check(cursor)
        except ApiError as e:
            await self.fail(e)
            return
        await self.send(pb.ServerFrame(ready_cursor=cursor))
        await self.presence(force=True)
        metrics.ws_open.inc()
        try:
            tasks = {
                asyncio.create_task(self.reader(cursor)),
                asyncio.create_task(self.receiver()),
                asyncio.create_task(self.token_timer(int(claims["exp"]))),
            }
            done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for t in pending:
                t.cancel()
            await asyncio.gather(*pending, return_exceptions=True)
            for t in done:
                exc = t.exception()
                if isinstance(exc, ApiError):
                    await self.fail(exc)
                elif exc is not None and not isinstance(exc, WebSocketDisconnect):
                    log.warning("ws_task_failed", error=repr(exc))
                    self.close_code = 1011
        finally:
            metrics.ws_open.dec()

    async def check(self, cursor: str) -> None:
        await self.svc.fn.call_ro(
            "ws_check", K.session_keys(self.sid), self.sid, clock.now_ms(), self.svc.ret, self.uid, cursor
        )

    async def reader(self, cursor: str) -> None:
        """XREAD from the client's id, in one loop: backlog and live events come from the same read."""
        svc, last, stream = self.svc, cursor, K.events(self.sid)
        metrics.blocking_pool_in_use.inc()
        try:
            while True:
                resp: Any = await svc.blocking.xread({stream: last}, count=100, block=svc.settings.ws_block_ms)
                if not resp:
                    await self.check(last)  # raises: ended, kicked, or the stream was lost
                    continue
                items = resp.items() if isinstance(resp, dict) else resp
                for _, entries in items:
                    for entry_id, fields in entries:
                        ev = entry_to_event(self.sid, entry_id, fields)
                        await self.send(pb.ServerFrame(event=ev))
                        last = entry_id
                        if ev.type == pb.PhotoEvent.PHOTO_STORED:
                            metrics.stored_to_delivered_seconds.observe(
                                max(0, clock.now_ms() - id_ms(entry_id)) / 1000)
                        elif ev.type == pb.PhotoEvent.MEMBER_LEFT and ev.member_id == self.uid:
                            raise ApiError("NOT_A_MEMBER", "You left or were removed from the session")
                        elif ev.type == pb.PhotoEvent.SESSION_ENDED:
                            raise ApiError("SESSION_ENDED")
        finally:
            metrics.blocking_pool_in_use.dec()

    async def receiver(self) -> None:
        while True:
            msg = await self.ws.receive()
            if msg["type"] == "websocket.disconnect":
                return
            data = msg.get("bytes")
            if not data:
                continue
            frame = pb.ClientFrame()
            try:
                frame.ParseFromString(data)
            except DecodeError:
                continue
            if frame.WhichOneof("f") == "ping":
                await self.send(pb.ServerFrame(pong=frame.ping))
                await self.presence()
                self.svc.spawn(self.svc.touch(self.sid))

    async def presence(self, force: bool = False) -> None:
        now = asyncio.get_running_loop().time()
        if not force and now - self.last_presence < 30:
            return
        self.last_presence = now
        async with self.svc.r.pipeline(transaction=False) as p:
            p.hset(K.online(self.sid), self.uid, clock.now_ms())
            p.pexpire(K.online(self.sid), 3_600_000)
            await p.execute()

    async def token_timer(self, exp_s: int) -> None:
        await asyncio.sleep(max(0.0, (exp_s * 1000 - clock.now_ms()) / 1000))
        raise ApiError("TOKEN_EXPIRED", "Access token expired; refresh and reconnect")

    async def close(self) -> None:
        if self.ws.application_state != WebSocketState.DISCONNECTED and \
                self.ws.client_state != WebSocketState.DISCONNECTED:
            with contextlib.suppress(Exception):
                await self.ws.close(self.close_code)
