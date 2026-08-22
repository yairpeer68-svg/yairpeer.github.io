import logging
import re
import time
import uuid

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request

from app.monitoring.metrics import HTTP_ERRORS, HTTP_LATENCY, HTTP_REQUESTS

log = logging.getLogger("http")
VALID_ID = re.compile(r"^[A-Za-z0-9._:-]{1,64}$")


class RequestContextMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        incoming = request.headers.get("X-Request-ID", "")
        request_id = incoming if VALID_ID.match(incoming) else str(uuid.uuid4())
        request.state.request_id = request_id
        started = time.perf_counter()
        response = await call_next(request)
        elapsed = time.perf_counter() - started
        # Only matched routes become metric labels. Falling back to the raw path let any
        # 404 with a random URL create a new Prometheus time series.
        route = getattr(request.scope.get("route"), "path", None) or "unmatched"
        HTTP_REQUESTS.labels(request.method, route, str(response.status_code)).inc()
        HTTP_LATENCY.labels(request.method, route).observe(elapsed)
        if response.status_code >= 400:
            HTTP_ERRORS.labels(route, str(response.status_code)).inc()
        response.headers["X-Request-ID"] = request_id
        log.info("request", extra={"request_id": request_id, "path": request.url.path,
                                   "status": response.status_code, "latency_ms": round(elapsed * 1000, 2),
                                   "user_id": getattr(request.state, "user_id", None)})
        return response
