"""Auth: the only holder of the access-token signing key. Never on the path of an ordinary request."""

from __future__ import annotations

import base64
import hashlib
import hmac
import html
import json
import secrets
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any
from urllib.parse import urlencode

from fastapi import FastAPI, Query, Request, Response
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncEngine

from photon import db
from photon.auth.providers import DevProvider, GitHubProvider, GoogleProvider, Provider
from photon.auth.tokens import Tokens
from photon.common import clock, jwtutil, ratelimit
from photon.common.config import Settings
from photon.common.errors import ApiError
from photon.common.httpapp import add_readyz, client_ip, create_base_app, user_dependency
from photon.common.pb import photon_pb2 as pb
from photon.common.proto import parse_body, proto_response
from photon.common.redisutil import make_client

CHALLENGE_LEN = 43  # base64url(sha256), no padding


@dataclass
class AuthKeys:
    signing: jwtutil.SigningKey
    published: dict[str, Any] = field(default_factory=dict)  # kid -> public key, for the JWKS (rotation)


def load_keys(settings: Settings) -> AuthKeys:
    signing = jwtutil.load_signing_key(settings.keys_dir, "auth-access")
    return AuthKeys(signing, jwtutil.load_public_keys(settings.keys_dir, "auth-access"))


def s256(verifier: str) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()


async def find_or_create_user(engine: AsyncEngine, provider: str, provider_user_id: str, display_name: str) -> str:
    """By (provider, provider_user_id) only; never by email. Two first logins racing end with one user."""
    ident = db.identities
    async with engine.begin() as conn:
        row = (await conn.execute(select(ident.c.user_id).where(
            ident.c.provider == provider, ident.c.provider_user_id == provider_user_id))).first()
        if row:
            return str(row.user_id)
        uid = (await conn.execute(db.users.insert().values(display_name=display_name)
                                  .returning(db.users.c.id))).scalar_one()
        await conn.execute(insert(ident).values(provider=provider, provider_user_id=provider_user_id, user_id=uid)
                           .on_conflict_do_nothing())
        row = (await conn.execute(select(ident.c.user_id).where(
            ident.c.provider == provider, ident.c.provider_user_id == provider_user_id))).first()
        assert row is not None
        if str(row.user_id) != str(uid):
            await conn.execute(db.users.delete().where(db.users.c.id == uid))  # lost the race
        return str(row.user_id)


