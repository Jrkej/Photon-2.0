"""One base URL for Auth and the orchestrator, as the load balancer provides in production (dev and tests)."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import AsyncExitStack, asynccontextmanager

from fastapi import FastAPI
from starlette.types import Receive, Scope, Send

AUTH_PREFIXES = ("/v1/auth/", "/v1/devices/", "/.well-known/")


class Gateway:
    def __init__(self, auth: FastAPI, orchestrator: FastAPI) -> None:
        self.auth, self.orchestrator = auth, orchestrator

    @asynccontextmanager
    async def lifespan(self) -> AsyncIterator[None]:
        async with AsyncExitStack() as stack:
            await stack.enter_async_context(self.auth.router.lifespan_context(self.auth))
            await stack.enter_async_context(self.orchestrator.router.lifespan_context(self.orchestrator))
            yield

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "lifespan":
            message = await receive()
            assert message["type"] == "lifespan.startup"
            async with self.lifespan():
                await send({"type": "lifespan.startup.complete"})
                message = await receive()
            await send({"type": "lifespan.shutdown.complete"})
            return
        path = scope.get("path", "")
        target = self.auth if path.startswith(AUTH_PREFIXES) else self.orchestrator
        await target(scope, receive, send)
