"""The orchestrator's shared state and the helpers several routes and jobs use."""

from __future__ import annotations

import asyncio
import time
from collections.abc import Coroutine
from dataclasses import dataclass
from typing import Any

import httpx
from redis.asyncio import Redis
from sqlalchemy.ext.asyncio import AsyncEngine

from photon.common import clock, jwtutil, logs, metrics
from photon.common.config import Settings
from photon.common.errors import ApiError
from photon.common.redisutil import Functions
from photon.orchestrator import keys as K

log = logs.get("orchestrator")


@dataclass
class OrchestratorKeys:
    upload: jwtutil.SigningKey  # signs upload tokens (aud=image-server)
    service: jwtutil.SigningKey  # signs this service's calls to the image server
    auth: jwtutil.KeySource  # verifies access tokens
    imageserver: jwtutil.KeySource  # verifies the image server's service tokens


class Svc:
    def __init__(
        self,
        settings: Settings,
        keys: OrchestratorKeys,
        redis: Redis,
        blocking: Redis,
        db: AsyncEngine | None = None,
    ) -> None:
        self.settings = settings
        self.keys = keys
        self.r = redis
        self.blocking = blocking
        self.fn = Functions(redis)
        self.db = db
        self.service_tokens = jwtutil.ServiceTokens("orchestrator", keys.service, settings.service_token_ttl_s)
        self.http = httpx.AsyncClient(timeout=5.0)
        self._touched: dict[str, float] = {}
        self._tasks: set[asyncio.Task[Any]] = set()
        self.pusher: Any = None  # set by app.py (push.Pusher)

    # ------------------------------------------------------------------ helpers

    @property
    def ret(self) -> int:
        return self.settings.retention_ms

    def spawn(self, coro: Coroutine[Any, Any, Any]) -> None:
        """Fire-and-forget work that must not hold up a response; the task is kept alive until it finishes."""
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def close(self) -> None:
        for t in list(self._tasks):
            t.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)
        await self.http.aclose()

    # ------------------------------------------------------------------ one session per user

    @property
    def pointer_ttl_ms(self) -> int:
        return self.settings.session_idle_ms + 3_600_000

    async def is_live_member(self, sid: str, uid: str) -> bool:
        async with self.r.pipeline(transaction=False) as p:
            p.hget(K.session(sid), "status")
            p.hexists(K.members(sid), uid)
            status, member = await p.execute()
        return bool(status == "ACTIVE" and member)

    async def claim_pointer(self, uid: str, sid: str) -> bool:
        """Sets user:{uid}:session -> sid. Returns False if the pointer already pointed at sid (nothing to undo)."""
        key = K.user_session(uid)
        for _ in range(3):
            if await self.r.set(key, sid, nx=True, px=self.pointer_ttl_ms):
                return True
            raw = await self.r.get(key)
            old = str(raw) if raw is not None else None
            if old == sid:
                return False
            if old is None:
                continue  # expired between SET and GET: try again
            if await self.is_live_member(old, uid):
                raise ApiError("ALREADY_IN_SESSION", "Leave your current session first")
            if await self.r.fcall("ptr_cas", 1, key, old, sid, self.pointer_ttl_ms) == 1:
                return True  # a pointer to an ended session is overwritten
        raise ApiError("BUSY", "Please try again")

    async def release_pointer(self, uid: str, sid: str) -> None:
        await self.r.fcall("ptr_del", 1, K.user_session(uid), sid)

    # ------------------------------------------------------------------ activity

    async def touch(self, sid: str, force: bool = False) -> None:
        """Keeps a session alive: at most once a minute per session per process."""
        last = self._touched.get(sid, 0.0)
        if not force and time.monotonic() - last < self.settings.activity_throttle_s:
            return
        self._touched[sid] = time.monotonic()
        try:
            (exp,) = await self.fn.call("touch_session", K.session_keys(sid), sid, clock.now_ms(), self.ret,
                                        self.settings.session_idle_ms)
        except ApiError:
            return
        members = await self.r.hkeys(K.members(sid))
        async with self.r.pipeline(transaction=False) as p:
            p.zadd(K.SESSIONS_DUE, {sid: int(exp)}, gt=True)
            for uid in members:
                p.fcall("ptr_touch", 1, K.user_session(str(uid)), sid, self.pointer_ttl_ms)
            await p.execute()

    # ------------------------------------------------------------------ deletion

    async def queue_deletes(self, sid: str, shas: list[str], at_ms: int) -> None:
        if not shas:
            return
        await self.r.zadd(K.expiry(K.shard(sid, self.settings.expiry_shards)),
                          {f"{sid}:{sha}": at_ms for sha in shas}, lt=True)

    async def delete_file(self, sid: str, sha: str) -> bool:
        """DELETE on the image server; True once it acknowledged (204 or 404)."""
        url = f"{self.settings.imageserver_internal_url}/internal/v1/photos/{sid}/{sha}"
        try:
            resp = await self.http.delete(
                url, headers={"Authorization": f"Bearer {self.service_tokens.token_for('imageserver')}"}
            )
        except httpx.HTTPError as e:
            log.warning("delete_failed", sid=sid, sha=sha[:12], error=repr(e))
            metrics.deletes_total.labels("error").inc()
            return False
        if resp.status_code in (200, 204, 404):
            metrics.deletes_total.labels("ok").inc()
            return True
        metrics.deletes_total.labels(str(resp.status_code)).inc()
        return False

    async def delete_now(self, sid: str, shas: list[str]) -> None:
        """Best-effort immediate deletes after recall or session end; the expiry sweeper retries what fails."""
        shard_key = K.expiry(K.shard(sid, self.settings.expiry_shards))
        for sha in shas:
            if await self.delete_file(sid, sha):
                await self.r.zrem(shard_key, f"{sid}:{sha}")

    async def after_end(self, sid: str, result: list[Any]) -> None:
        """Clean-up outside the session's slot after do_end. Losing it is harmless (see implementation 2.7)."""
        to_delete, member_ids, join_code, invite_token = result
        async with self.r.pipeline(transaction=False) as p:
            if join_code:
                p.delete(K.code(join_code))
            if invite_token:
                p.delete(K.invite(invite_token))
            p.zrem(K.SESSIONS_DUE, sid)
            for uid in member_ids:
                p.fcall("ptr_del", 1, K.user_session(uid), sid)
            await p.execute()
        now = clock.now_ms()
        await self.queue_deletes(sid, list(to_delete), now)
        self._touched.pop(sid, None)
        if self.pusher:
            self.spawn(self.pusher.notify(sid, "ended", list(member_ids)))
        self.spawn(self.delete_now(sid, list(to_delete)))
