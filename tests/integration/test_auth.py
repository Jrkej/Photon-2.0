"""Phase 1: OAuth state and PKCE, account identity, token rotation with the grace window, devices."""

from __future__ import annotations

import asyncio
import time
from typing import Any
from urllib.parse import parse_qs, urlencode, urlparse

import httpx
import jwt as pyjwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from sqlalchemy import select

from photon import db
from photon.auth.app import find_or_create_user
from photon.auth.providers import GoogleProvider
from photon.client.api import DEV_REDIRECT, ClientError, PhotonClient, pkce_pair
from photon.common import clock
from photon.common.errors import ApiError
from photon.common.pb import photon_pb2 as pb
from tests.conftest import Login, Stack


async def start_login(c: PhotonClient, user: str, redirect: str = DEV_REDIRECT) -> tuple[str, str, str]:
    verifier, challenge = pkce_pair()
    start = await c.call("POST", "/v1/auth/oauth/start", pb.OAuthStartRequest(
        provider="dev", redirect_uri=redirect, code_challenge=challenge), pb.OAuthStartResponse, auth=None)
    assert start is not None
    resp = await c.http.get(f"{start.authorization_url}&{urlencode({'user': user})}")
    q = parse_qs(urlparse(resp.headers["location"]).query)
    return q["code"][0], q["state"][0], verifier


async def callback(c: PhotonClient, code: str, state: str, verifier: str,
                   redirect: str = DEV_REDIRECT) -> pb.TokenPair | None:
    return await c.call("POST", "/v1/auth/oauth/callback", pb.OAuthCallbackRequest(
        code=code, state=state, code_verifier=verifier, redirect_uri=redirect), pb.TokenPair, auth=None)


async def test_same_account_same_user(login: Login) -> None:
    a1, a2, b = await login("alice"), await login("alice"), await login("bob")
    assert a1.uid == a2.uid != b.uid


async def test_state_works_once(env: Stack) -> None:
    c = PhotonClient(env.api_url, env.image_url)
    code, state, verifier = await start_login(c, "carol")
    assert await callback(c, code, state, verifier)
    with pytest.raises(ClientError) as e:
        await callback(c, code, state, verifier)
    assert e.value.status == 401
    await c.aclose()


async def test_racing_callbacks_one_wins(env: Stack) -> None:
    c = PhotonClient(env.api_url, env.image_url)
    code, state, verifier = await start_login(c, "dora")
    results = await asyncio.gather(*(callback(c, code, state, verifier) for _ in range(5)), return_exceptions=True)
    assert sum(1 for r in results if isinstance(r, pb.TokenPair)) == 1
    await c.aclose()


async def test_wrong_verifier_fails(env: Stack) -> None:
    c = PhotonClient(env.api_url, env.image_url)
    code, state, _ = await start_login(c, "erin")
    other, _ = pkce_pair()
    with pytest.raises(ClientError) as e:
        await callback(c, code, state, other)
    assert e.value.code == "UNAUTHENTICATED"
    await c.aclose()


async def test_redirect_uri_must_match_exactly(env: Stack) -> None:
    c = PhotonClient(env.api_url, env.image_url)
    _, challenge = pkce_pair()
    for bad in ("photon://auth/cb/extra", "https://photon.example/auth/cb?x=1", "https://evil.example/auth/cb"):
        with pytest.raises(ClientError) as e:
            await c.call("POST", "/v1/auth/oauth/start", pb.OAuthStartRequest(
                provider="dev", redirect_uri=bad, code_challenge=challenge), pb.OAuthStartResponse, auth=None)
        assert e.value.status == 400
    code, state, verifier = await start_login(c, "fred")
    with pytest.raises(ClientError):
        await callback(c, code, state, verifier, redirect="https://photon.example/auth/cb")
    await c.aclose()


async def test_bad_challenge_and_unknown_provider(env: Stack) -> None:
    c = PhotonClient(env.api_url, env.image_url)
    for req in (pb.OAuthStartRequest(provider="dev", redirect_uri=DEV_REDIRECT, code_challenge="short"),
                pb.OAuthStartRequest(provider="nope", redirect_uri=DEV_REDIRECT, code_challenge="a" * 43)):
        with pytest.raises(ClientError) as e:
            await c.call("POST", "/v1/auth/oauth/start", req, pb.OAuthStartResponse, auth=None)
        assert e.value.code == "INVALID_REQUEST"
    await c.aclose()


async def test_refresh_grace_window_and_reuse_detection(login: Login) -> None:
    c = await login("gina")
    old = c.tokens.refresh_token
    # Two refreshes with the same token 100 ms apart: both succeed (concurrent refresh, retried request)
    r1 = await c.call("POST", "/v1/auth/refresh", pb.RefreshRequest(refresh_token=old), pb.TokenPair, auth=None)
    await asyncio.sleep(0.1)
    r2 = await c.call("POST", "/v1/auth/refresh", pb.RefreshRequest(refresh_token=old), pb.TokenPair, auth=None)
    assert r1 and r2 and r1.refresh_token != r2.refresh_token and r1.user_id == r2.user_id == c.uid
    # A replay 31 s later revokes the whole family
    clock.advance(31_000)
    with pytest.raises(ClientError) as e:
        await c.call("POST", "/v1/auth/refresh", pb.RefreshRequest(refresh_token=old), pb.TokenPair, auth=None)
    assert e.value.code == "UNAUTHENTICATED"
    for tok in (r1.refresh_token, r2.refresh_token):
        with pytest.raises(ClientError):
            await c.call("POST", "/v1/auth/refresh", pb.RefreshRequest(refresh_token=tok), pb.TokenPair, auth=None)


