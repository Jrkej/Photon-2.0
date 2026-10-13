"""8.5: push goes to each device's own service; an invalid token is reported so the device row is deleted."""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

from photon.orchestrator.push import ApnsSender, PlatformSender


class Recorder:
    def __init__(self) -> None:
        self.sent: list[tuple[str, str]] = []

    async def send(self, platform: str, token: str, data: dict[str, str]) -> bool:
        self.sent.append((platform, token))
        return True


async def test_routes_by_platform() -> None:
    android, ios = Recorder(), Recorder()
    sender = PlatformSender(android, ios)
    await sender.send("android", "a1", {})
    await sender.send("ios", "i1", {})
    assert android.sent == [("android", "a1")] and ios.sent == [("ios", "i1")]


class FakeApns:
    def __init__(self, result: Any) -> None:
        self.result = result
        self.requests: list[Any] = []

    async def send_notification(self, request: Any) -> Any:
        self.requests.append(request)
        return self.result


def apns_with(result: Any) -> tuple[ApnsSender, FakeApns]:
    from aioapns import NotificationRequest, PushType

    sender = ApnsSender.__new__(ApnsSender)
    sender._request, sender._background = NotificationRequest, PushType.BACKGROUND
    fake = FakeApns(result)
    sender._apns = fake
    return sender, fake


async def test_apns_background_payload_and_results() -> None:
    ok, fake = apns_with(SimpleNamespace(is_successful=True, description=None))
    assert await ok.send("ios", "tok", {"sid": "s1", "kind": "stored"})
    req = fake.requests[0]
    assert req.message == {"aps": {"content-available": 1}, "sid": "s1", "kind": "stored"}
    assert req.push_type.value == "background" and req.priority == 5
    gone, _ = apns_with(SimpleNamespace(is_successful=False, description="Unregistered"))
    assert not await gone.send("ios", "tok", {})
    busy, _ = apns_with(SimpleNamespace(is_successful=False, description="TooManyRequests"))
    assert await busy.send("ios", "tok", {})
