"""0.7: token verification refuses a wrong algorithm, audience, issuer, unknown kid, expiry and a foreign key."""

from __future__ import annotations

import jwt as pyjwt
import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from photon.common import clock, jwtutil
from photon.common.errors import ApiError

KEY = jwtutil.SigningKey("k1", Ed25519PrivateKey.generate())
OTHER = jwtutil.SigningKey("k1", Ed25519PrivateKey.generate())  # same kid, different key
KEYS = jwtutil.StaticKeys({KEY.kid: KEY.public})


def claims(**over: object) -> dict[str, object]:
    now = clock.now_ms() // 1000
    return {"sub": "u1", "iss": "photon-auth", "aud": "photon", "iat": now, "exp": now + 60, **over}


async def verify(token: str) -> dict[str, object]:
    return await jwtutil.verify(token, KEYS, audience="photon", issuer="photon-auth")


async def test_valid() -> None:
    assert (await verify(jwtutil.sign(claims(), KEY)))["sub"] == "u1"


@pytest.mark.parametrize(
    "token_factory, code",
    [
        (lambda: jwtutil.sign(claims(aud="image-server"), KEY), "UNAUTHENTICATED"),
        (lambda: jwtutil.sign(claims(iss="someone"), KEY), "UNAUTHENTICATED"),
        (lambda: jwtutil.sign(claims(exp=clock.now_ms() // 1000 - 1), KEY), "TOKEN_EXPIRED"),
        (lambda: jwtutil.sign(claims(), OTHER), "UNAUTHENTICATED"),
        (lambda: pyjwt.encode(claims(), "secret" * 8, algorithm="HS256", headers={"kid": "k1"}), "UNAUTHENTICATED"),
        (lambda: jwtutil.sign(claims(), jwtutil.SigningKey("unknown", KEY.private)), "UNAUTHENTICATED"),
        (lambda: "not.a.token", "UNAUTHENTICATED"),
    ],
)
async def test_refused(token_factory: object, code: str) -> None:
    with pytest.raises(ApiError) as e:
        await verify(token_factory())  # type: ignore[operator]
    assert e.value.code == code


async def test_missing_exp_refused() -> None:
    c = claims()
    del c["exp"]
    with pytest.raises(ApiError):
        await verify(jwtutil.sign(c, KEY))


async def test_expiry_follows_the_shared_clock() -> None:
    token = jwtutil.sign(claims(), KEY)
    clock.advance(61_000)
    try:
        with pytest.raises(ApiError) as e:
            await verify(token)
        assert e.value.code == "TOKEN_EXPIRED"
    finally:
        clock.reset()


async def test_service_tokens_are_cached_and_scoped() -> None:
    st = jwtutil.ServiceTokens("imageserver", KEY, ttl_s=60)
    t1, t2 = st.token_for("orchestrator"), st.token_for("orchestrator")
    assert t1 == t2
    c = await jwtutil.verify(t1, KEYS, audience="orchestrator-internal", issuer="imageserver")
    assert c["iss"] == "imageserver"
    with pytest.raises(ApiError):
        await jwtutil.verify(t1, KEYS, audience="imageserver-internal", issuer="imageserver")


def test_jwks_document_round_trip() -> None:
    doc = jwtutil.jwks_document({KEY.kid: KEY.public})
    assert doc["keys"][0]["kid"] == "k1" and doc["keys"][0]["crv"] == "Ed25519"
