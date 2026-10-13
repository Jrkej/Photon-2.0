"""Background jobs: the expiry sweeper, the idle-session sweeper and the quota recount. Each runs in every
orchestrator process; a Redis lock per shard (or per job) makes one process do each piece of work at a time."""

from __future__ import annotations

import asyncio
import contextlib
import random
from collections.abc import Awaitable, Callable
from typing import Any

from redis.exceptions import LockError

from photon.common import clock, logs, metrics
from photon.common.errors import ApiError
from photon.orchestrator import keys as K
from photon.orchestrator.svc import Svc

log = logs.get("jobs")


async def _locked(svc: Svc, name: str, ttl_s: float, work: Callable[[], Awaitable[None]]) -> bool:
    lock = svc.r.lock(K.lock(name), timeout=max(1.0, ttl_s), blocking=False)
    if not await lock.acquire():
        return False
    try:
        await work()
    finally:
        with contextlib.suppress(LockError):
            await lock.release()
    return True


async def sweep_expiry_shard(svc: Svc, n: int) -> None:
    """Marks due photos EXPIRED and deletes their files; an entry leaves the index only once the delete is acked."""
    key, attempts_key = K.expiry(n), K.expiry_attempts(n)
    now = clock.now_ms()
    raw: list[Any] = await svc.r.zrangebyscore(key, "-inf", now, start=0, num=200, withscores=True)
    due = [(str(member), float(score)) for member, score in raw]
    if not due:
        return
    metrics.expiry_backlog_oldest_seconds.set(max(0, now - int(due[0][1])) / 1000)
    sem = asyncio.Semaphore(16)

    async def one(member: str) -> None:
        sid, _, sha = member.rpartition(":")
        async with sem:
            try:
                res = await svc.fn.call("expire_photo", K.session_keys(sid), sid, clock.now_ms(), svc.ret, sha)
            except ApiError:
                res = ["GONE"]
            if res[0] == "NOT_DUE":  # never delete a photo that is still live
                await svc.r.zadd(key, {member: int(float(res[1]))}, xx=True)
                return
            if await svc.delete_file(sid, sha):
                async with svc.r.pipeline(transaction=False) as p:
                    p.zrem(key, member)
                    p.hdel(attempts_key, member)
                    await p.execute()
                return
            tries = await svc.r.hincrby(attempts_key, member, 1)
            backoff_ms = min(2 ** int(tries), 300) * 1000
            await svc.r.zadd(key, {member: clock.now_ms() + backoff_ms}, xx=True)

    await asyncio.gather(*(one(member) for member, _ in due))


async def sweep_expiry(svc: Svc) -> None:
    ttl = max(5.0, svc.settings.sweeper_interval_s - 5)
    for n in range(svc.settings.expiry_shards):
        await _locked(svc, f"expiry:{n}", ttl, lambda n=n: sweep_expiry_shard(svc, n))  # type: ignore[misc]


async def sweep_sessions(svc: Svc) -> None:
    """Ends sessions idle past their expires_at."""

    async def work() -> None:
        now = clock.now_ms()
        for raw in await svc.r.zrangebyscore(K.SESSIONS_DUE, "-inf", now, start=0, num=100):
            sid = str(raw)
            res = await svc.fn.call("end_session", K.session_keys(sid), sid, now, svc.ret, "system", "idle")
            if res[0] == "ENDED":
                log.info("session_idle_ended", sid=sid)
                await svc.after_end(sid, res[1:5])
            elif res[0] == "NOT_DUE":
                await svc.r.zadd(K.SESSIONS_DUE, {sid: int(float(res[1]))})
            else:
                await svc.r.zrem(K.SESSIONS_DUE, sid)

    await _locked(svc, "session_sweeper", max(5.0, svc.settings.session_sweeper_interval_s - 5), work)


async def recount(svc: Svc) -> None:
    """Recomputes the quota counters from the photos hash, releases stale reservations, repairs missed expiries."""

    async def work() -> None:
        for raw in await svc.r.zrange(K.SESSIONS_DUE, 0, -1):
            sid, now = str(raw), clock.now_ms()
            ds, dr, released, expired = await svc.fn.call(
                "recount", K.session_keys(sid), sid, now, svc.ret, svc.settings.takeover_ms
            )
            metrics.quota_correction_bytes.labels("stored").observe(abs(int(ds)))
            metrics.quota_correction_bytes.labels("reserved").observe(abs(int(dr)))
            if int(released):
                metrics.stale_reservations_released_total.inc(int(released))
            if int(ds) or int(dr):
                log.warning("quota_drift_corrected", sid=sid, stored=int(ds), reserved=int(dr))
            await svc.queue_deletes(sid, list(expired), now)

    await _locked(svc, "recount", max(5.0, svc.settings.recount_interval_s - 5), work)


async def _every(interval_s: float, name: str, job: Callable[[], Awaitable[None]]) -> None:
    await asyncio.sleep(random.uniform(0, min(interval_s, 5.0)))
    while True:
        try:
            await job()
        except asyncio.CancelledError:
            raise
        except Exception as e:
            log.exception("job_failed", job=name, error=repr(e))
        await asyncio.sleep(interval_s * random.uniform(0.9, 1.1))


def start(svc: Svc) -> list[asyncio.Task[None]]:
    s = svc.settings
    return [
        asyncio.create_task(_every(s.sweeper_interval_s, "expiry", lambda: sweep_expiry(svc))),
        asyncio.create_task(_every(s.session_sweeper_interval_s, "sessions", lambda: sweep_sessions(svc))),
        asyncio.create_task(_every(s.recount_interval_s, "recount", lambda: recount(svc))),
    ]
