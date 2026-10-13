"""GitHub sign-in: the browser flow with one fixed callback URL, forwarded to the app's redirect by the server."""

from __future__ import annotations

import re
from collections.abc import AsyncIterator
from typing import Any
from urllib.parse import parse_qs, urlparse

import httpx
import pytest_asyncio

from photon.auth import app as auth_app
from photon.auth.providers import GitHubProvider
from photon.client.api import DEV_REDIRECT, PROTO, pkce_pair
from photon.common.pb import photon_pb2 as pb
from tests.conftest import Stack

CALLBACK = "http://127.0.0.1:8080/v1/auth/oauth/return"


class FakeGitHub:
    """GitHub's token and user endpoints."""

    def __init__(self) -> None:
        self.user: dict[str, Any] = {"id": 4242, "login": "octo", "name": "Octo Cat"}
        self.token_requests: list[dict[str, list[str]]] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        if request.url.path == "/login/oauth/access_token":
            form = parse_qs(request.content.decode())
            self.token_requests.append(form)
            if form["code"] == ["good-code"] and form["client_secret"] == ["s3cret"]:
                return httpx.Response(200, json={"access_token": "gho_x", "token_type": "bearer"})
            return httpx.Response(200, json={"error": "bad_verification_code"})
        if request.url.path == "/user":
            assert request.headers["authorization"] == "Bearer gho_x"
            return httpx.Response(200, json=self.user)
        return httpx.Response(404)


@pytest_asyncio.fixture(loop_scope="session")
async def gh(env: Stack) -> AsyncIterator[tuple[httpx.AsyncClient, FakeGitHub]]:
    fake = FakeGitHub()
    provider = GitHubProvider("gh-client", "s3cret", CALLBACK,
                              http=httpx.AsyncClient(transport=httpx.MockTransport(fake.handler)))
    app = auth_app.create_app(env.settings, auth_app.AuthKeys(env.keys.auth), providers={"github": provider})
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://auth") as c:
        yield c, fake
    await app.state.redis.aclose()
    await app.state.engine.dispose()


async def start(c: httpx.AsyncClient) -> tuple[str, str, str]:
    verifier, challenge = pkce_pair()
    r = await c.post("/v1/auth/oauth/start", headers={"Content-Type": PROTO}, content=pb.OAuthStartRequest(
        provider="github", redirect_uri=DEV_REDIRECT, code_challenge=challenge).SerializeToString())
    assert r.status_code == 200
    out = pb.OAuthStartResponse()
    out.ParseFromString(r.content)
    return out.authorization_url, out.state, verifier


def forwarded_to(page: str) -> str:
    m = re.search(r'url=([^"]+)"', page)
    assert m, page
    return m.group(1).replace("&amp;", "&")


async def callback(c: httpx.AsyncClient, code: str, state: str, verifier: str) -> httpx.Response:
    return await c.post("/v1/auth/oauth/callback", headers={"Content-Type": PROTO}, content=pb.OAuthCallbackRequest(
        code=code, state=state, code_verifier=verifier, redirect_uri=DEV_REDIRECT).SerializeToString())


async def test_github_sign_in(gh: tuple[httpx.AsyncClient, FakeGitHub]) -> None:
    c, fake = gh
    cfg = pb.AuthConfig()
    cfg.ParseFromString((await c.get("/v1/auth/config")).content)
    assert cfg.github_login
    url, state, verifier = await start(c)
    q = parse_qs(urlparse(url).query)
    assert url.startswith("https://github.com/login/oauth/authorize")
    assert q["redirect_uri"] == [CALLBACK] and q["state"] == [state] and q["code_challenge_method"] == ["S256"]
    # GitHub sends the browser back to the server, which forwards to the app's redirect
    page = await c.get("/v1/auth/oauth/return", params={"code": "good-code", "state": state})
    assert page.status_code == 200 and page.headers["cache-control"] == "no-store"
    target = urlparse(forwarded_to(page.text))
    assert f"{target.scheme}://{target.netloc}{target.path}" == DEV_REDIRECT
    assert parse_qs(target.query) == {"code": ["good-code"], "state": [state]}
    r = await callback(c, "good-code", state, verifier)
    assert r.status_code == 200
    pair = pb.TokenPair()
    pair.ParseFromString(r.content)
    assert pair.access_token
    # The exchange used the registered callback, the client secret and the app's PKCE verifier
    sent = fake.token_requests[-1]
    assert sent["redirect_uri"] == [CALLBACK] and sent["code_verifier"] == [verifier]
    # The same GitHub account (by numeric id) is the same user, even after renaming
    fake.user = {"id": 4242, "login": "renamed"}
    _, state2, verifier2 = await start(c)
    r2 = await callback(c, "good-code", state2, verifier2)
    again = pb.TokenPair()
    again.ParseFromString(r2.content)
    assert again.user_id == pair.user_id


async def test_denied_and_expired(gh: tuple[httpx.AsyncClient, FakeGitHub]) -> None:
    c, _ = gh
    _, state, _ = await start(c)
    page = await c.get("/v1/auth/oauth/return", params={"error": "access_denied", "state": state})
    assert parse_qs(urlparse(forwarded_to(page.text)).query) == {"error": ["access_denied"], "state": [state]}
    expired = await c.get("/v1/auth/oauth/return", params={"code": "x", "state": "unknown"})
    assert expired.status_code == 400 and "expired" in expired.text


async def test_bad_code_and_wrong_verifier(gh: tuple[httpx.AsyncClient, FakeGitHub]) -> None:
    c, _ = gh
    _, state, verifier = await start(c)
    assert (await callback(c, "bad-code", state, verifier)).status_code == 401
    _, state, _ = await start(c)
    other, _ = pkce_pair()
    assert (await callback(c, "good-code", state, other)).status_code == 401  # PKCE between app and server


async def test_github_off_by_default(env: Stack) -> None:
    async with httpx.AsyncClient() as h:
        cfg = pb.AuthConfig()
        cfg.ParseFromString((await h.get(f"{env.api_url}/v1/auth/config")).content)
    assert not cfg.github_login
