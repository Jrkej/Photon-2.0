"""Test harness: a real Redis (built by scripts/dev-deps.sh), an embedded Postgres (pgserver), in-memory keys, and
the three services under uvicorn on random ports, all in the test event loop."""

from __future__ import annotations

import asyncio
import contextlib
import io
import shutil
import socket
import subprocess
import time
from collections.abc import AsyncIterator, Awaitable, Callable, Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pytest
import pytest_asyncio
import uvicorn
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from PIL import Image
from redis.asyncio import Redis

from photon import migrate
from photon.auth import app as auth_app
from photon.client.api import PhotonClient
from photon.common import clock, jwtutil
from photon.common.config import Settings
from photon.common.redisutil import ensure_library
from photon.dev.gateway import Gateway
from photon.imageserver import app as img_app
from photon.orchestrator import app as orch_app
from photon.orchestrator.svc import Svc

ROOT = Path(__file__).resolve().parents[1]
REDIS_SERVER = ROOT / ".devtools" / "redis" / "bin" / "redis-server"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


@pytest.fixture(scope="session")
def redis_url(tmp_path_factory: pytest.TempPathFactory) -> Iterator[str]:
    if not REDIS_SERVER.exists():
        pytest.exit("Redis is not built: run scripts/dev-deps.sh", returncode=2)
    port = free_port()
    d = tmp_path_factory.mktemp("redis")
    proc = subprocess.Popen(
        [str(REDIS_SERVER), "--port", str(port), "--bind", "127.0.0.1", "--save", "", "--appendonly", "no",
         "--dir", str(d), "--maxclients", "4000"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    for _ in range(100):
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", port)) == 0:
                break
        time.sleep(0.05)
    yield f"redis://127.0.0.1:{port}/0"
    proc.terminate()
    proc.wait(5)


@pytest.fixture(scope="session")
def database_url(tmp_path_factory: pytest.TempPathFactory) -> Iterator[str]:
    import pgserver

    srv = pgserver.get_server(str(tmp_path_factory.mktemp("pg")), cleanup_mode="stop")
    url = srv.get_uri().replace("postgresql://", "postgresql+asyncpg://", 1)
    migrate.upgrade(url)
    yield url
    srv.cleanup()


@dataclass
class TestKeys:
    auth: jwtutil.SigningKey
    upload: jwtutil.SigningKey
    svc_orch: jwtutil.SigningKey
    svc_img: jwtutil.SigningKey

    def pub(self, key: jwtutil.SigningKey) -> jwtutil.StaticKeys:
        return jwtutil.StaticKeys({key.kid: key.public})


@pytest.fixture(scope="session")
def keys() -> TestKeys:
    def k(name: str) -> jwtutil.SigningKey:
        return jwtutil.SigningKey(f"{name}-test", Ed25519PrivateKey.generate())

    return TestKeys(k("auth-access"), k("orch-upload"), k("svc-orchestrator"), k("svc-imageserver"))


@pytest.fixture(scope="session")
def settings(redis_url: str, database_url: str, tmp_path_factory: pytest.TempPathFactory) -> Settings:
    api_port, img_port = free_port(), free_port()
    api, img = f"http://127.0.0.1:{api_port}", f"http://127.0.0.1:{img_port}"
    return Settings(
        env="test",
        redis_url=redis_url,
        database_url=database_url,
        storage_root=tmp_path_factory.mktemp("store"),
        api_base_url=api,
        image_base_url=img,
        orchestrator_internal_url=api,
        imageserver_internal_url=img,
        public_base_url="https://photon.test",
        run_jobs=False,
        decode_concurrency=4,
        ws_block_ms=300,
        complete_report_wait_s=3.0,
        announce_per_minute=10_000,
        join_per_minute=10_000,
        create_per_hour=10_000,
        oauth_per_minute=10_000,
        refresh_per_minute=10_000,
    )


@dataclass
class Stack:
    settings: Settings
    keys: TestKeys
    svc: Svc
    img: Any  # the image server FastAPI app
    api_url: str
    image_url: str
    redis: Redis


async def _serve(app: Any, url: str) -> tuple[uvicorn.Server, asyncio.Task[None]]:
    port = int(url.rsplit(":", 1)[1])
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", lifespan="on",
                                           ws_ping_interval=None))
    server.capture_signals = contextlib.nullcontext  # type: ignore[method-assign]  # pytest owns the signals
    task = asyncio.create_task(server.serve())
    for _ in range(500):
        if server.started:
            break
        await asyncio.sleep(0.01)
    return server, task


