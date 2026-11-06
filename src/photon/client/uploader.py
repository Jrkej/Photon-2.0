"""One photo, end to end: thumbnail, encrypt (e2ee.md 7), hash, announce, resume, chunk (parallel, CRC32C, retries)
and complete. The server only ever receives the encrypted file."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import math
import random
from dataclasses import dataclass

import google_crc32c

from photon.client import images
from photon.client.api import ClientError, PhotonClient
from photon.common import e2ee
from photon.common.pb import photon_pb2 as pb


def crc32c_b64(data: bytes) -> str:
    return base64.b64encode(google_crc32c.value(data).to_bytes(4, "big")).decode()


@dataclass
class UploadResult:
    outcome: str  # STORED, ALREADY_UPLOADED, IN_PROGRESS, or an error code (KEY_REQUIRED, SESSION_ENDED, ...)
    sha256: str  # of the encrypted file: the photo's id everywhere
    upload_id: str = ""
    response: pb.CompleteResponse | None = None
    photo_tag: bytes = b""
    key_epoch: int = 0


async def _backoff(attempt: int, base: float = 0.2, cap: float = 5.0) -> None:
    await asyncio.sleep(random.uniform(0, min(cap, base * 2**attempt)))


def build_file(bundle: e2ee.Bundle, original: bytes, prepared: images.Prepared, taken_at_ms: int) -> bytes:
    return e2ee.encrypt_photo(bundle, original=original, mime=prepared.mime, width=prepared.width,
                              height=prepared.height, taken_at_ms=taken_at_ms, thumb=prepared.thumb,
                              preview=prepared.preview)


async def upload_bytes(
    client: PhotonClient,
    sid: str,
    data: bytes,
    mime: str,
    taken_at_ms: int,
    *,
    chunk_size: int | None = None,
    parallel: int = 3,
    timeout_s: float = 120.0,
    announced_sha: bytes | None = None,
) -> UploadResult:
    """Encrypts the photo `data` for the session and uploads it. Holds the session key first (key maintenance),
    rotates it when the server says a member has left, and rebuilds the file when the epoch moved on."""
    if mime and mime not in e2ee.INNER_MIMES:
        return UploadResult("UNSUPPORTED_FORMAT", "")
    try:
        prepared = images.prepare(data, mime or None)
    except images.ImageError:
        return UploadResult("INVALID_IMAGE", "")
    result = UploadResult("KEY_REQUIRED", "")
    try:
        for _ in range(4):
            bundle = await client.current_bundle(sid)
            if bundle is None:
                return UploadResult("KEY_REQUIRED", "")  # no member has shared the key yet: wait for KEY_SHARED
            tag = e2ee.photo_tag(bundle.tag_key, data)
            file = build_file(bundle, data, prepared, taken_at_ms)
            result = await upload_file(client, sid, file, taken_at_ms, tag, bundle.epoch, chunk_size=chunk_size,
                                       parallel=parallel, timeout_s=timeout_s, announced_sha=announced_sha)
            if result.outcome == "KEY_ROTATION_REQUIRED":
                await client.rotate_keys(sid)
            elif result.outcome == "KEY_EPOCH_STALE":
                await client.sync_keys(sid)
            else:
                return result
    except ClientError as e:
        return UploadResult(e.code, result.sha256)
    return result


async def upload_file(
    client: PhotonClient,
    sid: str,
    file: bytes,
    taken_at_ms: int,
    photo_tag: bytes,
    key_epoch: int,
    *,
    mime: str = e2ee.MIME,
    chunk_size: int | None = None,
    parallel: int = 3,
    timeout_s: float = 120.0,
    announced_sha: bytes | None = None,
) -> UploadResult:
    """Uploads an already-built file as it is. `announced_sha` lets a test claim another hash than the bytes have,
    and `mime` another type than the encrypted one."""
    sha = announced_sha or hashlib.sha256(file).digest()
    sha_hex = sha.hex()

    def result(outcome: str, upload_id: str = "", resp: pb.CompleteResponse | None = None) -> UploadResult:
        return UploadResult(outcome, sha_hex, upload_id, resp, photo_tag, key_epoch)

    try:
        ann = await client.announce(sid, sha, len(file), mime, taken_at_ms, photo_tag, key_epoch)
    except ClientError as e:
        return result(e.code)
    if ann.result == pb.AnnounceResponse.ALREADY_UPLOADED:
        return result("ALREADY_UPLOADED")
    if ann.result == pb.AnnounceResponse.IN_PROGRESS:
        return result("IN_PROGRESS")
    url, token = ann.upload_url, ann.upload_token
    size = chunk_size or ann.chunk_size_hint or 1024 * 1024
    count = max(1, math.ceil(len(file) / size))
    chunks = [file[i * size:(i + 1) * size] for i in range(count)]
    sem = asyncio.Semaphore(parallel)

    async def send(i: int) -> None:
        async with sem:
            for attempt in range(3):
                try:
                    await client.put_chunk(url, token, i, chunks[i], crc32c_b64(chunks[i]))
                    return
                except ClientError as e:
                    if not e.retryable or attempt == 2:
                        raise
                    await _backoff(attempt)

    async def send_all(indexes: list[int]) -> None:
        await asyncio.gather(*(send(i) for i in indexes))

    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout_s
    try:
        status = await client.upload_status(url, token)
        if not status.server_has_file:
            have = set(status.received_chunks)
            await send_all([i for i in range(count) if i not in have])
        while True:
            try:
                code, resp = await client.complete(url, token, count)
            except ClientError as e:
                if e.code == "INVALID_REQUEST" and list(e.error.missing_chunks):
                    await send_all([i for i in e.error.missing_chunks if i < count])
                    continue
                if e.retryable and loop.time() < deadline:
                    await asyncio.sleep(min(e.retry_after, 2.0))
                    continue
                return result(e.code, ann.upload_id)
            if code == 200:
                return result("STORED", ann.upload_id, resp)
            if loop.time() > deadline:
                return result("TIMEOUT", ann.upload_id)
            await asyncio.sleep(0.5)
    except ClientError as e:
        return result(e.code, ann.upload_id)
