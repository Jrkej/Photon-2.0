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
        return await self.verify_id_token(tokens.id_token, nonce=nonce)

    async def verify_id_token(self, id_token: str, *, nonce: str) -> ProviderIdentity:
        """An ID token from the browser flow or from the platform account picker (Android Credential Manager,
        iOS): RS256 against Google's JWKS, issuer, audience = our server client id, expiry, and the nonce."""
        try:
            key = await asyncio.to_thread(self.jwks.get_signing_key_from_jwt, id_token)
            claims = jwt.decode(id_token, key.key, algorithms=["RS256"], audience=self.client_id,
                                issuer=list(self.ISSUERS), options={"require": ["exp", "iss", "aud", "sub"]})
        except (jwt.PyJWTError, jwt.PyJWKClientError) as e:
            raise ApiError("UNAUTHENTICATED", "Invalid ID token") from e
        if not nonce or claims.get("nonce") != nonce:
            raise ApiError("UNAUTHENTICATED", "ID token nonce mismatch")
        return ProviderIdentity(str(claims["sub"]), str(claims.get("name") or "Google user"))


class GitHubProvider:
    """GitHub OAuth App (not OIDC): the profile comes from the GitHub API, and only the numeric user id is kept as
    the identity. A GitHub OAuth App has one fixed callback URL, so GitHub returns the browser to this server
    (/v1/auth/oauth/return), which forwards the code to the app's own redirect URI. PKCE between the app and this
    server is checked as for every provider; the challenge is passed on to GitHub as well."""

    name = "github"
    AUTH_URL = "https://github.com/login/oauth/authorize"
    TOKEN_URL = "https://github.com/login/oauth/access_token"
    USER_URL = "https://api.github.com/user"

    def __init__(self, client_id: str, client_secret: str, callback_url: str,
                 http: httpx.AsyncClient | None = None) -> None:
        self.client_id, self.client_secret, self.callback_url = client_id, client_secret, callback_url
        self.http = http or httpx.AsyncClient(timeout=10.0)

    def authorization_url(self, *, state: str, code_challenge: str, redirect_uri: str, nonce: str) -> str:
        return self.AUTH_URL + "?" + urlencode({
            "client_id": self.client_id, "redirect_uri": self.callback_url, "scope": "read:user", "state": state,
            "code_challenge": code_challenge, "code_challenge_method": "S256", "allow_signup": "true",
        })

    async def exchange(self, *, code: str, code_verifier: str, redirect_uri: str) -> ProviderTokens:
        try:
            resp = await self.http.post(self.TOKEN_URL, headers={"Accept": "application/json"}, data={
                "client_id": self.client_id, "client_secret": self.client_secret, "code": code,
                "redirect_uri": self.callback_url, "code_verifier": code_verifier,
            })
        except httpx.TimeoutException as e:
            raise ApiError("BUSY", "GitHub timed out") from e
        except httpx.HTTPError as e:
            raise ApiError("BUSY", "GitHub unreachable") from e
        body = resp.json() if resp.headers.get("content-type", "").startswith("application/json") else {}
        token = body.get("access_token")
        if resp.status_code != 200 or not token:  # GitHub answers 200 with {"error": ...} for a bad code
            raise ApiError("UNAUTHENTICATED", "GitHub refused the sign-in")
        return ProviderTokens(str(token), None, body)

    async def identity(self, tokens: ProviderTokens, *, nonce: str) -> ProviderIdentity:
        try:
            resp = await self.http.get(self.USER_URL, headers={
                "Authorization": f"Bearer {tokens.access_token}", "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "photon",
            })
        except httpx.HTTPError as e:
            raise ApiError("BUSY", "GitHub unreachable") from e
        if resp.status_code != 200:
            raise ApiError("UNAUTHENTICATED", "Could not read the GitHub profile")
        user = resp.json()
        if not isinstance(user.get("id"), int):
            raise ApiError("UNAUTHENTICATED", "GitHub profile has no id")
        return ProviderIdentity(str(user["id"]), str(user.get("name") or user.get("login") or "GitHub user"))


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