@pytest_asyncio.fixture(scope="session", loop_scope="session")
async def stack(settings: Settings, keys: TestKeys) -> AsyncIterator[Stack]:
    from photon.imageserver.app import ImageServerKeys
    from photon.orchestrator.svc import OrchestratorKeys

    auth = auth_app.create_app(settings, auth_app.AuthKeys(keys.auth, {keys.auth.kid: keys.auth.public}))
    orch = orch_app.create_app(settings, OrchestratorKeys(
        upload=keys.upload, service=keys.svc_orch, auth=keys.pub(keys.auth), imageserver=keys.pub(keys.svc_img)))
    img = img_app.create_app(settings, ImageServerKeys(
        service=keys.svc_img, upload=keys.pub(keys.upload), auth=keys.pub(keys.auth),
        orchestrator=keys.pub(keys.svc_orch)))
    servers = [await _serve(Gateway(auth, orch), settings.api_base_url),
               await _serve(img, settings.image_base_url)]
    redis = Redis.from_url(settings.redis_url, decode_responses=True)
    yield Stack(settings, keys, orch.state.svc, img, settings.api_base_url, settings.image_base_url, redis)
    for server, _ in servers:
        server.should_exit = True
    await asyncio.gather(*(t for _, t in servers), return_exceptions=True)
    await redis.aclose()


DEFAULTS: dict[str, Any] = {}


@pytest_asyncio.fixture(loop_scope="session")
async def env(stack: Stack) -> AsyncIterator[Stack]:
    """A clean world for each test: empty Redis (library kept), empty storage, real time, default settings."""
    await stack.redis.flushdb()
    await ensure_library(stack.redis)
    root = stack.settings.storage_root
    for name in ("tmp", "photos", "outbox", "trash"):
        shutil.rmtree(root / name, ignore_errors=True)
        (root / name).mkdir(parents=True)
    stack.img.state.pipeline.scan_usage()
    stack.svc._touched.clear()
    clock.reset()
    saved = stack.settings.model_dump()
    yield stack
    for k, v in saved.items():
        setattr(stack.settings, k, v)
    clock.reset()


Login = Callable[[str], Awaitable[PhotonClient]]


@pytest_asyncio.fixture(loop_scope="session")
async def login(env: Stack) -> AsyncIterator[Login]:
    clients: list[PhotonClient] = []

    async def make(user: str) -> PhotonClient:
        c = PhotonClient(env.api_url, env.image_url)
        await c.login_dev(user)
        clients.append(c)
        return c

    yield make
    for c in clients:
        await c.aclose()


def jpeg(seed: int = 0, size: tuple[int, int] = (64, 48)) -> bytes:
    """A small valid JPEG whose bytes depend on `seed`."""
    img = Image.new("RGB", size, ((seed * 37) % 256, (seed * 91) % 256, (seed * 53) % 256))
    img.putpixel((seed % size[0], (seed // size[0]) % size[1]), (255 - (seed % 256), 0, 0))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=90)
    return buf.getvalue()


def png(size: tuple[int, int] = (32, 32)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", size, (10, 200, 30)).save(buf, "PNG")
    return buf.getvalue()


async def eventually(check: Callable[[], Awaitable[bool]], timeout: float = 5.0, interval: float = 0.05) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if await check():
            return
        await asyncio.sleep(interval)
    raise AssertionError("condition not met in time")
