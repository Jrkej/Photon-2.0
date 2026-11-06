"""PhotonClient: every PHOTON call, the error contract, and single-flight token refresh. It is the reference client:
the CLI, the end-to-end tests and the load test all use it."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import json
import os
import secrets
from dataclasses import dataclass
from pathlib import Path
from typing import Any, TypeVar, cast
from urllib.parse import parse_qs, urlencode, urlparse

import httpx
from google.protobuf.message import DecodeError, Message

from photon.client.keyring import Keyring
from photon.common import e2ee
from photon.common.pb import photon_pb2 as pb

PROTO = "application/x-protobuf"
DEV_REDIRECT = "photon://auth/cb"
M = TypeVar("M", bound=Message)


class ClientError(Exception):
    def __init__(self, status: int, error: pb.Error) -> None:
        self.status, self.error = status, error
        super().__init__(f"{status} {error.code}: {error.message}")

    @property
    def code(self) -> str:
        return self.error.code

    @property
    def retryable(self) -> bool:
        return self.error.retryable

    @property
    def retry_after(self) -> float:
        return float(self.error.retry_after_s or 1)


@dataclass
class Tokens:
    access_token: str = ""
    refresh_token: str = ""
    access_expires_at_ms: int = 0
    user_id: str = ""


def pkce_pair() -> tuple[str, str]:
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    return verifier, challenge


def to_error(resp: httpx.Response) -> ClientError:
    err = pb.Error()
    try:
        err.ParseFromString(resp.content)
    except DecodeError:
        err.code, err.message = "UNKNOWN", resp.text[:200]
    if not err.code:
        err.code = "UNKNOWN"
    return ClientError(resp.status_code, err)


class PhotonClient:
    def __init__(self, api_base: str, image_base: str, tokens: Tokens | None = None,
                 token_file: Path | None = None, http: httpx.AsyncClient | None = None,
                 keyring: Keyring | None = None) -> None:
        self.api_base, self.image_base = api_base.rstrip("/"), image_base.rstrip("/")
        self.tokens = tokens or Tokens()
        self.keys = keyring or Keyring()  # this install's identity and session keys (e2ee.md 3)
        self.token_file = token_file
        self.http = http or httpx.AsyncClient(timeout=30.0)
        self._refresh_lock = asyncio.Lock()
        if token_file and token_file.exists() and not self.tokens.access_token:
            self.tokens = Tokens(**json.loads(token_file.read_text()))

    async def aclose(self) -> None:
        await self.http.aclose()

    @property
    def uid(self) -> str:
        return self.tokens.user_id

    def _save(self) -> None:
        if self.token_file:
            self.token_file.parent.mkdir(parents=True, exist_ok=True)
            self.token_file.write_text(json.dumps(self.tokens.__dict__))
            self.token_file.chmod(0o600)

    # ------------------------------------------------------------------ transport

    async def _send(self, method: str, url: str, body: Message | bytes | None = None, *,
                    auth: str | None = "user", headers: dict[str, str] | None = None,
                    params: dict[str, Any] | None = None) -> httpx.Response:
        hdrs = dict(headers or {})
        content: bytes | None = None
        if isinstance(body, Message):
            content = body.SerializeToString()
            hdrs.setdefault("Content-Type", PROTO)
        elif body is not None:
            content = body
        # Every PHOTON call is idempotent except creating a session, so transport failures (a keep-alive connection
        # closed under us, a dropped network) are retried.
        retries = 0 if (method == "POST" and url.endswith("/v1/sessions")) else 3
        for attempt in range(2):
            if auth == "user":
                hdrs["Authorization"] = f"Bearer {self.tokens.access_token}"
            elif auth:
                hdrs["Authorization"] = f"Bearer {auth}"
            for transport_try in range(retries + 1):
                try:
                    resp = await self.http.request(method, url, content=content, headers=hdrs, params=params)
                    break
                except httpx.TransportError:
                    if transport_try == retries:
                        raise
                    await asyncio.sleep(0.2 * 2**transport_try)
            if resp.status_code == 401 and auth == "user" and attempt == 0:
                err = to_error(resp)
                if err.code == "TOKEN_EXPIRED":
                    await self.refresh(self.tokens.access_token)
                    continue
            return resp
        return resp

    async def call(self, method: str, path: str, body: Message | None, out: type[M] | None, *,
                   base: str | None = None, auth: str | None = "user",
                   params: dict[str, Any] | None = None) -> M | None:
        resp = await self._send(method, f"{base or self.api_base}{path}", body, auth=auth, params=params)
        if resp.status_code >= 400:
            raise to_error(resp)
        if out is None or resp.status_code == 204:
            return None
        msg = cast(M, out())
        msg.ParseFromString(resp.content)
        return msg

    # ------------------------------------------------------------------ auth

    async def login_dev(self, user: str) -> Tokens:
        verifier, challenge = pkce_pair()
        start = await self.call("POST", "/v1/auth/oauth/start", pb.OAuthStartRequest(
            provider="dev", redirect_uri=DEV_REDIRECT, code_challenge=challenge), pb.OAuthStartResponse, auth=None)
        assert start is not None
        # The dev sign-in page is on this same API: reach it the way this client reaches the API (the server names
        # its public address, which may be a tunnel this machine shouldn't go through: cloudflare.md).
        page = urlparse(start.authorization_url)
        url = f"{self.api_base}{page.path}?{page.query}" if page.path.startswith("/v1/auth/dev/") else \
            start.authorization_url
        sep = "&" if "?" in url else "?"
        resp = await self.http.get(f"{url}{sep}{urlencode({'user': user})}", follow_redirects=False)
        if resp.status_code != 302:
            raise to_error(resp)
        q = parse_qs(urlparse(resp.headers["location"]).query)
        pair = await self.call("POST", "/v1/auth/oauth/callback", pb.OAuthCallbackRequest(
            code=q["code"][0], state=q["state"][0], code_verifier=verifier, redirect_uri=DEV_REDIRECT),
            pb.TokenPair, auth=None)
        assert pair is not None
        self._take(pair)
        return self.tokens

    def _take(self, pair: pb.TokenPair) -> None:
        self.tokens = Tokens(pair.access_token, pair.refresh_token, pair.access_expires_at_ms, pair.user_id)
        self._save()

    async def refresh(self, seen_access: str | None = None) -> None:
        """Single-flight: concurrent callers wait for one refresh instead of each spending the refresh token."""
        async with self._refresh_lock:
            if seen_access is not None and self.tokens.access_token != seen_access:
                return  # someone else refreshed while we waited
            pair = await self.call("POST", "/v1/auth/refresh",
                                   pb.RefreshRequest(refresh_token=self.tokens.refresh_token), pb.TokenPair, auth=None)
            assert pair is not None
            self._take(pair)

    async def register_device(self, platform: str, push_token: str) -> None:
        await self.call("PUT", "/v1/devices/current", pb.DeviceRegistration(platform=platform,
                                                                          push_token=push_token), None)

    # ------------------------------------------------------------------ sessions

    async def create_session(self, max_members: int = 0) -> pb.SessionInfo:
        """Creates a session and its epoch-1 key, which stays on this device (others get it sealed, e2ee.md 5)."""
        bundle = e2ee.new_session_bundle()
        r = await self.call("POST", "/v1/sessions", pb.CreateSessionRequest(
            max_members=max_members, member_key=self.keys.member_key), pb.SessionInfo)
        assert r is not None
        self.keys.add(r.session_id, bundle)
        return r

    async def join(self, code: str | None = None, invite_token: str | None = None) -> pb.SessionInfo:
        """Joins with this install's public key. The session key arrives later, sealed by a member (sync_keys)."""
        mk = self.keys.member_key
        req = (pb.JoinRequest(code=code, member_key=mk) if code is not None
               else pb.JoinRequest(invite_token=invite_token or "", member_key=mk))
        r = await self.call("POST", "/v1/sessions/join", req, pb.SessionInfo)
        assert r is not None
        return r

    async def session(self, sid: str) -> pb.SessionInfo:
        r = await self.call("GET", f"/v1/sessions/{sid}", None, pb.SessionInfo)
        assert r is not None
        return r

    async def current_session(self) -> pb.SessionInfo:
        r = await self.call("GET", "/v1/sessions/current", None, pb.SessionInfo)
        assert r is not None
        return r

    async def leave(self, sid: str) -> None:
        await self.call("POST", "/v1/sessions/leave", pb.SessionRef(session_id=sid), None)
        self.keys.forget(sid)

    async def end(self, sid: str) -> None:
        await self.call("POST", f"/v1/sessions/{sid}/end", None, None)
        self.keys.forget(sid)

    async def kick(self, sid: str, member_id: str) -> None:
        await self.call("POST", f"/v1/sessions/{sid}/kick", pb.KickRequest(session_id=sid, member_id=member_id), None)

    async def rotate_invites(self, sid: str) -> pb.SessionInfo:
        r = await self.call("POST", f"/v1/sessions/{sid}/invites/rotate", None, pb.SessionInfo)
        assert r is not None
        return r

    async def snapshot(self, sid: str) -> pb.Snapshot:
        r = await self.call("GET", f"/v1/sessions/{sid}/snapshot", None, pb.Snapshot)
        assert r is not None
        return r

    async def events_since(self, sid: str, since: str, limit: int = 500) -> pb.EventBatch:
        r = await self.call("GET", f"/v1/sessions/{sid}/events", None, pb.EventBatch,
                            params={"since": since, "limit": limit})
        assert r is not None
        return r

    # ------------------------------------------------------------------ end-to-end keys (e2ee.md 5)

    async def key_state(self, sid: str) -> pb.KeyState:
        r = await self.call("GET", f"/v1/sessions/{sid}/keys", None, pb.KeyState)
        assert r is not None
        return r

    async def put_keys(self, sid: str, epoch: int, boxes: dict[str, bytes], rotate: bool = False) -> pb.KeyUploadResult:
        body = pb.KeyUpload(epoch=epoch, rotate=rotate, keys=[
            pb.SealedKey(member_id=m, epoch=epoch, box=box) for m, box in boxes.items()])
        r = await self.call("POST", f"/v1/sessions/{sid}/keys", body, pb.KeyUploadResult)
        assert r is not None
        return r

    def _seal_for(self, sid: str, bundle: e2ee.Bundle, members: list[pb.MemberKey]) -> dict[str, bytes]:
        boxes: dict[str, bytes] = {}
        for m in members:
            try:
                boxes[m.member_id] = e2ee.seal(bundle, m.member_key, sid, m.member_id)
            except e2ee.E2eeError:
                pass  # a malformed member key: nobody can seal for it
        return boxes

    async def sync_keys(self, sid: str) -> pb.KeyState:
        """Key maintenance (e2ee.md 5.3): open the boxes sealed for this install, then seal every epoch held for
        members who lack it. Run it after creating or joining, on MEMBER_JOINED, KEY_SHARED and KEY_ROTATED events,
        and on start."""
        state = await self.key_state(sid)
        for box in state.sealed:
            if not self.keys.has(sid, box.epoch):
                try:
                    self.keys.add(sid, e2ee.open_box(box.box, self.keys.identity, sid, self.uid, box.epoch))
                except e2ee.E2eeError:
                    pass  # sealed for another install's key, or tampered: ignore it
        held = self.keys.bundles(sid)
        if state.epoch in held:
            for epoch in sorted(held):
                lacking = [m for m in state.members if m.member_id != self.uid and m.key_epoch < epoch]
                boxes = self._seal_for(sid, held[epoch], lacking)
                if boxes:
                    await self.put_keys(sid, epoch, boxes)
        return state

    async def rotate_keys(self, sid: str) -> int:
        """Starts the next epoch after a member left or was kicked (e2ee.md 5.4); returns the epoch now current.
        The new key is sealed for every member, this one included, so a crash after the POST loses nothing."""
        for _ in range(3):
            state = await self.sync_keys(sid)
            if not state.rotation_required:
                return state.epoch
            base = self.keys.latest(sid)
            if base is None:
                raise ClientError(403, pb.Error(code="KEY_REQUIRED", message="No session key on this device yet"))
            nxt = e2ee.Bundle(state.epoch + 1, os.urandom(e2ee.KEY_LEN), base.tag_key)
            try:
                await self.put_keys(sid, nxt.epoch, self._seal_for(sid, nxt, list(state.members)), rotate=True)
            except ClientError as e:
                if e.code in ("EPOCH_TAKEN", "INVALID_REQUEST"):
                    continue  # another member rotated first, or someone joined meanwhile: look again
                raise
            self.keys.add(sid, nxt)
            return nxt.epoch
        return (await self.sync_keys(sid)).epoch

    async def current_bundle(self, sid: str) -> e2ee.Bundle | None:
        """The newest epoch held, fetching sealed keys first if none is; None while no member has shared one."""
        if self.keys.latest(sid) is None:
            await self.sync_keys(sid)
        return self.keys.latest(sid)

    # ------------------------------------------------------------------ photos

    async def announce(self, sid: str, sha256: bytes, size: int, mime: str, taken_at_ms: int,
                       photo_tag: bytes = b"", key_epoch: int = 0) -> pb.AnnounceResponse:
        r = await self.call("POST", "/v1/photos/announce", pb.AnnounceRequest(
            session_id=sid, sha256=sha256, size=size, mime=mime, taken_at_ms=taken_at_ms, photo_tag=photo_tag,
            key_epoch=key_epoch), pb.AnnounceResponse)
        assert r is not None
        return r

    async def recall(self, sid: str, sha_hex: str) -> None:
        await self.call("POST", f"/v1/sessions/{sid}/photos/{sha_hex}/recall", None, None)

    async def report(self, sid: str, sha_hex: str, reason: str) -> None:
        await self.call("POST", f"/v1/sessions/{sid}/photos/{sha_hex}/report", pb.ReportPhotoRequest(reason=reason),
                        None)

    # ------------------------------------------------------------------ image server

    async def upload_status(self, upload_url: str, token: str) -> pb.UploadStatus:
        r = await self.call("GET", "", None, pb.UploadStatus, base=upload_url, auth=token)
        assert r is not None
        return r

    async def put_chunk(self, upload_url: str, token: str, n: int, data: bytes, crc_b64: str) -> None:
        resp = await self._send("PUT", f"{upload_url}/chunks/{n}", data, auth=token,
                                headers={"X-Chunk-CRC32C": crc_b64, "Content-Type": "application/octet-stream"})
        if resp.status_code >= 400:
            raise to_error(resp)

    async def complete(self, upload_url: str, token: str, chunk_count: int) -> tuple[int, pb.CompleteResponse | None]:
        resp = await self._send("POST", f"{upload_url}/complete", pb.CompleteRequest(chunk_count=chunk_count),
                                auth=token)
        if resp.status_code >= 400:
            raise to_error(resp)
        if resp.status_code == 202:
            return 202, None
        out = pb.CompleteResponse()
        out.ParseFromString(resp.content)
        return 200, out

    async def download(self, sid: str, sha_hex: str, size: str = "full",
                       byte_range: str | None = None) -> httpx.Response:
        headers = {"Range": byte_range} if byte_range else None
        resp = await self._send("GET", f"{self.image_base}/v1/sessions/{sid}/photos/{sha_hex}",
                                headers=headers, params={"size": size})
        if resp.status_code >= 400 and resp.status_code != 416:
            raise to_error(resp)
        return resp

    async def download_photo(self, sid: str, sha_hex: str, size: str = "full",
                             photo_tag: bytes | None = None) -> e2ee.Photo:
        """Downloads and decrypts a photo (or, for thumb and preview, the file's prefix: e2ee.md 6.3). With the
        event's `photo_tag`, checks the original against it. Raises e2ee.E2eeError on a file that won't decrypt."""
        data = (await self.download(sid, sha_hex, size)).content
        if size == "full" and hashlib.sha256(data).hexdigest() != sha_hex:
            raise e2ee.E2eeError("the file does not match its hash")
        header = e2ee.parse_header(data)
        if not self.keys.has(sid, header.epoch):
            await self.sync_keys(sid)
        photo = e2ee.decrypt_photo(data, self.keys.epoch_keys(sid))
        if size == "preview" and photo.preview is None:
            return await self.download_photo(sid, sha_hex, "full", photo_tag)  # a small original is its own preview
        if photo_tag and photo.original is not None:
            tag_key = self.keys.bundles(sid)[header.epoch].tag_key
            if not hmac.compare_digest(e2ee.photo_tag(tag_key, photo.original), photo_tag):
                raise e2ee.E2eeError("the original does not match its photo tag")
        return photo
