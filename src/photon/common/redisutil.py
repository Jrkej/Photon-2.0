"""Redis clients and the photon Functions library (every state transition is one Lua function)."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from redis.asyncio import BlockingConnectionPool, Redis
from redis.exceptions import ResponseError

from photon.common.errors import ApiError

LIB_PATH = Path(__file__).resolve().parent.parent / "orchestrator" / "lua" / "photon.lua"


def make_client(url: str, max_connections: int | None = None) -> Redis:
    return Redis.from_url(url, decode_responses=True, max_connections=max_connections, health_check_interval=30)


def make_blocking_client(url: str, max_connections: int) -> Redis:
    """For XREAD BLOCK: one connection per open WebSocket, waiting (not failing) when the pool is exhausted.

    No socket read timeout: redis-py's default (5 s) would cut every XREAD BLOCK longer than that and drop the
    WebSocket. A blocked read ends with its BLOCK time or with its socket; TCP keepalive finds dead peers.
    """
    pool = BlockingConnectionPool.from_url(
        url, max_connections=max_connections, timeout=5, decode_responses=True,
        socket_timeout=None, socket_connect_timeout=5, socket_keepalive=True,
    )
    return Redis(connection_pool=pool)


def library_code() -> str:
    return LIB_PATH.read_text()


def library_version(code: str) -> str:
    m = re.search(r"^local VERSION = '([^']+)'", code, re.M)
    if not m:
        raise ValueError("photon.lua has no VERSION")
    return m.group(1)


async def ensure_library(r: Redis, code: str | None = None) -> str:
    """Loads photon.lua when Redis has no library or another version (FUNCTION LOAD REPLACE)."""
    code = code or library_code()
    want = library_version(code)
    try:
        have = await r.fcall_ro("version", 0)
    except ResponseError:
        have = None
    if have != want:
        await r.function_load(code, replace=True)
    return want


def unwrap(res: Any) -> list[Any]:
    if not isinstance(res, list) or not res:
        raise RuntimeError(f"unexpected function result {res!r}")
    if res[0] == "ERR":
        raise ApiError(res[1])
    return res[1:]


class Functions:
    def __init__(self, r: Redis) -> None:
        self.r = r

    async def call(self, name: str, keys: list[str], *args: Any) -> list[Any]:
        return unwrap(await self.r.fcall(name, len(keys), *keys, *(_arg(a) for a in args)))

    async def call_ro(self, name: str, keys: list[str], *args: Any) -> list[Any]:
        return unwrap(await self.r.fcall_ro(name, len(keys), *keys, *(_arg(a) for a in args)))

    async def raw(self, name: str, keys: list[str], *args: Any) -> Any:
        return await self.r.fcall(name, len(keys), *keys, *(_arg(a) for a in args))


def _arg(a: Any) -> str:
    if isinstance(a, bool):
        return "1" if a else "0"
    return str(a)
