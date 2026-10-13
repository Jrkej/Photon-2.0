"""Hash, announce, resume, chunk (parallel, CRC32C, retries) and complete: one photo, end to end."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import math
import random
from dataclasses import dataclass

import google_crc32c

from photon.client.api import ClientError, PhotonClient
from photon.common.pb import photon_pb2 as pb


def crc32c_b64(data: bytes) -> str:
    return base64.b64encode(google_crc32c.value(data).to_bytes(4, "big")).decode()


@dataclass
class UploadResult:
    outcome: str  # STORED, ALREADY_UPLOADED, IN_PROGRESS, or an error code (HASH_MISMATCH, SESSION_ENDED, ...)
    sha256: str
    upload_id: str = ""
    response: pb.CompleteResponse | None = None


async def _backoff(attempt: int, base: float = 0.2, cap: float = 5.0) -> None:
    await asyncio.sleep(random.uniform(0, min(cap, base * 2**attempt)))


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
    """Uploads `data`. `announced_sha` lets a test claim another hash than the bytes have."""
    sha = announced_sha or hashlib.sha256(data).digest()
    sha_hex = sha.hex()
    try:
        ann = await client.announce(sid, sha, len(data), mime, taken_at_ms)
    except ClientError as e:
        return UploadResult(e.code, sha_hex)
    if ann.result == pb.AnnounceResponse.ALREADY_UPLOADED:
        return UploadResult("ALREADY_UPLOADED", sha_hex)
    if ann.result == pb.AnnounceResponse.IN_PROGRESS:
        return UploadResult("IN_PROGRESS", sha_hex)
    url, token = ann.upload_url, ann.upload_token
    size = chunk_size or ann.chunk_size_hint or 1024 * 1024
    count = max(1, math.ceil(len(data) / size))
    chunks = [data[i * size:(i + 1) * size] for i in range(count)]
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
                return UploadResult(e.code, sha_hex, ann.upload_id)
            if code == 200:
                return UploadResult("STORED", sha_hex, ann.upload_id, resp)
            if loop.time() > deadline:
                return UploadResult("TIMEOUT", sha_hex, ann.upload_id)
            await asyncio.sleep(0.5)
    except ClientError as e:
        return UploadResult(e.code, sha_hex, ann.upload_id)