async def test_expired_access_token_is_refreshed_once(login: Login, env: Stack) -> None:
    """Five calls fail with TOKEN_EXPIRED at once; single-flight means exactly one rotation."""
    c = await login("hank")
    info = await c.create_session()
    family = c.tokens.refresh_token.split(".")[0]
    clock.advance(16 * 60 * 1000)
    results = await asyncio.gather(*(c.session(info.session_id) for _ in range(5)))
    assert all(r.session_id == info.session_id for r in results)
    in_family = [k async for k in env.redis.scan_iter(f"refresh:{{{family}}}:*")]
    assert len(in_family) == 2  # the first token and exactly one rotation


async def test_identity_is_provider_and_id_never_email(env: Stack) -> None:
    engine = db.make_engine(env.settings.database_url)
    try:
        a = await find_or_create_user(engine, "google", "same-id", "A")
        b = await find_or_create_user(engine, "apple", "same-id", "B")
        assert a != b
        racers = await asyncio.gather(*(find_or_create_user(engine, "google", "race", "R") for _ in range(8)))
        assert len(set(racers)) == 1
        async with engine.connect() as conn:
            n = (await conn.execute(select(db.identities).where(db.identities.c.provider_user_id == "race"))).all()
        assert len(n) == 1
    finally:
        await engine.dispose()


async def test_device_registration(login: Login, env: Stack) -> None:
    a, b = await login("ivy"), await login("jack")
    await a.register_device("android", "fcm-token-123456")
    await b.register_device("ios", "fcm-token-123456")  # the device changed hands
    engine = db.make_engine(env.settings.database_url)
    async with engine.connect() as conn:
        rows = (await conn.execute(select(db.devices).where(db.devices.c.push_token == "fcm-token-123456"))).all()
    await engine.dispose()
    assert len(rows) == 1 and str(rows[0].user_id) == b.uid and rows[0].platform == "ios"
    with pytest.raises(ClientError):
        await a.register_device("windows", "fcm-token-123456")


async def test_jwks(env: Stack) -> None:
    async with httpx.AsyncClient() as h:
        doc = (await h.get(f"{env.api_url}/.well-known/jwks.json")).json()
    assert [k["kid"] for k in doc["keys"]] == [env.keys.auth.kid]


# ---------------------------------------------------------------------- Google (OIDC), mocked


class StubJwks:
    def __init__(self, key: Any) -> None:
        self.key = key

    def get_signing_key_from_jwt(self, token: str) -> Any:
        return type("K", (), {"key": self.key})()


def google(id_claims: dict[str, Any], rsa_key: Any) -> GoogleProvider:
    id_token = pyjwt.encode(id_claims, rsa_key, algorithm="RS256", headers={"kid": "g1"})

    def handler(request: httpx.Request) -> httpx.Response:
        body = parse_qs(request.content.decode())
        assert body["code_verifier"] == ["v" * 43] and body["grant_type"] == ["authorization_code"]
        return httpx.Response(200, json={"access_token": "at", "id_token": id_token})

    return GoogleProvider("client-1", "secret", http=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
                          jwks=StubJwks(rsa_key.public_key()))  # type: ignore[arg-type]


@pytest.mark.parametrize("override, ok", [
    ({}, True),
    ({"iss": "https://evil.example"}, False),
    ({"aud": "someone-else"}, False),
    ({"nonce": "other"}, False),
    ({"exp": int(time.time()) - 10}, False),
])
async def test_google_id_token_checks(override: dict[str, Any], ok: bool) -> None:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    claims = {"iss": "https://accounts.google.com", "aud": "client-1", "sub": "g-123", "nonce": "n1",
              "exp": int(time.time()) + 300, "name": "G User", **override}
    p = google(claims, key)
    tokens = await p.exchange(code="c", code_verifier="v" * 43, redirect_uri="https://photon.example/auth/cb")
    if ok:
        ident = await p.identity(tokens, nonce="n1")
        assert ident.provider_user_id == "g-123"
    else:
        with pytest.raises(ApiError):
            await p.identity(tokens, nonce="n1")


async def test_google_url_has_pkce_and_state() -> None:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    p = google({}, key)
    url = p.authorization_url(state="s1", code_challenge="c" * 43, redirect_uri="https://photon.example/auth/cb",
                              nonce="n1")
    q = parse_qs(urlparse(url).query)
    assert q["code_challenge_method"] == ["S256"] and q["state"] == ["s1"] and q["nonce"] == ["n1"]
