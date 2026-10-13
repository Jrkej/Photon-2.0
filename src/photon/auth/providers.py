"""OAuth providers behind one interface. Google is OIDC; `dev` exists only outside production."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any, Protocol
from urllib.parse import urlencode

import httpx
import jwt

from photon.common import clock, jwtutil
from photon.common.config import Settings
from photon.common.errors import ApiError


@dataclass
class ProviderTokens:
    access_token: str
    id_token: str | None
    raw: dict[str, Any]


@dataclass
class ProviderIdentity:
    provider_user_id: str
    display_name: str


class Provider(Protocol):
    name: str

    def authorization_url(self, *, state: str, code_challenge: str, redirect_uri: str, nonce: str) -> str: ...
    async def exchange(self, *, code: str, code_verifier: str, redirect_uri: str) -> ProviderTokens: ...
    async def identity(self, tokens: ProviderTokens, *, nonce: str) -> ProviderIdentity: ...


class GoogleProvider:
    name = "google"
    AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    TOKEN_URL = "https://oauth2.googleapis.com/token"
    JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"
    ISSUERS = ("https://accounts.google.com", "accounts.google.com")

    def __init__(self, client_id: str, client_secret: str, http: httpx.AsyncClient | None = None,
                 jwks: jwt.PyJWKClient | None = None) -> None:
        self.client_id, self.client_secret = client_id, client_secret
        self.http = http or httpx.AsyncClient(timeout=10.0)
        self.jwks = jwks or jwt.PyJWKClient(self.JWKS_URL, cache_keys=True, lifespan=3600)

    def authorization_url(self, *, state: str, code_challenge: str, redirect_uri: str, nonce: str) -> str:
        return self.AUTH_URL + "?" + urlencode({
            "client_id": self.client_id, "response_type": "code", "scope": "openid profile",
            "redirect_uri": redirect_uri, "state": state, "nonce": nonce,
            "code_challenge": code_challenge, "code_challenge_method": "S256",
        })

    async def exchange(self, *, code: str, code_verifier: str, redirect_uri: str) -> ProviderTokens:
        try:
            resp = await self.http.post(self.TOKEN_URL, data={
                "grant_type": "authorization_code", "code": code, "code_verifier": code_verifier,
                "redirect_uri": redirect_uri, "client_id": self.client_id, "client_secret": self.client_secret,
            })
        except httpx.TimeoutException as e:
            raise ApiError("BUSY", "Login provider timed out") from e
        except httpx.HTTPError as e:
            raise ApiError("BUSY", "Login provider unreachable") from e
        if resp.status_code != 200:
            raise ApiError("UNAUTHENTICATED", "Login provider refused the code")
        body = resp.json()
        return ProviderTokens(body.get("access_token", ""), body.get("id_token"), body)

    async def identity(self, tokens: ProviderTokens, *, nonce: str) -> ProviderIdentity:
        if not tokens.id_token:
            raise ApiError("UNAUTHENTICATED", "No ID token")
        try:
            key = await asyncio.to_thread(self.jwks.get_signing_key_from_jwt, tokens.id_token)
            claims = jwt.decode(tokens.id_token, key.key, algorithms=["RS256"], audience=self.client_id,
                                issuer=list(self.ISSUERS), options={"require": ["exp", "iss", "aud", "sub"]})
        except jwt.PyJWTError as e:
            raise ApiError("UNAUTHENTICATED", "Invalid ID token") from e
        if claims.get("nonce") != nonce:
            raise ApiError("UNAUTHENTICATED", "ID token nonce mismatch")
        return ProviderIdentity(str(claims["sub"]), str(claims.get("name") or "Google user"))


class DevProvider:
    """Local logins for development and tests: a page at /v1/auth/dev/authorize redirects back with a signed code
    naming the user. Refused at startup when PHOTON_ENV=prod."""

    name = "dev"

    def __init__(self, settings: Settings, key: jwtutil.SigningKey) -> None:
        self.settings, self.key = settings, key

    def authorization_url(self, *, state: str, code_challenge: str, redirect_uri: str, nonce: str) -> str:
        return f"{self.settings.api_base_url}/v1/auth/dev/authorize?" + urlencode(
            {"state": state, "redirect_uri": redirect_uri, "nonce": nonce})

    def make_code(self, user: str, nonce: str) -> str:
        now_s = clock.now_ms() // 1000
        return jwtutil.sign({"iss": "photon-dev", "aud": "dev-code", "sub": user, "nonce": nonce,
                             "iat": now_s, "exp": now_s + 120}, self.key)

    async def exchange(self, *, code: str, code_verifier: str, redirect_uri: str) -> ProviderTokens:
        return ProviderTokens(code, None, {})

    async def identity(self, tokens: ProviderTokens, *, nonce: str) -> ProviderIdentity:
        claims = await jwtutil.verify(tokens.access_token, jwtutil.StaticKeys({self.key.kid: self.key.public}),
                                      audience="dev-code", issuer="photon-dev")
        if claims.get("nonce") != nonce:
            raise ApiError("UNAUTHENTICATED", "Code nonce mismatch")
        user = str(claims["sub"])
        return ProviderIdentity(user, user)
