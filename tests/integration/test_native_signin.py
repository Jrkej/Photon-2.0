"""Native sign-in: Google ID tokens from the platform account picker (single-use nonce), the sign-in config, and
the development username login."""

from __future__ import annotations

import time
from collections.abc import AsyncIterator
from typing import Any

import httpx
import jwt as pyjwt
import pytest
import pytest_asyncio
from cryptography.hazmat.primitives.asymmetric import rsa

from photon.auth import app as auth_app
from photon.auth.providers import DevProvider, GoogleProvider
from photon.client.api import PROTO
from photon.common.pb import photon_pb2 as pb
from tests.conftest import Stack

CLIENT_ID = "web-client-1.apps.googleusercontent.com"
RSA_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


class StubJwks:
    def get_signing_key_from_jwt(self, token: str) -> Any:
        return type("K", (), {"key": RSA_KEY.public_key()})()


def id_token(token_nonce: str, **over: Any) -> str:
    claims = {"iss": "https://accounts.google.com", "aud": CLIENT_ID, "azp": "android-client",
              "sub": "g-42", "name": "Ada", "nonce": token_nonce, "exp": int(time.time()) + 600, **over}
    return pyjwt.encode(claims, RSA_KEY, algorithm="RS256", headers={"kid": "g1"})


@pytest_asyncio.fixture(loop_scope="session")
async def auth(env: Stack) -> AsyncIterator[httpx.AsyncClient]:
    settings = env.settings.model_copy(update={"google_client_id": CLIENT_ID})
    google = GoogleProvider(CLIENT_ID, "", jwks=StubJwks())  # type: ignore[arg-type]
    app = auth_app.create_app(settings, auth_app.AuthKeys(env.keys.auth),
                              providers={"google": google, "dev": DevProvider(settings, env.keys.auth)})
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://auth") as c:
        yield c
    await app.state.redis.aclose()
    await app.state.engine.dispose()


async def post(c: httpx.AsyncClient, path: str, msg: Any = None) -> httpx.Response:
    body = msg.SerializeToString() if msg is not None else b""
    return await c.post(path, content=body, headers={"Content-Type": PROTO})


def parsed(resp: httpx.Response, cls: Any) -> Any:
    out = cls()
    out.ParseFromString(resp.content)
    return out


async def nonce(c: httpx.AsyncClient) -> str:
    r = await post(c, "/v1/auth/nonce")
    assert r.status_code == 200
    return str(parsed(r, pb.NonceResponse).nonce)


async def test_config_lists_the_sign_in_methods(auth: httpx.AsyncClient, env: Stack) -> None:
    cfg = parsed(await auth.get("/v1/auth/config"), pb.AuthConfig)
    assert cfg.google_client_id == CLIENT_ID and cfg.dev_login
    # The shared test stack has no Google client: the app hides the button
    async with httpx.AsyncClient() as h:
        plain = parsed(await h.get(f"{env.api_url}/v1/auth/config"), pb.AuthConfig)
    assert plain.google_client_id == "" and plain.dev_login


async def test_google_sign_in(auth: httpx.AsyncClient) -> None:
    n = await nonce(auth)
    r = await post(auth, "/v1/auth/google", pb.GoogleSignInRequest(id_token=id_token(n), nonce=n))
    assert r.status_code == 200
    first = parsed(r, pb.TokenPair)
    assert first.access_token and first.refresh_token
    n2 = await nonce(auth)
    again = parsed(await post(auth, "/v1/auth/google", pb.GoogleSignInRequest(id_token=id_token(n2), nonce=n2)),
                   pb.TokenPair)
    assert again.user_id == first.user_id  # the same Google account is the same user


async def test_nonce_is_single_use(auth: httpx.AsyncClient) -> None:
    n = await nonce(auth)
    req = pb.GoogleSignInRequest(id_token=id_token(n), nonce=n)
    assert (await post(auth, "/v1/auth/google", req)).status_code == 200
    replay = await post(auth, "/v1/auth/google", req)
    assert replay.status_code == 401 and parsed(replay, pb.Error).code == "UNAUTHENTICATED"


@pytest.mark.parametrize("override", [
    {"aud": "someone-else"},
    {"iss": "https://evil.example"},
    {"exp": int(time.time()) - 10},
    {"nonce": "a-different-nonce"},
])
async def test_bad_id_tokens_are_refused(auth: httpx.AsyncClient, override: dict[str, Any]) -> None:
    n = await nonce(auth)
    r = await post(auth, "/v1/auth/google", pb.GoogleSignInRequest(id_token=id_token(n, **override), nonce=n))
    assert r.status_code == 401


async def test_unknown_nonce_is_refused(auth: httpx.AsyncClient) -> None:
    r = await post(auth, "/v1/auth/google", pb.GoogleSignInRequest(id_token=id_token("made-up"), nonce="made-up"))
    assert r.status_code == 401


async def test_google_not_configured(env: Stack) -> None:
    async with httpx.AsyncClient() as h:
        r = await h.post(f"{env.api_url}/v1/auth/google", headers={"Content-Type": PROTO},
                         content=pb.GoogleSignInRequest(id_token="x", nonce="y").SerializeToString())
    assert r.status_code == 400 and "not configured" in parsed(r, pb.Error).message


async def test_dev_login(auth: httpx.AsyncClient, env: Stack) -> None:
    a = parsed(await post(auth, "/v1/auth/dev/login", pb.DevLoginRequest(username="ada")), pb.TokenPair)
    b = parsed(await post(auth, "/v1/auth/dev/login", pb.DevLoginRequest(username="ada")), pb.TokenPair)
    assert a.user_id == b.user_id and a.access_token
    bad = await post(auth, "/v1/auth/dev/login", pb.DevLoginRequest(username="no spaces allowed"))
    assert bad.status_code == 400
    # The same username through the browser dev flow is the same account
    from photon.client.api import PhotonClient

    c = PhotonClient(env.api_url, env.image_url)
    await c.login_dev("ada")
    assert c.uid == a.user_id
    await c.aclose()


async def test_dev_login_does_not_exist_in_production(env: Stack) -> None:
    settings = env.settings.model_copy(update={"env": "prod"})
    app = auth_app.create_app(settings, auth_app.AuthKeys(env.keys.auth))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://auth") as c:
        r = await post(c, "/v1/auth/dev/login", pb.DevLoginRequest(username="ada"))
        assert r.status_code == 404
        cfg = parsed(await c.get("/v1/auth/config"), pb.AuthConfig)
        assert not cfg.dev_login
    await app.state.redis.aclose()
    await app.state.engine.dispose()
