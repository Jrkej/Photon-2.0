"""Image server: owns the bytes. Verifies each upload's SHA-256 before it exists for anyone else, reports to the
orchestrator, serves downloads to members, and deletes files at expiry on its own."""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass

from fastapi import FastAPI

from photon.common import jwtutil, logs
from photon.common.config import Settings
from photon.common.httpapp import add_readyz, create_base_app, service_dependency, user_dependency
from photon.common.redisutil import make_client
from photon.imageserver.access import Access
from photon.imageserver.decode import Decoder
from photon.imageserver.outbox import Outbox
from photon.imageserver.pipeline import Pipeline
from photon.imageserver.routes import router
from photon.imageserver.store import Store
from photon.imageserver.sweeper import Sweeper

log = logs.get("imageserver")


@dataclass
class ImageServerKeys:
    service: jwtutil.SigningKey  # signs reports to the orchestrator
    upload: jwtutil.KeySource  # verifies upload tokens (the orchestrator's key)
    auth: jwtutil.KeySource  # verifies access tokens for downloads
    orchestrator: jwtutil.KeySource  # verifies the orchestrator's service tokens


def load_keys(settings: Settings) -> ImageServerKeys:
    kd = settings.keys_dir
    return ImageServerKeys(
        service=jwtutil.load_signing_key(kd, "svc-imageserver"),
        upload=jwtutil.JwksClient(settings.upload_jwks_url) if settings.upload_jwks_url
        else jwtutil.StaticKeys(jwtutil.load_public_keys(kd, "orch-upload")),
        auth=jwtutil.JwksClient(settings.auth_jwks_url) if settings.auth_jwks_url
        else jwtutil.StaticKeys(jwtutil.load_public_keys(kd, "auth-access")),
        orchestrator=jwtutil.StaticKeys(jwtutil.load_public_keys(kd, "svc-orchestrator")),
    )


def create_app(settings: Settings | None = None, keys: ImageServerKeys | None = None) -> FastAPI:
    settings = settings or Settings()
    keys = keys or load_keys(settings)
    store = Store(settings.storage_root)
    redis = make_client(settings.redis_url, settings.redis_pool)
    access = Access(redis, settings)
    decoder = Decoder(settings)
    outbox = Outbox(settings, store, jwtutil.ServiceTokens("imageserver", keys.service, settings.service_token_ttl_s))
    pipeline = Pipeline(settings, store, access, decoder, outbox)
    sweeper = Sweeper(store, access, pipeline)

    async def sweep_loop() -> None:
        while True:
            try:
                await sweeper.sweep()
            except asyncio.CancelledError:
                raise
            except Exception as e:
                log.exception("sweep_failed", error=repr(e))
            await asyncio.sleep(settings.imageserver_sweep_interval_s)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        pipeline.scan_usage()
        outbox.start()  # resends every pending report first: crash recovery
        task = asyncio.create_task(sweep_loop()) if settings.run_jobs else None
        try:
            yield
        finally:
            if task:
                task.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await task
            await outbox.stop()
            await redis.aclose()

    app = create_base_app("photon-imageserver", settings, lifespan)
    app.state.pipeline, app.state.store, app.state.outbox, app.state.sweeper = pipeline, store, outbox, sweeper
    app.include_router(router(
        store, access, pipeline, decoder, keys.upload, user_dependency(keys.auth),
        service_dependency(keys.orchestrator, "imageserver", "orchestrator"),
    ))

    async def ready() -> None:
        await redis.ping()
        if not store.tmp.exists():
            raise RuntimeError("storage root missing")

    add_readyz(app, ready)
    return app
