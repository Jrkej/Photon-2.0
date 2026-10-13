"""What every PHOTON FastAPI app shares: request ids, the Error body for every failure, health and metrics."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.responses import PlainTextResponse, Response

from photon.common import jwtutil, logs
from photon.common.config import Settings
from photon.common.errors import ApiError
from photon.common.metrics import metrics_endpoint
from photon.common.pb import photon_pb2 as pb
from photon.common.proto import proto_response

log = logs.get("http")


def create_base_app(title: str, settings: Settings, lifespan: Any = None) -> FastAPI:
    logs.configure()
    app = FastAPI(title=title, docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.settings = settings
    app.add_middleware(logs.RequestIdMiddleware)

    @app.exception_handler(ApiError)
    async def api_error(_: Request, exc: ApiError) -> Response:
        return proto_response(exc.to_pb(), exc.status, exc.headers())

    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, exc: RequestValidationError) -> Response:
        err = ApiError("INVALID_REQUEST", "Invalid parameters")
        return proto_response(err.to_pb(), err.status)

    @app.exception_handler(StarletteHTTPException)
    async def http_error(_: Request, exc: StarletteHTTPException) -> Response:
        body = pb.Error(code="INVALID_REQUEST", message=str(exc.detail), retryable=False)
        return proto_response(body, exc.status_code)

    @app.exception_handler(Exception)
    async def unexpected(_: Request, exc: Exception) -> Response:
        log.exception("unhandled_error", error=repr(exc))
        err = ApiError("BUSY", "Temporary server error")
        return proto_response(err.to_pb(), err.status, err.headers())

    async def healthz(_: Request) -> Response:
        return PlainTextResponse("ok")

    app.add_route("/healthz", healthz)
    app.add_route("/metrics", metrics_endpoint)
    return app


def add_readyz(app: FastAPI, check: Callable[[], Awaitable[None]]) -> None:
    async def readyz(_: Request) -> Response:
        try:
            await check()
        except Exception as e:
            return PlainTextResponse(f"not ready: {e!r}", status_code=503)
        return PlainTextResponse("ready")

    app.add_route("/readyz", readyz)


def client_ip(request: Request, settings: Settings) -> str:
    """X-Forwarded-For is trusted only when the direct peer is a configured proxy."""
    peer = request.client.host if request.client else "unknown"
    if peer not in settings.trusted_proxies:
        return peer
    hops = [h.strip() for h in request.headers.get("x-forwarded-for", "").split(",") if h.strip()]
    for hop in reversed(hops):
        if hop not in settings.trusted_proxies:
            return hop
    return peer


def user_dependency(keys: jwtutil.KeySource) -> Callable[[Request], Awaitable[str]]:
    """FastAPI dependency: the access JWT's user id (aud=photon, iss=photon-auth)."""

    async def current_user(request: Request) -> str:
        token = jwtutil.bearer(request.headers.get("authorization"))
        claims = await jwtutil.verify(token, keys, audience="photon", issuer="photon-auth")
        sub = claims.get("sub")
        if not isinstance(sub, str) or not sub:
            raise ApiError("UNAUTHENTICATED", "Token has no subject")
        return sub

    return current_user


def service_dependency(keys: jwtutil.KeySource, me: str, allowed: str) -> Callable[[Request], Awaitable[str]]:
    """FastAPI dependency for /internal routes: a service token from `allowed`, addressed to `me`."""

    async def require_service(request: Request) -> str:
        token = jwtutil.bearer(request.headers.get("authorization"))
        claims = await jwtutil.verify(token, keys, audience=f"{me}-internal", issuer=allowed)
        return str(claims["iss"])

    return require_service
