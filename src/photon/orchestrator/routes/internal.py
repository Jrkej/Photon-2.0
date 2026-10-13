"""Reports from the image server, fenced by upload_id, and the upload-token JWKS."""

from __future__ import annotations

from collections.abc import Awaitable, Callable

from fastapi import APIRouter, Depends, Request, Response
from fastapi.responses import JSONResponse

from photon.common import clock, jwtutil, metrics
from photon.common.errors import ApiError
from photon.common.pb import photon_pb2 as pb
from photon.common.proto import parse_body, proto_response, sha_hex
from photon.orchestrator import keys as K
from photon.orchestrator.routes.sessions import check_sid
from photon.orchestrator.svc import Svc, log


def router(svc: Svc, imageserver: Callable[[Request], Awaitable[str]]) -> APIRouter:
    r = APIRouter()

    @r.get("/internal/v1/jwks.json")
    async def jwks() -> Response:
        return JSONResponse(jwtutil.jwks_document({svc.keys.upload.kid: svc.keys.upload.public}))

    @r.post("/internal/v1/photos/stored")
    async def stored(request: Request, _: str = Depends(imageserver)) -> Response:
        msg = await parse_body(request, pb.PhotoStored)
        sid, sha = check_sid(msg.session_id), sha_hex(msg.sha256)
        now = clock.now_ms()
        if abs(msg.stored_at_ms - now) > 2000:
            metrics.clock_skew_reports_total.inc()
            log.warning("clock_skew", sid=sid, skew_ms=msg.stored_at_ms - now)
        try:
            result, event_id, reserved_at = await svc.fn.call(
                "report_stored", K.session_keys(sid), sid, now, svc.ret, sha, msg.upload_id,
                msg.stored_at_ms, msg.expires_at_ms,
            )
        except ApiError as e:
            metrics.reports_total.labels("stored", e.code.lower()).inc()
            raise
        metrics.reports_total.labels("stored", result.lower()).inc()
        # Also on NOOP: repairs a crash between the function and this call (ZADD LT is idempotent).
        await svc.queue_deletes(sid, [sha], msg.expires_at_ms)
        if result == "APPLIED":
            metrics.upload_duration_seconds.observe(max(0, now - int(float(reserved_at))) / 1000)
            if svc.pusher:
                svc.spawn(svc.pusher.notify(sid, "stored"))
            return proto_response(pb.ReportAck(result=pb.ReportAck.APPLIED, event_id=event_id))
        return proto_response(pb.ReportAck(result=pb.ReportAck.NOOP))

    @r.post("/internal/v1/photos/rejected")
    async def rejected(request: Request, _: str = Depends(imageserver)) -> Response:
        msg = await parse_body(request, pb.PhotoRejected)
        sid, sha = check_sid(msg.session_id), sha_hex(msg.sha256)
        try:
            await svc.fn.call("report_rejected", K.session_keys(sid), sid, clock.now_ms(), svc.ret, sha,
                              msg.upload_id, msg.reason)
        except ApiError as e:
            metrics.reports_total.labels("rejected", e.code.lower()).inc()
            raise
        metrics.reports_total.labels("rejected", "applied").inc()
        log.info("upload_rejected", sid=sid, sha=sha[:12], reason=msg.reason)
        return proto_response(pb.ReportAck(result=pb.ReportAck.APPLIED))

    return r
