"""Create, join, leave, end, kick, rotate invites, and session info."""

from __future__ import annotations

from collections.abc import Awaitable, Callable

from fastapi import APIRouter, Depends, Request, Response

from photon.common import clock, ids, ratelimit
from photon.common.errors import ApiError
from photon.common.httpapp import client_ip
from photon.common.pb import photon_pb2 as pb
from photon.common.proto import parse_body, proto_response
from photon.orchestrator import keys as K
from photon.orchestrator.svc import Svc


def check_sid(sid: str) -> str:
    if not ids.is_valid_sid(sid):
        raise ApiError("INVALID_REQUEST", "Invalid session id")
    return sid


async def new_invites(svc: Svc, sid: str) -> tuple[str, str]:
    s = svc.settings
    for _ in range(5):
        code = ids.new_join_code()
        if await svc.r.set(K.code(code), sid, nx=True, ex=s.code_ttl_s):
            break
    else:
        raise ApiError("BUSY", "Could not allocate a join code")
    token = ids.new_invite_token()
    await svc.r.set(K.invite(token), sid, ex=s.invite_ttl_s)
    return code, token


async def drop_invites(svc: Svc, code: str, token: str) -> None:
    names = [n for n in (code and K.code(code), token and K.invite(token)) if n]
    if names:
        await svc.r.delete(*names)


async def session_info(svc: Svc, sid: str, uid: str) -> pb.SessionInfo:
    _, host, expires_at, code, token, role, member_ids = await svc.fn.call_ro(
        "session_info", K.session_keys(sid), sid, clock.now_ms(), svc.ret, uid
    )
    invite_url = f"{svc.settings.public_base_url}/j/{token}"
    return pb.SessionInfo(
        session_id=sid,
        join_code=code,
        invite_url=invite_url,
        qr_payload=invite_url,
        expires_at_ms=int(expires_at or 0),
        role=role,
        member_ids=member_ids,
        host_id=host,
    )


def router(svc: Svc, user: Callable[[Request], Awaitable[str]]) -> APIRouter:
    r = APIRouter()
    s = svc.settings

    @r.post("/v1/sessions")
    async def create(request: Request, uid: str = Depends(user)) -> Response:
        body = await parse_body(request, pb.CreateSessionRequest)
        max_members = body.max_members or s.max_members
        if not 2 <= max_members <= s.max_members:
            raise ApiError("INVALID_REQUEST", f"max_members must be between 2 and {s.max_members}")
        await ratelimit.hit(svc.r, K.rl("create", uid), s.create_per_hour, 3600)
        sid = ids.new_sid()
        await svc.claim_pointer(uid, sid)
        code = token = ""
        try:
            code, token = await new_invites(svc, sid)
            (exp,) = await svc.fn.call(
                "create_session", K.session_keys(sid), sid, clock.now_ms(), svc.ret, uid,
                s.session_idle_ms, max_members, code, token,
            )
        except BaseException:
            await drop_invites(svc, code, token)
            await svc.release_pointer(uid, sid)
            raise
        await svc.r.zadd(K.SESSIONS_DUE, {sid: int(exp)})
        return proto_response(await session_info(svc, sid, uid))

    @r.post("/v1/sessions/join")
    async def join(request: Request, uid: str = Depends(user)) -> Response:
        body = await parse_body(request, pb.JoinRequest)
        which = body.WhichOneof("by")
        if which is None:
            raise ApiError("INVALID_REQUEST", "A code or an invite token is required")
        await ratelimit.hit(svc.r, K.rl("join:u", uid), s.join_per_minute, 60)
        await ratelimit.hit(svc.r, K.rl("join:ip", client_ip(request, s)), s.join_per_minute, 60)
        if which == "code":
            raw = await svc.r.get(K.code(ids.normalize_code(body.code)))
        else:
            raw = await svc.r.get(K.invite(body.invite_token))
        sid = str(raw) if raw else ""
        if not sid:
            raise ApiError("SESSION_NOT_FOUND", "This code or invite is not valid")
        claimed = await svc.claim_pointer(uid, sid)
        try:
            await svc.fn.call("join", K.session_keys(sid), sid, clock.now_ms(), svc.ret, uid, s.session_idle_ms)
        except BaseException:
            if claimed:
                await svc.release_pointer(uid, sid)  # the compensation for the cross-slot pointer
            raise
        return proto_response(await session_info(svc, sid, uid))

    @r.get("/v1/sessions/current")
    async def current(uid: str = Depends(user)) -> Response:
        """The caller's live session, so a client refused with ALREADY_IN_SESSION can show or leave it."""
        raw = await svc.r.get(K.user_session(uid))
        sid = str(raw) if raw else ""
        if not sid or not await svc.is_live_member(sid, uid):
            raise ApiError("SESSION_NOT_FOUND", "You are not in a session")
        return proto_response(await session_info(svc, sid, uid))

    @r.get("/v1/sessions/{sid}")
    async def info(sid: str, uid: str = Depends(user)) -> Response:
        return proto_response(await session_info(svc, check_sid(sid), uid))

    @r.post("/v1/sessions/leave")
    async def leave(request: Request, uid: str = Depends(user)) -> Response:
        body = await parse_body(request, pb.SessionRef)
        sid = check_sid(body.session_id)
        res = await svc.fn.call("leave", K.session_keys(sid), sid, clock.now_ms(), svc.ret, uid)
        await svc.release_pointer(uid, sid)
        if res[0] == "ENDED":
            await svc.after_end(sid, res[1:5])
        return Response(status_code=204)

    @r.post("/v1/sessions/{sid}/end")
    async def end(sid: str, uid: str = Depends(user)) -> Response:
        check_sid(sid)
        res = await svc.fn.call("end_session", K.session_keys(sid), sid, clock.now_ms(), svc.ret, uid, "host")
        if res[0] == "ENDED":
            await svc.after_end(sid, res[1:5])
        return Response(status_code=204)

    @r.post("/v1/sessions/{sid}/kick")
    async def kick(sid: str, request: Request, uid: str = Depends(user)) -> Response:
        check_sid(sid)
        body = await parse_body(request, pb.KickRequest)
        if body.session_id and body.session_id != sid:
            raise ApiError("INVALID_REQUEST", "Session id mismatch")
        if not body.member_id:
            raise ApiError("INVALID_REQUEST", "member_id is required")
        await svc.fn.call("kick", K.session_keys(sid), sid, clock.now_ms(), svc.ret, uid, body.member_id)
        await svc.release_pointer(body.member_id, sid)
        return Response(status_code=204)

    @r.post("/v1/sessions/{sid}/invites/rotate")
    async def rotate(sid: str, uid: str = Depends(user)) -> Response:
        check_sid(sid)
        code, token = await new_invites(svc, sid)
        try:
            old_code, old_token = await svc.fn.call(
                "set_invites", K.session_keys(sid), sid, clock.now_ms(), svc.ret, uid, code, token
            )
        except BaseException:
            await drop_invites(svc, code, token)
            raise
        await drop_invites(svc, old_code, old_token)
        return proto_response(await session_info(svc, sid, uid))

    return r
