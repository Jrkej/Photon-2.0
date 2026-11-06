"""Announce, recall and report."""

from __future__ import annotations

import json
from collections.abc import Awaitable, Callable

from fastapi import APIRouter, Depends, Request, Response

from photon import db
from photon.common import clock, e2ee, ids, metrics, ratelimit
from photon.common.errors import ApiError
from photon.common.pb import photon_pb2 as pb
from photon.common.proto import parse_body, proto_response, sha_from_hex, sha_hex
from photon.orchestrator import keys as K
from photon.orchestrator import upload_tokens
from photon.orchestrator.routes.sessions import check_sid
from photon.orchestrator.svc import Svc, log

DAY_MS = 24 * 3600 * 1000


def router(svc: Svc, user: Callable[[Request], Awaitable[str]]) -> APIRouter:
    r = APIRouter()
    s = svc.settings

    @r.post("/v1/photos/announce")
    async def announce(request: Request, uid: str = Depends(user)) -> Response:
        body = await parse_body(request, pb.AnnounceRequest)
        sha = sha_hex(body.sha256)
        sid = check_sid(body.session_id)
        now = clock.now_ms()
        if body.size <= 0 or body.taken_at_ms <= 0 or body.taken_at_ms > now + DAY_MS:
            raise ApiError("INVALID_REQUEST", "size and taken_at_ms must be plausible")
        if body.size > s.max_photo_bytes:
            raise ApiError("TOO_LARGE", f"Photos are limited to {s.max_photo_bytes} bytes")
        if body.mime != e2ee.MIME:
            raise ApiError("UNSUPPORTED_FORMAT", f"Photos must be end-to-end encrypted ({e2ee.MIME}, see e2ee.md)")
        if len(body.photo_tag) != 32 or body.key_epoch < 1:
            raise ApiError("INVALID_REQUEST", "photo_tag (32 bytes) and key_epoch are required")
        await ratelimit.hit(svc.r, K.rl("announce", uid), s.announce_per_minute, 60)
        try:
            res = await svc.fn.call(
                "announce", K.session_keys(sid), sid, now, svc.ret, uid, sha, body.size, body.mime,
                body.taken_at_ms, ids.new_upload_id(), s.session_quota_bytes, s.max_live_uploads_user,
                s.max_live_uploads_session, s.max_strikes, s.lease_ms, s.takeover_ms, body.photo_tag.hex(),
                body.key_epoch,
            )
        except ApiError as e:
            if e.code in ("TOO_MANY_UPLOADS", "QUOTA_EXCEEDED"):
                metrics.quota_rejections_total.labels(e.code).inc()
            raise
        result = res[0]
        metrics.announce_total.labels(result).inc()
        svc.spawn(svc.touch(sid))
        if result == "UPLOAD":
            upload_id, takeover = res[1], int(res[2])
            if takeover:
                metrics.takeovers_total.inc()
            token = upload_tokens.mint(
                svc.keys.upload, s.upload_token_ttl_s, sid=sid, sha=sha, size=body.size, mime=body.mime,
                taken_at_ms=body.taken_at_ms, uploader=uid, upload_id=upload_id, key_epoch=body.key_epoch,
            )
            return proto_response(pb.AnnounceResponse(
                result=pb.AnnounceResponse.UPLOAD,
                upload_id=upload_id,
                upload_url=f"{s.image_base_url}/v1/uploads/{upload_id}",
                upload_token=token,
                chunk_size_hint=s.chunk_size_hint,
            ))
        if result == "ALREADY_UPLOADED":
            return proto_response(pb.AnnounceResponse(result=pb.AnnounceResponse.ALREADY_UPLOADED))
        return proto_response(pb.AnnounceResponse(result=pb.AnnounceResponse.IN_PROGRESS))

    @r.post("/v1/sessions/{sid}/photos/{sha}/recall")
    async def recall(sid: str, sha: str, uid: str = Depends(user)) -> Response:
        check_sid(sid)
        sha_from_hex(sha)
        now = clock.now_ms()
        res = await svc.fn.call("recall", K.session_keys(sid), sid, now, svc.ret, uid, sha)
        if res[0] == "RECALLED":
            await svc.queue_deletes(sid, [sha], now)
            svc.spawn(svc.delete_now(sid, [sha]))
            if svc.pusher:
                svc.spawn(svc.pusher.notify(sid, "recalled"))
        return Response(status_code=204)

    @r.post("/v1/sessions/{sid}/photos/{sha}/report")
    async def report(sid: str, sha: str, request: Request, uid: str = Depends(user)) -> Response:
        check_sid(sid)
        sha_from_hex(sha)
        body = await parse_body(request, pb.ReportPhotoRequest)
        if len(body.reason) > 500:
            raise ApiError("INVALID_REQUEST", "Reason is limited to 500 characters")
        await ratelimit.hit(svc.r, K.rl("report", uid), 10, 60)
        await svc.fn.call_ro("session_info", K.session_keys(sid), sid, clock.now_ms(), svc.ret, uid)
        raw = await svc.r.hget(K.photos(sid), sha)
        if raw is None:
            raise ApiError("PHOTO_NOT_FOUND")
        uploader = json.loads(raw).get("uploader", "")
        metrics.photo_reports_total.inc()
        if svc.db is not None:
            async with svc.db.begin() as conn:
                await conn.execute(db.photo_reports.insert().values(
                    session_id=sid, sha256=sha, reporter_id=uid, uploader_id=uploader, reason=body.reason
                ))
        log.warning("photo_reported", sid=sid, sha=sha[:12], reporter=uid, uploader=uploader)
        return Response(status_code=204)

    return r
