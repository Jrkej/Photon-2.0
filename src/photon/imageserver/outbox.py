"""Pending stored/rejected reports. A record is on disk before the first send and is retried until the
orchestrator gives a final answer (200, 409 or 410), so a crash between saving a file and reporting it is
recovered at startup."""

from __future__ import annotations

import asyncio
import base64
import os
import random
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

import httpx

from photon.common import clock, jwtutil, logs, metrics
from photon.common.config import Settings
from photon.common.pb import photon_pb2 as pb
from photon.common.proto import PROTO
from photon.imageserver.store import Store, read_json, write_json

log = logs.get("outbox")

APPLIED, NOOP, SUPERSEDED, ENDED = "APPLIED", "NOOP", "SUPERSEDED", "ENDED"


@dataclass
class Record:
    upload_id: str
    kind: str  # "stored" or "rejected"
    body: bytes
    attempts: int = 0
    next_at: int = 0
    created_at: int = 0

    def to_json(self) -> dict[str, Any]:
        return {"upload_id": self.upload_id, "kind": self.kind, "body": base64.b64encode(self.body).decode(),
                "attempts": self.attempts, "next_at": self.next_at, "created_at": self.created_at}

    @classmethod
    def from_json(cls, raw: dict[str, Any]) -> Record:
        return cls(raw["upload_id"], raw["kind"], base64.b64decode(raw["body"]), int(raw["attempts"]),
                   int(raw["next_at"]), int(raw["created_at"]))


OnFinal = Callable[[Record, str], Awaitable[None]]


class Outbox:
    def __init__(self, settings: Settings, store: Store, tokens: jwtutil.ServiceTokens) -> None:
        self.settings, self.store, self.tokens = settings, store, tokens
        self.on_final: OnFinal | None = None
        self.http = httpx.AsyncClient(timeout=10.0)
        self._wake = asyncio.Event()
        self._inflight: set[str] = set()
        self._done: dict[str, asyncio.Event] = {}
        self._task: asyncio.Task[None] | None = None
        self._sends: set[asyncio.Task[None]] = set()

    def put(self, upload_id: str, kind: str, msg: pb.PhotoStored | pb.PhotoRejected) -> None:
        now = clock.now_ms()
        rec = Record(upload_id, kind, msg.SerializeToString(), 0, now, now)
        write_json(self.store.outbox_file(upload_id), rec.to_json())
        self._done.setdefault(upload_id, asyncio.Event())
        self._wake.set()

    def pending(self, upload_id: str) -> bool:
        return self.store.outbox_file(upload_id).exists()

    async def wait(self, upload_id: str, timeout: float) -> bool:
        """True when the record reached a final answer within `timeout`."""
        if not self.pending(upload_id):
            return True
        ev = self._done.setdefault(upload_id, asyncio.Event())
        try:
            await asyncio.wait_for(ev.wait(), timeout)
        except TimeoutError:
            return False
        return True

    def start(self) -> None:
        self._task = asyncio.create_task(self._run())

    async def stop(self) -> None:
        tasks = [t for t in (self._task, *self._sends) if t]
        for t in tasks:
            t.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        await self.http.aclose()

    def _load_all(self) -> list[Record]:
        out = []
        for entry in os.scandir(self.store.outbox):
            if entry.name.endswith(".json"):
                raw = read_json(self.store.outbox / entry.name)
                if raw:
                    out.append(Record.from_json(raw))
        return out

    async def _run(self) -> None:
        while True:
            try:
                records = self._load_all()
                now = clock.now_ms()
                metrics.outbox_pending.set(len(records))
                metrics.outbox_oldest_seconds.set(
                    max((now - r.created_at for r in records), default=0) / 1000)
                for rec in records:
                    if rec.next_at <= now and rec.upload_id not in self._inflight:
                        self._inflight.add(rec.upload_id)
                        task = asyncio.create_task(self._send(rec))
                        self._sends.add(task)
                        task.add_done_callback(self._sends.discard)
            except Exception as e:
                log.exception("outbox_loop_failed", error=repr(e))
            self._wake.clear()
            try:
                await asyncio.wait_for(self._wake.wait(), 1.0)
            except TimeoutError:
                pass

    async def _send(self, rec: Record) -> None:
        try:
            answer = await self._post(rec)
            if answer is None:
                rec.attempts += 1
                backoff_s = min(60.0, 2.0 ** min(rec.attempts, 6))
                rec.next_at = clock.now_ms() + int(random.uniform(0, backoff_s) * 1000) + 500
                write_json(self.store.outbox_file(rec.upload_id), rec.to_json())
                return
            if self.on_final is not None:
                await self.on_final(rec, answer)
            self.store.outbox_file(rec.upload_id).unlink(missing_ok=True)
            self._done.setdefault(rec.upload_id, asyncio.Event()).set()
        except Exception as e:
            log.exception("outbox_send_failed", upload_id=rec.upload_id, error=repr(e))
        finally:
            self._inflight.discard(rec.upload_id)

    async def _post(self, rec: Record) -> str | None:
        url = f"{self.settings.orchestrator_internal_url}/internal/v1/photos/{rec.kind}"
        headers = {"Authorization": f"Bearer {self.tokens.token_for('orchestrator')}", "Content-Type": PROTO}
        try:
            resp = await self.http.post(url, content=rec.body, headers=headers)
        except httpx.HTTPError as e:
            log.warning("report_unreachable", upload_id=rec.upload_id, error=repr(e))
            return None
        if resp.status_code == 200:
            ack = pb.ReportAck()
            ack.ParseFromString(resp.content)
            return NOOP if ack.result == pb.ReportAck.NOOP else APPLIED
        if resp.status_code == 409:
            return SUPERSEDED
        if resp.status_code == 410:
            return ENDED
        if resp.status_code in (401, 403):
            metrics.report_auth_failures_total.inc()
        log.warning("report_retry", upload_id=rec.upload_id, status=resp.status_code)
        return None
