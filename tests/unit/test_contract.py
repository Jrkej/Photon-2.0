"""0.4: every row of the design's error contract; ids; key slots (finding 5)."""

from __future__ import annotations

import binascii

import pytest

from photon.auth.tokens import family_key, refresh_key
from photon.common import ids
from photon.common.errors import CONTRACT, ApiError
from photon.orchestrator import keys as K

DESIGN_TABLE = {
    400: (["INVALID_REQUEST"], False),
    401: (["TOKEN_EXPIRED", "UNAUTHENTICATED"], None),
    403: (["NOT_A_MEMBER", "FORBIDDEN"], False),
    404: (["UNKNOWN_UPLOAD", "PHOTO_NOT_FOUND", "SESSION_NOT_FOUND"], False),
    409: (["ALREADY_IN_SESSION", "UPLOAD_SUPERSEDED", "UPLOAD_FINALIZED"], False),
    410: (["SESSION_ENDED", "PHOTO_EXPIRED", "PHOTO_RECALLED", "UPLOAD_EXPIRED", "RESYNC_REQUIRED"], False),
    413: (["TOO_LARGE"], False),
    415: (["UNSUPPORTED_FORMAT"], False),
    422: (["HASH_MISMATCH", "INVALID_IMAGE"], False),
    429: (["RATE_LIMITED", "TOO_MANY_UPLOADS", "QUOTA_EXCEEDED"], True),
    503: (["BUSY", "PROCESSING_FAILED"], True),
}


@pytest.mark.parametrize("status", sorted(DESIGN_TABLE))
def test_contract_rows(status: int) -> None:
    codes, retryable = DESIGN_TABLE[status]
    for code in codes:
        err = ApiError(code)
        assert err.status == status
        if retryable is not None:
            assert err.retryable is retryable
        pb = err.to_pb()
        assert pb.code == code and pb.retryable == err.retryable


def test_every_contract_code_is_in_the_design_table() -> None:
    assert set(CONTRACT) == {c for codes, _ in DESIGN_TABLE.values() for c in codes}


def test_token_expired_is_retryable_after_refresh() -> None:
    assert ApiError("TOKEN_EXPIRED").retryable and not ApiError("UNAUTHENTICATED").retryable


def test_retry_after_header_and_missing_chunks() -> None:
    err = ApiError("RATE_LIMITED", retry_after_s=7)
    assert err.headers() == {"Retry-After": "7"} and err.to_pb().retry_after_s == 7
    assert ApiError("BUSY").headers() == {"Retry-After": "5"}
    assert ApiError("INVALID_REQUEST").headers() == {}
    assert list(ApiError("INVALID_REQUEST", missing_chunks=[1, 3]).to_pb().missing_chunks) == [1, 3]


def test_function_errors_map_onto_contract_codes() -> None:
    err = ApiError("SESSION_FULL")
    assert (err.code, err.status, err.message) == ("FORBIDDEN", 403, "Session is full")
    with pytest.raises(ValueError):
        ApiError("NOT_A_REAL_CODE")


def test_ids() -> None:
    sid = ids.new_sid()
    assert len(sid) == 22 and "{" not in sid and "}" not in sid and ids.is_valid_sid(sid)
    code = ids.new_join_code()
    assert len(code) == 8 and set(code) <= set(ids.CROCKFORD)
    assert ids.normalize_code("abcd-ef1o ") == "ABCDEF10"
    assert ids.normalize_code("il") == "11"
    assert not ids.is_valid_sid("../../etc") and not ids.is_valid_sid("a{b}c" * 5)


def crc16(data: bytes) -> int:
    return binascii.crc_hqx(data, 0)


def slot(key: str) -> int:
    start = key.find("{")
    if start != -1:
        end = key.find("}", start + 1)
        if end > start + 1:
            key = key[start + 1:end]
    return crc16(key.encode()) % 16384


def test_every_function_call_stays_in_one_slot() -> None:
    """Finding 5: a session function's KEYS, and the lease keys it builds, hash to the session's slot."""
    for _ in range(50):
        sid = ids.new_sid()
        names = [*K.session_keys(sid), K.lease(sid, ids.new_upload_id()), K.online(sid)]
        assert len({slot(n) for n in names}) == 1
        fam = ids.new_invite_token()[:22]
        assert slot(refresh_key(fam, "a" * 64)) == slot(refresh_key(fam, "b" * 64)) == slot(family_key(fam))


async def test_redis_pool_waits_instead_of_failing(redis_url: str) -> None:
    """More concurrent commands than connections: callers wait for a free one rather than fail."""
    import asyncio

    from photon.common.redisutil import make_client

    r = make_client(redis_url, max_connections=3)
    try:
        results = await asyncio.gather(*(r.ping() for _ in range(200)))
        assert all(results)
    finally:
        await r.aclose()