def create_app(settings: Settings | None = None, keys: AuthKeys | None = None,
               providers: dict[str, Provider] | None = None) -> FastAPI:
    settings = settings or Settings()
    keys = keys or load_keys(settings)
    if not keys.published:
        keys.published = {keys.signing.kid: keys.signing.public}
    redis = make_client(settings.redis_url, settings.redis_pool)
    engine = db.make_engine(settings.database_url)
    tokens = Tokens(settings, keys.signing, redis)
    dev = DevProvider(settings, keys.signing)
    if providers is None:
        providers = {}
        if settings.google_client_id:
            providers["google"] = GoogleProvider(settings.google_client_id, settings.google_client_secret)
        if settings.github_client_id:
            providers["github"] = GitHubProvider(settings.github_client_id, settings.github_client_secret,
                                                 f"{settings.api_base_url}/v1/auth/oauth/return")
        if settings.env != "prod":
            providers["dev"] = dev
    if settings.env == "prod" and "dev" in providers:
        raise RuntimeError("the dev login provider must not run in production")

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        try:
            yield
        finally:
            await redis.aclose()
            await engine.dispose()

    app = create_base_app("photon-auth", settings, lifespan)
    current_user = user_dependency(jwtutil.StaticKeys(dict(keys.published)))

    @app.get("/.well-known/jwks.json")
    async def jwks() -> Response:
        return JSONResponse(jwtutil.jwks_document(dict(keys.published)),
                            headers={"Cache-Control": "public, max-age=300"})

    @app.post("/v1/auth/oauth/start")
    async def oauth_start(request: Request) -> Response:
        await ratelimit.hit(redis, f"rl:oauth:{client_ip(request, settings)}", settings.oauth_per_minute, 60)
        body = await parse_body(request, pb.OAuthStartRequest)
        provider = providers.get(body.provider)
        if provider is None:
            raise ApiError("INVALID_REQUEST", "Unknown provider")
        if body.redirect_uri not in settings.redirect_uris:  # exact match: no prefixes, no wildcards
            raise ApiError("INVALID_REQUEST", "Redirect URI is not registered")
        if len(body.code_challenge) != CHALLENGE_LEN or not all(
                c.isalnum() or c in "-_" for c in body.code_challenge):
            raise ApiError("INVALID_REQUEST", "code_challenge must be an S256 challenge")
        state, nonce = secrets.token_urlsafe(32), secrets.token_urlsafe(16)
        record = {"provider": body.provider, "code_challenge": body.code_challenge,
                  "redirect_uri": body.redirect_uri, "nonce": nonce, "created_at": clock.now_ms()}
        await redis.set(f"oauth:{state}", json.dumps(record), ex=600, nx=True)
        url = provider.authorization_url(state=state, code_challenge=body.code_challenge,
                                         redirect_uri=body.redirect_uri, nonce=nonce)
        return proto_response(pb.OAuthStartResponse(authorization_url=url, state=state))

    @app.post("/v1/auth/oauth/callback")
    async def oauth_callback(request: Request) -> Response:
        await ratelimit.hit(redis, f"rl:oauth:{client_ip(request, settings)}", settings.oauth_per_minute, 60)
        body = await parse_body(request, pb.OAuthCallbackRequest)
        raw = await redis.getdel(f"oauth:{body.state}") if body.state else None
        if raw is None:  # missing, expired or already used: a state works exactly once
            raise ApiError("UNAUTHENTICATED", "Login attempt expired or already used")
        rec = json.loads(raw)
        if body.redirect_uri != rec["redirect_uri"] or body.redirect_uri not in settings.redirect_uris:
            raise ApiError("UNAUTHENTICATED", "Redirect URI mismatch")
        if not hmac.compare_digest(s256(body.code_verifier), rec["code_challenge"]):
            raise ApiError("UNAUTHENTICATED", "PKCE verifier does not match")
        provider = providers.get(rec["provider"])
        if provider is None:
            raise ApiError("UNAUTHENTICATED", "Unknown provider")
        ptokens = await provider.exchange(code=body.code, code_verifier=body.code_verifier,
                                          redirect_uri=body.redirect_uri)
        ident = await provider.identity(ptokens, nonce=rec["nonce"])
        uid = await find_or_create_user(engine, provider.name, ident.provider_user_id, ident.display_name)
        return proto_response(await tokens.pair(uid))

    @app.post("/v1/auth/refresh")
    async def refresh(request: Request) -> Response:
        body = await parse_body(request, pb.RefreshRequest)
        family = Tokens.family_of(body.refresh_token)
        await ratelimit.hit(redis, f"rl:refresh:{family}", settings.refresh_per_minute, 60)
        return proto_response(await tokens.rotate(body.refresh_token))

    @app.put("/v1/devices/current")
    async def register_device(request: Request) -> Response:
        uid = await current_user(request)
        body = await parse_body(request, pb.DeviceRegistration)
        if body.platform not in ("android", "ios") or not 10 <= len(body.push_token) <= 4096:
            raise ApiError("INVALID_REQUEST", "platform must be android or ios, with a push token")
        stmt = insert(db.devices).values(user_id=uid, platform=body.platform, push_token=body.push_token)
        stmt = stmt.on_conflict_do_update(index_elements=[db.devices.c.push_token],
                                          set_={"user_id": uid, "platform": body.platform, "updated_at": clock_now()})
        async with engine.begin() as conn:
            await conn.execute(stmt)
        return Response(status_code=204)

    @app.get("/v1/auth/config")
    async def auth_config() -> Response:
        """What the sign-in screen offers. The Google client id is public (it is in every ID token)."""
        google = providers.get("google")
        return proto_response(pb.AuthConfig(
            google_client_id=google.client_id if isinstance(google, GoogleProvider) else "",
            dev_login="dev" in providers,
            github_login="github" in providers,
        ))

    @app.get("/v1/auth/oauth/return")
    async def oauth_return(state: str = Query(""), code: str = Query(""), error: str = Query("")) -> Response:
        """Where a provider with one fixed callback URL (GitHub) sends the browser back. Forwards the code and
        state to the app's redirect URI from the login attempt; the state stays unused until the app's callback."""
        raw = await redis.get(f"oauth:{state}") if state else None
        headers = {"Cache-Control": "no-store", "Referrer-Policy": "no-referrer"}
        if raw is None:
            return HTMLResponse(_return_page(None, "This sign-in has expired. Go back to PHOTON and try again."),
                                status_code=400, headers=headers)
        rec = json.loads(raw)
        params = {"error": error[:64], "state": state} if error or not code else {"code": code, "state": state}
        target = rec["redirect_uri"] + ("&" if "?" in rec["redirect_uri"] else "?") + urlencode(params)
        return HTMLResponse(_return_page(target, "Returning to PHOTON…"), headers=headers)

    @app.post("/v1/auth/nonce")
    async def new_nonce(request: Request) -> Response:
        """A single-use nonce for an ID token from the platform account picker (10 minutes)."""
        await ratelimit.hit(redis, f"rl:oauth:{client_ip(request, settings)}", settings.oauth_per_minute, 60)
        nonce = secrets.token_urlsafe(24)
        await redis.set(f"oauth:nonce:{nonce}", "1", ex=600, nx=True)
        return proto_response(pb.NonceResponse(nonce=nonce, expires_at_ms=clock.now_ms() + 600_000))

    @app.post("/v1/auth/google")
    async def google_sign_in(request: Request) -> Response:
        """Sign in with a Google ID token from Android Credential Manager (or iOS). The token's nonce must be one
        this server issued and not yet used, so a captured token can't be replayed."""
        await ratelimit.hit(redis, f"rl:oauth:{client_ip(request, settings)}", settings.oauth_per_minute, 60)
        body = await parse_body(request, pb.GoogleSignInRequest, max_bytes=16 * 1024)
        google = providers.get("google")
        if not isinstance(google, GoogleProvider):
            raise ApiError("INVALID_REQUEST", "Google sign-in is not configured on this server")
        if not body.nonce or await redis.getdel(f"oauth:nonce:{body.nonce}") is None:
            raise ApiError("UNAUTHENTICATED", "Sign-in attempt expired or already used")
        ident = await google.verify_id_token(body.id_token, nonce=body.nonce)
        uid = await find_or_create_user(engine, "google", ident.provider_user_id, ident.display_name)
        return proto_response(await tokens.pair(uid))

    if "dev" in providers:
        @app.post("/v1/auth/dev/login")
        async def dev_login(request: Request) -> Response:
            """Development only: sign in with a bare username, no browser."""
            await ratelimit.hit(redis, f"rl:oauth:{client_ip(request, settings)}", settings.oauth_per_minute, 60)
            body = await parse_body(request, pb.DevLoginRequest)
            user = body.username.strip()
            if not user or len(user) > 40 or not all(c.isalnum() or c in "_.-" for c in user):
                raise ApiError("INVALID_REQUEST", "Usernames are 1-40 letters, digits, '_', '.' or '-'")
            uid = await find_or_create_user(engine, "dev", user, user)
            return proto_response(await tokens.pair(uid))

        @app.get("/v1/auth/dev/authorize")
        async def dev_authorize(state: str = Query(...), redirect_uri: str = Query(...), nonce: str = Query(...),
                                user: str | None = Query(None)) -> Response:
            if redirect_uri not in settings.redirect_uris:
                raise ApiError("INVALID_REQUEST", "Redirect URI is not registered")
            if not user:
                hidden = "".join(f'<input type="hidden" name="{k}" value="{html.escape(v)}">'
                                 for k, v in (("state", state), ("redirect_uri", redirect_uri), ("nonce", nonce)))
                return HTMLResponse(
                    "<!doctype html><meta name=viewport content='width=device-width'><title>PHOTON dev login</title>"
                    "<form method=get><h1>Dev login</h1><label>Username <input name=user required "
                    f"pattern='[A-Za-z0-9_.-]{{1,40}}' autofocus></label>{hidden}<button>Sign in</button></form>")
            if not all(c.isalnum() or c in "_.-" for c in user) or len(user) > 40:
                raise ApiError("INVALID_REQUEST", "Invalid username")
            code = dev.make_code(user, nonce)
            sep = "&" if "?" in redirect_uri else "?"
            return RedirectResponse(f"{redirect_uri}{sep}{urlencode({'code': code, 'state': state})}", 302)

    @app.get("/.well-known/assetlinks.json")
    async def assetlinks() -> Response:
        return JSONResponse([{
            "relation": ["delegate_permission/common.handle_all_urls"],
            "target": {"namespace": "android_app", "package_name": "com.photon.app",
                       "sha256_cert_fingerprints": settings.android_cert_sha256},
        }])

    @app.get("/.well-known/apple-app-site-association")
    async def aasa() -> Response:
        return JSONResponse({"applinks": {"details": [{"appIDs": [settings.ios_app_id],
                                                       "components": [{"/": "/auth/cb"}, {"/": "/j/*"}]}]},
                             "webcredentials": {"apps": [settings.ios_app_id]}})

    async def ready() -> None:
        await redis.ping()
        async with engine.connect() as conn:
            await conn.execute(select(1))

    add_readyz(app, ready)
    app.state.tokens, app.state.engine, app.state.redis = tokens, engine, redis
    return app


def _return_page(target: str | None, message: str) -> str:
    link = ""
    if target:
        t = html.escape(target, quote=True)
        link = (f'<meta http-equiv="refresh" content="0;url={t}">'
                f'<p><a class="b" href="{t}">Return to PHOTON</a></p>'
                f"<script>location.replace({json.dumps(target)})</script>")
    return ("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
            "<title>PHOTON</title><style>body{font:16px system-ui;margin:3em 1.5em;text-align:center}"
            ".b{display:inline-block;margin-top:1em;padding:.8em 1.6em;border-radius:2em;background:#24292f;"
            "color:#fff;text-decoration:none}</style>"
            f"<h1>PHOTON</h1><p>{html.escape(message)}</p>{link}")


def clock_now() -> datetime:
    return datetime.fromtimestamp(clock.now_ms() / 1000, tz=UTC)
