"""Access JWTs and refresh tokens with rotation, reuse detection and a grace window.

A refresh token is `<family>.<secret>`. Only sha256(secret) is stored, at refresh:{family}:<hash>, next to the
family record rfam:{family}. The family is the hash tag, so a rotation is one atomic script in one slot.
"""

from __future__ import annotations

import hashlib
import re
import secrets

from redis.asyncio import Redis

from photon.common import clock, jwtutil, logs, metrics
from photon.common.config import Settings
from photon.common.errors import ApiError
from photon.common.pb import photon_pb2 as pb

log = logs.get("auth")
TOKEN_RE = re.compile(r"^([A-Za-z0-9_-]{22})\.([A-Za-z0-9_-]{43})$")

ROTATE = """
-- KEYS: refresh:{f}:<old>, rfam:{f}, refresh:{f}:<new>; ARGV: now, grace_ms, ttl_ms
if redis.call('HGET', KEYS[2], 'revoked') ~= '0' then return {'ERR', 'REVOKED'} end
local tok = redis.call('HMGET', KEYS[1], 'uid', 'rotated_at')
if not tok[1] then return {'ERR', 'UNKNOWN'} end
local now = tonumber(ARGV[1])
if tok[2] == '' then
  redis.call('HSET', KEYS[1], 'rotated_at', now)
elseif now - tonumber(tok[2]) > tonumber(ARGV[2]) then
  redis.call('HSET', KEYS[2], 'revoked', '1')
  return {'ERR', 'REUSED'}
end
redis.call('HSET', KEYS[3], 'uid', tok[1], 'rotated_at', '')
redis.call('PEXPIRE', KEYS[3], ARGV[3])
redis.call('PEXPIRE', KEYS[2], ARGV[3])
return {'OK', tok[1]}
"""


def _hash(secret: str) -> str:
    return hashlib.sha256(secret.encode()).hexdigest()


def refresh_key(family: str, digest: str) -> str:
    return f"refresh:{{{family}}}:{digest}"


def family_key(family: str) -> str:
    return f"rfam:{{{family}}}"


class Tokens:
    def __init__(self, settings: Settings, key: jwtutil.SigningKey, redis: Redis) -> None:
        self.settings, self.key, self.r = settings, key, redis
        self._rotate = redis.register_script(ROTATE)

    def access_token(self, uid: str) -> tuple[str, int]:
        now_s = clock.now_ms() // 1000
        exp = now_s + self.settings.access_token_ttl_s
        token = jwtutil.sign({"sub": uid, "iss": "photon-auth", "aud": "photon", "iat": now_s, "exp": exp}, self.key)
        return token, exp * 1000

    async def new_family(self, uid: str) -> str:
        family, secret = secrets.token_urlsafe(16), secrets.token_urlsafe(32)
        ttl_ms = self.settings.refresh_ttl_s * 1000
        async with self.r.pipeline(transaction=True) as p:
            p.hset(family_key(family), mapping={"uid": uid, "revoked": "0"})
            p.pexpire(family_key(family), ttl_ms)
            p.hset(refresh_key(family, _hash(secret)), mapping={"uid": uid, "rotated_at": ""})
            p.pexpire(refresh_key(family, _hash(secret)), ttl_ms)
            await p.execute()
        return f"{family}.{secret}"

    async def pair(self, uid: str, refresh: str | None = None) -> pb.TokenPair:
        access, exp_ms = self.access_token(uid)
        refresh = refresh or await self.new_family(uid)
        return pb.TokenPair(access_token=access, refresh_token=refresh, access_expires_at_ms=exp_ms, user_id=uid)

    @staticmethod
    def family_of(token: str) -> str:
        m = TOKEN_RE.match(token)
        if not m:
            raise ApiError("UNAUTHENTICATED", "Malformed refresh token")
        return m.group(1)

    async def rotate(self, token: str) -> pb.TokenPair:
        m = TOKEN_RE.match(token)
        if not m:
            raise ApiError("UNAUTHENTICATED", "Malformed refresh token")
        family, secret = m.group(1), m.group(2)
        new_secret = secrets.token_urlsafe(32)
        res = await self._rotate(
            keys=[refresh_key(family, _hash(secret)), family_key(family), refresh_key(family, _hash(new_secret))],
            args=[clock.now_ms(), self.settings.refresh_grace_ms, self.settings.refresh_ttl_s * 1000],
        )
        if res[0] != "OK":
            if res[1] == "REUSED":
                metrics.refresh_reuse_total.inc()
                log.warning("refresh_reuse_detected", family=family[:6])
            raise ApiError("UNAUTHENTICATED", "Refresh token is no longer valid")
        return await self.pair(str(res[1]), f"{family}.{new_secret}")
