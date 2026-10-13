"""Protobuf bodies over HTTP."""

from __future__ import annotations

from typing import TypeVar, cast

from fastapi import Request, Response
from google.protobuf.message import DecodeError, Message

from photon.common.errors import ApiError

PROTO = "application/x-protobuf"
M = TypeVar("M", bound=Message)


async def parse_body(request: Request, cls: type[M], max_bytes: int = 64 * 1024) -> M:
    content_type = request.headers.get("content-type", "").split(";")[0].strip().lower()
    if content_type != PROTO:
        raise ApiError("INVALID_REQUEST", f"Content-Type must be {PROTO}")
    length = request.headers.get("content-length")
    if length is not None and int(length) > max_bytes:
        raise ApiError("TOO_LARGE", "Request body too large")
    body = await request.body()
    if len(body) > max_bytes:
        raise ApiError("TOO_LARGE", "Request body too large")
    msg = cast(M, cls())
    try:
        msg.ParseFromString(body)
    except DecodeError as e:
        raise ApiError("INVALID_REQUEST", "Body is not a valid protobuf message") from e
    return msg


def proto_response(msg: Message, status: int = 200, headers: dict[str, str] | None = None) -> Response:
    return Response(content=msg.SerializeToString(), status_code=status, media_type=PROTO, headers=headers)


def sha_hex(raw: bytes) -> str:
    if len(raw) != 32:
        raise ApiError("INVALID_REQUEST", "sha256 must be 32 bytes")
    return raw.hex()


def sha_from_hex(value: str) -> bytes:
    try:
        raw = bytes.fromhex(value)
    except ValueError as e:
        raise ApiError("INVALID_REQUEST", "Invalid hash") from e
    if len(raw) != 32 or value != value.lower():
        raise ApiError("INVALID_REQUEST", "Invalid hash")
    return raw
