"""Upload tokens and the Redis checks the image server makes: session status, membership, lease, photo status.
The image server reads session state and refreshes leases; it never changes anything else."""

from __future__ import annotations

import time
from dataclasses import dataclass

from fastapi import Request
from redis.asyncio import Redis

from photon.common import clock, jwtutil
from photon.common.config import Settings
from photon.common.errors import ApiError
from photon.common.redisutil import Functions
from photon.imageserver.store import check_sha, check_sid, check_upload_id
from photon.orchestrator import keys as K


@dataclass(frozen=True)
class UploadClaims:
    sid: str
    sha256: str
    size: int
    mime: str
    taken_at_ms: int
    uploader: str
    upload_id: str


async def upload_claims(request: Request, keys: jwtutil.KeySource, upload_id: str) -> UploadClaims:
    check_upload_id(upload_id)
    token = jwtutil.bearer(request.headers.get("authorization"))
    c = await jwtutil.verify(token, keys, audience="image-server", issuer="photon-orchestrator")
    try:
        claims = UploadClaims(
            sid=check_sid(str(c["sid"])),
            sha256=check_sha(str(c["sha256"])),
            size=int(c["size"]),
            mime=str(c["mime"]),
            taken_at_ms=int(c["taken_at_ms"]),
            uploader=str(c["uploader"]),
            upload_id=str(c["upload_id"]),
        )
    except (KeyError, ValueError, TypeError) as e:
        raise ApiError("UNAUTHENTICATED", "Malformed upload token") from e
    if claims.upload_id != upload_id:
        raise ApiError("UNKNOWN_UPLOAD")
    return claims


class Access:
    def __init__(self, r: Redis, settings: Settings) -> None:
        self.r = r
        self.fn = Functions(r)
        self.settings = settings
        self._cache: dict[tuple[str, str], tuple[float, str, bool]] = {}

    async def _upload_check(self, c: UploadClaims) -> tuple[str, bool, bool, bool]:
        status, member, alive, superseded = await self.fn.call_ro(
            "upload_check", K.session_keys(c.sid), c.sid, clock.now_ms(), self.settings.retention_ms,
            c.uploader, c.upload_id, c.sha256,
        )
        self._cache[(c.sid, c.uploader)] = (time.monotonic() + self.settings.member_cache_s, status, bool(member))
        return status, bool(member), bool(alive), bool(superseded)

    @staticmethod
    def _raise_for(status: str, member: bool) -> None:
        if status != "ACTIVE":
            raise ApiError("SESSION_ENDED")
        if not member:
            raise ApiError("NOT_A_MEMBER")

    async def check_chunk(self, c: UploadClaims, state: str) -> None:
        """Membership and status from a cache of at most member_cache_s; the lease is refreshed every time."""
        cached = self._cache.get((c.sid, c.uploader))
        if cached and cached[0] > time.monotonic():
            self._raise_for(cached[1], cached[2])
        else:
            status, member, _, _ = await self._upload_check(c)
            self._raise_for(status, member)
        res = await self.fn.call(
            "touch_lease", [K.lease(c.sid, c.upload_id)], c.sid, clock.now_ms(), self.settings.retention_ms,
            c.upload_id, self.settings.lease_ms, state, c.sha256,
        )
        if int(res[0]) == 0:
            raise ApiError("UPLOAD_SUPERSEDED" if res[1] == "SUPERSEDED" else "UPLOAD_EXPIRED")

    async def check_uncached(self, c: UploadClaims) -> None:
        status, member, alive, superseded = await self._upload_check(c)
        self._raise_for(status, member)
        if not alive:
            raise ApiError("UPLOAD_SUPERSEDED" if superseded else "UPLOAD_EXPIRED")

    async def lease_exists(self, sid: str, upload_id: str) -> bool:
        return bool(await self.r.exists(K.lease(sid, upload_id)))

    async def session_status(self, sid: str) -> str:
        return str(await self.r.hget(K.session(sid), "status") or "")

    async def download_check(self, sid: str, uid: str, sha: str) -> tuple[str, bool, str, str, str, int]:
        status, member, pstatus, upload_id, mime, expires_at = await self.fn.call_ro(
            "download_check", K.session_keys(sid), sid, clock.now_ms(), self.settings.retention_ms, uid, sha
        )
        return status, bool(member), pstatus, upload_id, mime, int(float(expires_at or 0))
