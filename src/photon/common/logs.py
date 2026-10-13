"""Structured JSON logs with a request id that follows a request across services."""

from __future__ import annotations

import logging
import secrets
import sys
from typing import Any

import structlog
from starlette.types import ASGIApp, Message, Receive, Scope, Send

_configured = False


def configure(level: str = "INFO") -> None:
    global _configured
    if _configured:
        return
    _configured = True
    logging.basicConfig(format="%(message)s", stream=sys.stdout, level=level)
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            structlog.processors.format_exc_info,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.getLevelName(level)),
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=True,
    )


def get(name: str) -> Any:
    return structlog.get_logger(name)


class RequestIdMiddleware:
    """Accepts X-Request-Id or creates one, binds it to the log context and echoes it back."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] not in ("http", "websocket"):
            await self.app(scope, receive, send)
            return
        rid = ""
        for k, v in scope.get("headers", []):
            if k == b"x-request-id":
                rid = v.decode("latin-1")[:64]
                break
        rid = rid or secrets.token_hex(8)
        scope.setdefault("state", {})["request_id"] = rid
        structlog.contextvars.clear_contextvars()
        structlog.contextvars.bind_contextvars(request_id=rid, path=scope.get("path"))

        async def send_with_id(message: Message) -> None:
            if message["type"] == "http.response.start":
                message.setdefault("headers", []).append((b"x-request-id", rid.encode()))
            await send(message)

        await self.app(scope, receive, send_with_id)
