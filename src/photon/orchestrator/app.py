"""Orchestrator: sessions, members, photo status and event fan-out. Stateless; all state is in Redis."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from photon import db
from photon.common import jwtutil
from photon.common.config import Settings
from photon.common.httpapp import add_readyz, create_base_app, service_dependency, user_dependency
from photon.common.redisutil import (
    ensure_library,
    library_code,
    library_version,
    make_blocking_client,
    make_client,
)
from photon.orchestrator import jobs
from photon.orchestrator.push import Pusher, make_sender
from photon.orchestrator.routes import events, internal, photos, sessions
from photon.orchestrator.routes import keys as key_routes
from photon.orchestrator.svc import OrchestratorKeys, Svc


def load_keys(settings: Settings) -> OrchestratorKeys:
    kd = settings.keys_dir
    auth: jwtutil.KeySource = (
        jwtutil.JwksClient(settings.auth_jwks_url) if settings.auth_jwks_url
        else jwtutil.StaticKeys(jwtutil.load_public_keys(kd, "auth-access"))
    )
    return OrchestratorKeys(
        upload=jwtutil.load_signing_key(kd, "orch-upload"),
        service=jwtutil.load_signing_key(kd, "svc-orchestrator"),
        auth=auth,
        imageserver=jwtutil.StaticKeys(jwtutil.load_public_keys(kd, "svc-imageserver")),
    )


def create_app(settings: Settings | None = None, keys: OrchestratorKeys | None = None) -> FastAPI:
    settings = settings or Settings()
    keys = keys or load_keys(settings)
    svc = Svc(
        settings,
        keys,
        make_client(settings.redis_url, settings.redis_pool),
        make_blocking_client(settings.redis_url, settings.redis_blocking_pool),
        db.make_engine(settings.database_url) if settings.database_url else None,
    )
    svc.pusher = Pusher(svc, make_sender(settings))

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        await ensure_library(svc.r)
        tasks = jobs.start(svc) if settings.run_jobs else []
        try:
            yield
        finally:
            for t in tasks:
                t.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            await svc.close()
            await svc.r.aclose()
            await svc.blocking.aclose()
            if svc.db is not None:
                await svc.db.dispose()

    app = create_base_app("photon-orchestrator", settings, lifespan)
    app.state.svc = svc
    user = user_dependency(keys.auth)
    app.include_router(sessions.router(svc, user))
    app.include_router(photos.router(svc, user))
    app.include_router(events.router(svc, user))
    app.include_router(key_routes.router(svc, user))
    app.include_router(internal.router(svc, service_dependency(keys.imageserver, "orchestrator", "imageserver")))
    want = library_version(library_code())

    async def ready() -> None:
        have = await svc.r.fcall_ro("version", 0)
        if have != want:
            raise RuntimeError(f"function library {have}, expected {want}")

    add_readyz(app, ready)
    return app
