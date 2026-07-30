"""HTTP middleware: request IDs, access logging and security headers."""

from __future__ import annotations

import time
import uuid
from collections.abc import Awaitable, Callable

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response
from starlette.types import ASGIApp

from app.core.logging import get_logger, request_id_ctx, user_id_ctx

logger = get_logger("http")

NextCall = Callable[[Request], Awaitable[Response]]


class RequestContextMiddleware(BaseHTTPMiddleware):
    """Assign a request id, expose it on the response and log the exchange."""

    _QUIET_PATHS = frozenset({"/health", "/health/live", "/health/ready", "/metrics"})

    async def dispatch(self, request: Request, call_next: NextCall) -> Response:
        request_id = request.headers.get("x-request-id") or uuid.uuid4().hex
        token = request_id_ctx.set(request_id)
        user_token = user_id_ctx.set(None)
        started = time.perf_counter()
        try:
            response = await call_next(request)
        except Exception:
            duration_ms = (time.perf_counter() - started) * 1000
            logger.exception(
                "request_failed",
                method=request.method,
                path=request.url.path,
                duration_ms=round(duration_ms, 2),
            )
            raise
        finally:
            request_id_ctx.reset(token)
            user_id_ctx.reset(user_token)

        duration_ms = (time.perf_counter() - started) * 1000
        response.headers["X-Request-ID"] = request_id
        response.headers["X-Response-Time-ms"] = f"{duration_ms:.1f}"
        if request.url.path not in self._QUIET_PATHS:
            logger.info(
                "request",
                method=request.method,
                path=request.url.path,
                status=response.status_code,
                duration_ms=round(duration_ms, 2),
            )
        return response


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    """Apply the standard hardening headers to every response.

    The API only ever returns JSON/SSE, so a maximally restrictive CSP is
    correct here: nothing in a response is meant to be rendered as a document.
    """

    def __init__(self, app: ASGIApp, *, hsts: bool = False) -> None:
        super().__init__(app)
        self._hsts = hsts

    async def dispatch(self, request: Request, call_next: NextCall) -> Response:
        response = await call_next(request)
        headers = response.headers
        headers.setdefault("X-Content-Type-Options", "nosniff")
        headers.setdefault("X-Frame-Options", "DENY")
        headers.setdefault("Referrer-Policy", "no-referrer")
        headers.setdefault("Cross-Origin-Opener-Policy", "same-origin")
        headers.setdefault("Cross-Origin-Resource-Policy", "same-site")
        headers.setdefault("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        headers.setdefault(
            "Content-Security-Policy",
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        )
        if self._hsts:
            headers.setdefault(
                "Strict-Transport-Security", "max-age=63072000; includeSubDomains; preload"
            )
        # Never let an intermediary cache an authenticated response.
        if "authorization" in request.headers:
            headers.setdefault("Cache-Control", "no-store")
        return response


class BodySizeLimitMiddleware(BaseHTTPMiddleware):
    """Reject oversized bodies before they are buffered.

    ``Content-Length`` is checked up-front; chunked uploads without the header
    are still bounded by the per-endpoint streaming check in the upload route.
    """

    def __init__(self, app: ASGIApp, *, max_bytes: int) -> None:
        super().__init__(app)
        self._max_bytes = max_bytes

    async def dispatch(self, request: Request, call_next: NextCall) -> Response:
        raw_length = request.headers.get("content-length")
        if raw_length and raw_length.isdigit() and int(raw_length) > self._max_bytes:
            from app.core.errors import PayloadTooLargeError  # local: avoid cycle

            error = PayloadTooLargeError(
                details={"max_bytes": self._max_bytes, "received": int(raw_length)}
            )
            from starlette.responses import JSONResponse

            return JSONResponse(error.to_payload(), status_code=error.status_code)
        return await call_next(request)
