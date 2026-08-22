import logging

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse

from app.services.redis_service import RedisService

log = logging.getLogger("maintenance")


class MaintenanceMiddleware(BaseHTTPMiddleware):
    def __init__(self, app, redis: RedisService):
        super().__init__(app)
        self.redis = redis

    async def dispatch(self, request: Request, call_next):
        path = request.url.path
        if path.startswith(("/health", "/version", "/metrics", "/api/v1/admin")):
            return await call_next(request)
        try:
            enabled = await self.redis.get_value("system:maintenance:enabled")
            if enabled == "1":
                message = await self.redis.get_value("system:maintenance:message") or "Service is under maintenance"
                return JSONResponse({"maintenance": True, "message": message}, status_code=503)
        except Exception as exc:
            # Redis failures are exposed by readiness; middleware fails open to avoid cascading total outage.
            log.warning("maintenance_state_unavailable", extra={"event": type(exc).__name__})
        return await call_next(request)
