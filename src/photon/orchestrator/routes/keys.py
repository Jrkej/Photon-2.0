"""End-to-end key relay (e2ee.md 5): the orchestrator stores members' public keys and sealed key bundles, and tells
members when one is waiting. It can open none of them."""

from __future__ import annotations

from collections.abc import Awaitable, Callable

from fastapi import APIRouter, Depends, Request, Response

from photon.common import clock, e2ee, ratelimit
from photon.common.errors import ApiError
from photon.common.pb import photon_pb2 as pb
from photon.common.proto import parse_body, proto_response
from photon.orchestrator import keys as K
from photon.orchestrator.routes.sessions import check_sid
from photon.orchestrator.svc import Svc

MAX_BOXES = 64  # members (at most max_members) for one epoch per call


def router(svc: Svc, user: Callable[[Request], Awaitable[str]]) -> APIRouter:
    r = APIRouter()

    @r.get("/v1/sessions/{sid}/keys")
    async def key_state(sid: str, uid: str = Depends(user)) -> Response:
        """Members' public keys and newest held epochs, plus the caller's own sealed keys."""
        check_sid(sid)
        epoch, rotate, members, mine = await svc.fn.call_ro(
            "key_state", K.session_keys(sid), sid, clock.now_ms(), svc.ret, uid
        )
        state = pb.KeyState(epoch=int(epoch), rotation_required=bool(int(rotate)))
        for member_id, member_key, key_epoch in members:
            state.members.append(pb.MemberKey(member_id=member_id, member_key=bytes.fromhex(member_key),
                                              key_epoch=int(key_epoch)))
        for box_epoch, from_member, box in mine:
            state.sealed.append(pb.SealedKey(member_id=uid, epoch=int(box_epoch), box=bytes.fromhex(box),
                                             from_member_id=from_member))
        return proto_response(state)

    @r.post("/v1/sessions/{sid}/keys")
    async def put_keys(sid: str, request: Request, uid: str = Depends(user)) -> Response:
        """Shares an epoch's sealed key with members who lack it, or starts the next epoch (rotate)."""
        check_sid(sid)
        body = await parse_body(request, pb.KeyUpload)
        # Sharing needs at least one box. Rotation may carry none: the last member left rotates for itself alone.
        if body.epoch < 1 or len(body.keys) > MAX_BOXES or not (body.keys or body.rotate):
            raise ApiError("INVALID_REQUEST", f"An epoch and up to {MAX_BOXES} sealed keys are required")
        seen: set[str] = set()
        flat: list[str] = []
        for k in body.keys:
            if not k.member_id or k.member_id in seen or len(k.box) != e2ee.BOX_LEN:
                raise ApiError("INVALID_REQUEST",
                               f"Each sealed key needs a distinct member and a {e2ee.BOX_LEN}-byte box")
            if k.epoch not in (0, body.epoch):
                raise ApiError("INVALID_REQUEST", "Every sealed key in one call is for the same epoch")
            seen.add(k.member_id)
            flat += [k.member_id, k.box.hex()]
        await ratelimit.hit(svc.r, K.rl("keys", uid), 60, 60)
        epoch, stored = await svc.fn.call(
            "put_keys", K.session_keys(sid), sid, clock.now_ms(), svc.ret, uid, body.epoch, body.rotate,
            len(body.keys), *flat,
        )
        return proto_response(pb.KeyUploadResult(epoch=int(epoch), stored=int(stored)))

    return r
