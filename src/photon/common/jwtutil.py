"""Ed25519 JWTs: signing, verification against static keys or a cached JWKS, and service tokens."""

from __future__ import annotations

import asyncio
import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Protocol

import httpx
import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from jwt.algorithms import OKPAlgorithm

from photon.common import clock
from photon.common.errors import ApiError

ALG = "EdDSA"


@dataclass
class SigningKey:
    kid: str
    private: Ed25519PrivateKey

    @property
    def public(self) -> Ed25519PublicKey:
        return self.private.public_key()


def load_signing_key(keys_dir: Path, purpose: str) -> SigningKey:
    """Reads <purpose>.pem and its kid from <purpose>.kid (written by tools/gen_keys.py)."""
    pem = (keys_dir / f"{purpose}.pem").read_bytes()
    key = serialization.load_pem_private_key(pem, password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise ValueError(f"{purpose}.pem is not an Ed25519 key")
    kid = (keys_dir / f"{purpose}.kid").read_text().strip()
    return SigningKey(kid, key)


def load_public_keys(keys_dir: Path, purpose: str) -> dict[str, Ed25519PublicKey]:
    """Reads <purpose>.pub.pem (current) and <purpose>.next.pub.pem / .prev.pub.pem when present (rotation)."""
    out: dict[str, Ed25519PublicKey] = {}
    for variant in ("", ".next", ".prev"):
        pem_path = keys_dir / f"{purpose}{variant}.pub.pem"
        kid_path = keys_dir / f"{purpose}{variant}.kid"
        if pem_path.exists() and kid_path.exists():
            key = serialization.load_pem_public_key(pem_path.read_bytes())
            if not isinstance(key, Ed25519PublicKey):
                raise ValueError(f"{pem_path} is not an Ed25519 key")
            out[kid_path.read_text().strip()] = key
    if not out:
        raise FileNotFoundError(f"no public key for {purpose} in {keys_dir}")
    return out


def jwks_document(keys: dict[str, Ed25519PublicKey]) -> dict[str, Any]:
    entries = []
    for kid, key in keys.items():
        jwk = json.loads(OKPAlgorithm.to_jwk(key))
        jwk.update(kid=kid, alg=ALG, use="sig")
        entries.append(jwk)
    return {"keys": entries}


def sign(claims: dict[str, Any], key: SigningKey) -> str:
    return jwt.encode(claims, key.private, algorithm=ALG, headers={"kid": key.kid})


class KeySource(Protocol):
    async def get(self, kid: str) -> Ed25519PublicKey | None: ...


@dataclass
class StaticKeys:
    keys: dict[str, Ed25519PublicKey]

    async def get(self, kid: str) -> Ed25519PublicKey | None:
        return self.keys.get(kid)


@dataclass
class JwksClient:
    """Fetches a JWKS, caches it for 10 minutes, and refetches once on an unknown kid (at most every 30 s)."""

    url: str
    ttl_s: float = 600.0
    _keys: dict[str, Ed25519PublicKey] = field(default_factory=dict)
    _fetched_at: float = 0.0
    _lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    async def _fetch(self) -> None:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(self.url)
            resp.raise_for_status()
        keys: dict[str, Ed25519PublicKey] = {}
        for jwk in resp.json().get("keys", []):
            if jwk.get("kty") == "OKP" and jwk.get("crv") == "Ed25519":
                key = OKPAlgorithm.from_jwk(json.dumps(jwk))
                if isinstance(key, Ed25519PublicKey):
                    keys[jwk["kid"]] = key
        self._keys, self._fetched_at = keys, time.monotonic()

    async def get(self, kid: str) -> Ed25519PublicKey | None:
        age = time.monotonic() - self._fetched_at
        if age > self.ttl_s or (kid not in self._keys and age > 30):
            async with self._lock:
                age = time.monotonic() - self._fetched_at
                if age > self.ttl_s or (kid not in self._keys and age > 30):
                    try:
                        await self._fetch()
                    except httpx.HTTPError:
                        if not self._keys:
                            raise ApiError("BUSY", "Key service unavailable") from None
        return self._keys.get(kid)


async def verify(token: str, keys: KeySource, *, audience: str, issuer: str | list[str]) -> dict[str, Any]:
    """Pins EdDSA, requires exp/iss/aud and a known kid. Expired -> TOKEN_EXPIRED, anything else -> UNAUTHENTICATED."""
    try:
        header = jwt.get_unverified_header(token)
    except jwt.PyJWTError as e:
        raise ApiError("UNAUTHENTICATED", "Malformed token") from e
    if header.get("alg") != ALG or not isinstance(header.get("kid"), str):
        raise ApiError("UNAUTHENTICATED", "Unexpected token algorithm or key id")
    key = await keys.get(header["kid"])
    if key is None:
        raise ApiError("UNAUTHENTICATED", "Unknown signing key")
    try:
        claims: dict[str, Any] = jwt.decode(
            token,
            key,
            algorithms=[ALG],
            audience=audience,
            issuer=issuer,
            # exp is checked below against clock.now_ms(), the one clock every service (and test) uses
            options={"require": ["exp", "iss", "aud"], "verify_exp": False, "verify_iat": False},
        )
    except jwt.PyJWTError as e:
        raise ApiError("UNAUTHENTICATED", "Invalid token") from e
    if not isinstance(claims["exp"], int) or claims["exp"] * 1000 <= clock.now_ms():
        raise ApiError("TOKEN_EXPIRED", "Token expired")
    return claims


class ServiceTokens:
    """Mints this service's short-lived tokens for calls to another service; reused until 10 s before expiry."""

    def __init__(self, service: str, key: SigningKey, ttl_s: int = 60) -> None:
        self.service, self.key, self.ttl_s = service, key, ttl_s
        self._cache: dict[str, tuple[str, int]] = {}

    def token_for(self, target: str) -> str:
        now_s = clock.now_ms() // 1000
        cached = self._cache.get(target)
        if cached and cached[1] - 10 > now_s:
            return cached[0]
        exp = now_s + self.ttl_s
        token = sign({"iss": self.service, "aud": f"{target}-internal", "iat": now_s, "exp": exp}, self.key)
        self._cache[target] = (token, exp)
        return token


def bearer(authorization: str | None) -> str:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise ApiError("UNAUTHENTICATED", "Missing bearer token")
    return authorization[7:].strip()
