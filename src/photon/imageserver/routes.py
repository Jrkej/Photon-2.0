"""Image server HTTP: chunked upload, status, complete, downloads, and the orchestrator's delete."""

from __future__ import annotations

import asyncio
import os
import re
from collections.abc import Awaitable, Callable, Iterator
from pathlib import Path

from fastapi import APIRouter, Depends, Query, Request, Response
from fastapi.responses import StreamingResponse

from photon.common import clock, jwtutil, metrics
from photon.common.errors import ApiError
from photon.common.pb import photon_pb2 as pb
from photon.common.proto import parse_body, proto_response
from photon.imageserver.access import Access, upload_claims
from photon.imageserver.decode import Decoder
from photon.imageserver.pipeline import Pipeline
from photon.imageserver.store import Store, check_sha, check_sid, read_json

RANGE = re.compile(r"^bytes=(\d*)-(\d*)$")
NO_STORE = {"Cache-Control": "private, no-store"}
_preview_locks: dict[str, asyncio.Lock] = {}


def file_iter(path: Path, start: int, length: int) -> Iterator[bytes]:
    with open(path, "rb") as f:
        f.seek(start)
        left = length
        while left > 0:
            block = f.read(min(256 * 1024, left))
            if not block:
                return
            left -= len(block)
            yield block


def send_file(path: Path, media_type: str, etag: str, range_header: str | None) -> Response:
    size = path.stat().st_size
    headers = {**NO_STORE, "ETag": f'"{etag}"', "Accept-Ranges": "bytes"}
    if not range_header:
        return StreamingResponse(file_iter(path, 0, size), media_type=media_type,
                                 headers={**headers, "Content-Length": str(size)})
    m = RANGE.match(range_header.strip())
    start: int | None = None
    if m and (m.group(1) or m.group(2)):
        if m.group(1):
            start = int(m.group(1))
            end = int(m.group(2)) if m.group(2) else size - 1
        else:
            suffix = int(m.group(2))
            start, end = max(0, size - suffix), size - 1
        end = min(end, size - 1)
        if start > end or start >= size:
            start = None
    if start is None:
        return Response(status_code=416, headers={**headers, "Content-Range": f"bytes */{size}"})
    length = end - start + 1
    return StreamingResponse(
        file_iter(path, start, length), status_code=206, media_type=media_type,
        headers={**headers, "Content-Length": str(length), "Content-Range": f"bytes {start}-{end}/{size}"},
    )


def router(
    store: Store,
    access: Access,
    pipeline: Pipeline,
    decoder: Decoder,
    upload_keys: jwtutil.KeySource,
    user: Callable[[Request], Awaitable[str]],
    orchestrator: Callable[[Request], Awaitable[str]],
) -> APIRouter:
    r = APIRouter()
    settings = pipeline.settings

    @r.put("/v1/uploads/{upload_id}/chunks/{n}")
    async def put_chunk(upload_id: str, n: int, request: Request) -> Response:
        claims = await upload_claims(request, upload_keys, upload_id)
        await pipeline.put_chunk(claims, n, request)
        return Response(status_code=204)

    @r.get("/v1/uploads/{upload_id}")
    async def status(upload_id: str, request: Request) -> Response:
        claims = await upload_claims(request, upload_keys, upload_id)
        return proto_response(await pipeline.status(claims))

    @r.post("/v1/uploads/{upload_id}/complete")
    async def complete(upload_id: str, request: Request) -> Response:
        claims = await upload_claims(request, upload_keys, upload_id)
        body = await parse_body(request, pb.CompleteRequest)
        code, resp = await pipeline.complete(claims, body.chunk_count)
        if resp is None:
            return Response(status_code=202, headers={"Retry-After": "2"})
        return proto_response(resp, code)

    @r.get("/v1/sessions/{sid}/photos/{sha}")
    async def download(
        sid: str, sha: str, request: Request,
        size: str = Query("full", pattern="^(thumb|preview|full)$"),
        uid: str = Depends(user),
    ) -> Response:
        check_sid(sid)
        check_sha(sha)
        status, member, pstatus, upload_id, _, expires_at = await access.download_check(sid, uid, sha)
        now = clock.now_ms()
        if not member:
            raise ApiError("PHOTO_NOT_FOUND")  # non-members cannot learn that a photo exists
        if status != "ACTIVE":
            raise ApiError("SESSION_ENDED")
        if pstatus in ("", "UPLOADING"):
            raise ApiError("PHOTO_NOT_FOUND")
        if pstatus == "RECALLED":
            raise ApiError("PHOTO_RECALLED")
        if pstatus == "EXPIRED" or now >= expires_at:
            raise ApiError("PHOTO_EXPIRED")
        d = store.photo_dir(sid, sha)
        if not (d / "meta.json").exists() and upload_id:
            d = store.staged_dir(upload_id)  # between the orchestrator's 200 and promotion
        meta = read_json(d / "meta.json")
        if meta is None:
            raise ApiError("PHOTO_NOT_FOUND")
        if now >= int(meta["expires_at_ms"]):
            raise ApiError("PHOTO_EXPIRED")  # the image server's own guard, whatever Redis says
        etag = f"{sha}-{size}"
        if size == "thumb":
            return send_file(d / "thumb.webp", "image/webp", etag, None)
        if size == "preview":
            preview = d / "preview.webp"
            if not preview.exists():
                lock = _preview_locks.setdefault(f"{sid}:{sha}", asyncio.Lock())
                async with lock:
                    if not preview.exists():
                        tmp = d / f"preview.{os.getpid()}.webp"
                        res = await decoder.run("preview", d / "original", tmp, str(meta["mime"]),
                                                settings.preview_px)
                        if not res.ok:
                            tmp.unlink(missing_ok=True)
                            raise ApiError("PROCESSING_FAILED", "Preview unavailable; use the thumbnail")
                        os.replace(tmp, preview)
                _preview_locks.pop(f"{sid}:{sha}", None)
            return send_file(preview, "image/webp", etag, None)
        return send_file(d / "original", str(meta["mime"]), etag, request.headers.get("range"))

    @r.delete("/internal/v1/photos/{sid}/{sha}")
    async def delete(sid: str, sha: str, _: str = Depends(orchestrator)) -> Response:
        d = store.photo_dir(check_sid(sid), check_sha(sha))
        meta = read_json(d / "meta.json")
        if await asyncio.to_thread(store.to_trash, d) and meta:
            lag_s = (clock.now_ms() - int(meta["expires_at_ms"])) / 1000
            if lag_s >= 0:  # recall and session end delete early; that is not lag
                metrics.deletion_lag_seconds.observe(lag_s)
        sid_dir = d.parent
        if sid_dir.exists() and not any(sid_dir.iterdir()):
            sid_dir.rmdir()
        return Response(status_code=204)

    return r
