"""Fixed-window rate limits in Redis: rl:{scope}:{id}."""

from __future__ import annotations

from redis.asyncio import Redis

from photon.common.errors import ApiError


async def hit(r: Redis, key: str, limit: int, window_s: int) -> None:
    async with r.pipeline(transaction=True) as p:
        p.incr(key)
        p.expire(key, window_s, nx=True)
        p.ttl(key)
        count, _, ttl = await p.execute()
    if int(count) > limit:
        raise ApiError("RATE_LIMITED", "Too many requests", retry_after_s=max(1, int(ttl)))
