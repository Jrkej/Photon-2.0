"""One-command local stack, no Docker: Redis (scripts/dev-deps.sh), embedded Postgres, migrations, keys, and the
services. API (Auth + orchestrator behind one base URL) on :8080, image server on :8081.

    python -m photon.dev.run [--host 0.0.0.0] [--data .devdata]
    python -m photon.dev.run --api-url https://api.example.com --image-url https://img.example.com --dev-login local

Development only: plain HTTP, the dev login provider, jobs on short intervals. To reach it from outside this
machine through Cloudflare Tunnel, give the public URLs and restrict the dev login: see cloudflare.md.
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import signal
import socket
import subprocess
import time
from pathlib import Path

import uvicorn

from photon import migrate
from photon.common.config import Settings

ROOT = Path(__file__).resolve().parents[3]


def wait_port(host: str, port: int, timeout: float = 10.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        with socket.socket() as s:
            if s.connect_ex((host, port)) == 0:
                return
        time.sleep(0.05)
    raise RuntimeError(f"nothing listening on {host}:{port}")


def start_redis(data: Path, port: int) -> subprocess.Popen[bytes]:
    binary = ROOT / ".devtools" / "redis" / "bin" / "redis-server"
    if not binary.exists():
        subprocess.run([str(ROOT / "scripts" / "dev-deps.sh")], check=True)
    (data / "redis").mkdir(parents=True, exist_ok=True)
    proc = subprocess.Popen([str(binary), "--port", str(port), "--bind", "127.0.0.1", "--dir", str(data / "redis"),
                             "--appendonly", "yes", "--appendfsync", "everysec"], stdout=subprocess.DEVNULL)
    wait_port("127.0.0.1", port)
    return proc


def ensure_keys(keys_dir: Path) -> None:
    if not (keys_dir / "auth-access.pem").exists():
        subprocess.run(["python3", str(ROOT / "tools" / "gen_keys.py"), str(keys_dir)], check=True)


async def serve(settings: Settings, host: str) -> None:
    from photon.auth.app import create_app as auth_app
    from photon.dev.gateway import Gateway
    from photon.imageserver.app import create_app as img_app
    from photon.orchestrator.app import create_app as orch_app

    api = Gateway(auth_app(settings), orch_app(settings))
    servers = [
        uvicorn.Server(uvicorn.Config(api, host=host, port=8080, log_level="info", lifespan="on",
                                      timeout_keep_alive=75)),
        uvicorn.Server(uvicorn.Config(img_app(settings), host=host, port=8081, log_level="info", lifespan="on",
                                      timeout_keep_alive=75)),
    ]
    for s in servers:
        s.capture_signals = contextlib.nullcontext  # type: ignore[method-assign,assignment]  # we handle signals
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop.set)
    tasks = [asyncio.create_task(s.serve()) for s in servers]
    await stop.wait()
    for s in servers:
        s.should_exit = True
    await asyncio.gather(*tasks, return_exceptions=True)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1", help="0.0.0.0 to reach it from an emulator or phone")
    ap.add_argument("--data", default=str(ROOT / ".devdata"))
    ap.add_argument("--public-host", default=None, help="host name phones use (default: --host)")
    ap.add_argument("--api-url", default=None,
                    help="public API URL, e.g. a tunnel's https://... (default: http://<public-host>:8080)")
    ap.add_argument("--image-url", default=None,
                    help="public image server URL (default: http://<public-host>:8081)")
    ap.add_argument("--dev-login", choices=["on", "local", "off"], default="on",
                    help="username sign-in: on, local (only from this machine, not through a tunnel), or off")
    args = ap.parse_args()
    data = Path(args.data).resolve()
    data.mkdir(parents=True, exist_ok=True)
    redis = start_redis(data, 6390)
    import pgserver

    pg = pgserver.get_server(str(data / "pg"), cleanup_mode="stop")  # type: ignore[attr-defined]
    db_url = pg.get_uri().replace("postgresql://", "postgresql+asyncpg://", 1)
    migrate.upgrade(db_url)
    ensure_keys(data / "keys")
    public = args.public_host or ("127.0.0.1" if args.host == "0.0.0.0" else args.host)
    api_url = (args.api_url or f"http://{public}:8080").rstrip("/")
    image_url = (args.image_url or f"http://{public}:8081").rstrip("/")
    settings = Settings(
        env="dev", redis_url="redis://127.0.0.1:6390/0", database_url=db_url, storage_root=data / "storage",
        keys_dir=data / "keys", api_base_url=api_url, image_base_url=image_url, dev_login=args.dev_login,
        orchestrator_internal_url="http://127.0.0.1:8080", imageserver_internal_url="http://127.0.0.1:8081",
        sweeper_interval_s=15, session_sweeper_interval_s=15, recount_interval_s=60, imageserver_sweep_interval_s=15,
        trusted_proxies=[],
    )
    print(f"PHOTON dev stack: API {api_url}  images {image_url}  dev login {args.dev_login}  data {data}")
    try:
        asyncio.run(serve(settings, args.host))
    finally:
        redis.terminate()
        pg.cleanup()


if __name__ == "__main__":
    main()
