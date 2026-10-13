"""Push as a hint: members without a live socket get a data-only wake-up that never carries photo data.
Delivery may be dropped, delayed or merged; the client always runs the same catch-up."""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any, Protocol

from sqlalchemy import delete, select

from photon import db
from photon.common import clock, logs, metrics
from photon.common.config import Settings
from photon.orchestrator import keys as K
from photon.orchestrator.svc import Svc

log = logs.get("push")


class Sender(Protocol):
    async def send(self, platform: str, token: str, data: dict[str, str]) -> bool:
        """True when delivered to the push service; False when the token is invalid and should be deleted."""
        ...


class LogSender:
    async def send(self, platform: str, token: str, data: dict[str, str]) -> bool:
        log.info("push_hint", platform=platform, token=token[:8], **data)
        metrics.push_sent_total.labels("logged").inc()
        return True


class FcmSender:
    """Android devices through FCM with firebase-admin (optional dependency: pip install photon[push])."""

    def __init__(self, credentials_file: str) -> None:
        import firebase_admin
        from firebase_admin import credentials, messaging

        self._messaging = messaging
        self._app = firebase_admin.initialize_app(credentials.Certificate(credentials_file), name="photon")

    async def send(self, platform: str, token: str, data: dict[str, str]) -> bool:
        m = self._messaging
        msg = m.Message(data=data, token=token, android=m.AndroidConfig(priority="high"))
        try:
            await asyncio.to_thread(m.send, msg, app=self._app)
        except (m.UnregisteredError, ValueError):
            metrics.push_sent_total.labels("invalid_token").inc()
            return False
        except Exception as e:
            log.warning("push_failed", error=repr(e))
            metrics.push_sent_total.labels("error").inc()
            return True
        metrics.push_sent_total.labels("sent").inc()
        return True


class ApnsSender:
    """iOS devices: an APNs background push (content-available) with token auth, through aioapns
    (optional dependency: pip install photon[push]). iOS may delay or drop it; the app never relies on it."""

    INVALID = frozenset({"BadDeviceToken", "Unregistered", "DeviceTokenNotForTopic"})

    def __init__(self, key_file: str, key_id: str, team_id: str, topic: str, sandbox: bool) -> None:
        from aioapns import APNs, NotificationRequest, PushType

        self._request, self._background = NotificationRequest, PushType.BACKGROUND
        self._apns: Any = APNs(key=Path(key_file).read_text(), key_id=key_id, team_id=team_id, topic=topic,
                               use_sandbox=sandbox, err_func=self._quiet)

    @staticmethod
    async def _quiet(request: Any, result: Any) -> None:
        """Failures are counted by send(); aioapns would otherwise log each one as an error."""

    async def send(self, platform: str, token: str, data: dict[str, str]) -> bool:
        request = self._request(device_token=token, message={"aps": {"content-available": 1}, **data},
                                push_type=self._background, priority=5)
        try:
            result = await self._apns.send_notification(request)
        except Exception as e:
            log.warning("apns_failed", error=repr(e))
            metrics.push_sent_total.labels("error").inc()
            return True
        if result.is_successful:
            metrics.push_sent_total.labels("sent").inc()
            return True
        if result.description in self.INVALID:
            metrics.push_sent_total.labels("invalid_token").inc()
            return False
        metrics.push_sent_total.labels("error").inc()
        return True


class PlatformSender:
    """Routes each device to its platform's push service."""

    def __init__(self, android: Sender, ios: Sender) -> None:
        self.android, self.ios = android, ios

    async def send(self, platform: str, token: str, data: dict[str, str]) -> bool:
        return await (self.ios if platform == "ios" else self.android).send(platform, token, data)


def make_sender(settings: Settings) -> Sender:
    android: Sender = FcmSender(settings.fcm_credentials_file) if settings.fcm_credentials_file else LogSender()
    ios: Sender = (ApnsSender(settings.apns_key_file, settings.apns_key_id, settings.apns_team_id,
                              settings.apns_topic, settings.apns_sandbox)
                   if settings.apns_key_file else LogSender())
    return PlatformSender(android, ios)


class Pusher:
    def __init__(self, svc: Svc, sender: Sender) -> None:
        self.svc, self.sender = svc, sender

    async def notify(self, sid: str, kind: str, member_ids: list[str] | None = None) -> None:
        svc, s = self.svc, self.svc.settings
        try:
            now = clock.now_ms()
            online = {str(k): int(v) for k, v in (await svc.r.hgetall(K.online(sid))).items()}
            ids = member_ids if member_ids is not None else [str(u) for u in await svc.r.hkeys(K.members(sid))]
            stale_ms = s.presence_stale_s * 1000
            for uid in ids:
                if now - int(online.get(uid, 0)) <= stale_ms:
                    continue  # a live socket delivers the event itself
                if not await svc.r.set(K.push(uid), "1", nx=True, ex=s.push_coalesce_s):
                    continue  # one push per user per window
                for platform, token in await self.tokens(uid):
                    if not await self.sender.send(platform, token, {"sid": sid, "kind": kind}):
                        await self.forget(token)
        except Exception as e:  # best effort by design
            log.warning("push_notify_failed", sid=sid, error=repr(e))

    async def tokens(self, uid: str) -> list[tuple[str, str]]:
        if self.svc.db is None:
            return []
        async with self.svc.db.connect() as conn:
            rows = await conn.execute(select(db.devices.c.platform, db.devices.c.push_token)
                                      .where(db.devices.c.user_id == uid))
            return [(r.platform, r.push_token) for r in rows]

    async def forget(self, token: str) -> None:
        if self.svc.db is None:
            return
        async with self.svc.db.begin() as conn:
            await conn.execute(delete(db.devices).where(db.devices.c.push_token == token))
