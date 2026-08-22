import importlib
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Header
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.trustedhost import TrustedHostMiddleware
from fastapi.responses import JSONResponse, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from sqlalchemy import text

from app.api.v1.router import router as v1_router
from app.core.config import get_settings
from app.core.errors import AppError, app_error_handler, unhandled_error_handler, validation_error_handler, http_error_handler
from app.core.logging import configure_logging
from app.db.session import close_engine, get_engine
from app.middleware.maintenance import MaintenanceMiddleware
from app.middleware.request_context import RequestContextMiddleware
from app.middleware.security_headers import SecurityHeadersMiddleware
from app.services.redis_service import get_redis_service

settings = get_settings()
configure_logging(settings.LOG_LEVEL)
log = logging.getLogger("app")


def configure_sentry() -> None:
    if not settings.SENTRY_DSN:
        return
    try:
        sentry_sdk = importlib.import_module("sentry_sdk")
        sentry_sdk.init(dsn=settings.SENTRY_DSN, environment=settings.APP_ENV, release=settings.APP_VERSION,
                        traces_sample_rate=0.1, send_default_pii=False, max_request_body_size="never")
    except ModuleNotFoundError:
        log.warning("Sentry DSN configured but sentry-sdk package is unavailable")


@asynccontextmanager
async def lifespan(app: FastAPI):
    configure_sentry()
    yield
    await close_engine()
    try:
        await get_redis_service(settings).close()
    except Exception as exc:
        log.warning("redis_close_failed", extra={"event": type(exc).__name__})


app = FastAPI(title=settings.APP_NAME, version=settings.APP_VERSION, docs_url="/docs" if settings.APP_ENV != "production" else None,
              redoc_url=None, lifespan=lifespan)
# Starlette types every handler as (Request, Exception); these narrow the second
# argument to the exception class they are registered for, which is the documented
# pattern but is not expressible in the base signature.
app.add_exception_handler(AppError, app_error_handler)  # type: ignore[arg-type]
app.add_exception_handler(RequestValidationError, validation_error_handler)  # type: ignore[arg-type]
app.add_exception_handler(StarletteHTTPException, http_error_handler)  # type: ignore[arg-type]
app.add_exception_handler(Exception, unhandled_error_handler)
app.add_middleware(TrustedHostMiddleware, allowed_hosts=settings.trusted_hosts)
app.add_middleware(CORSMiddleware, allow_origins=settings.cors_origins, allow_credentials=False,
                   allow_methods=["GET","POST","PUT","PATCH","DELETE","OPTIONS"], allow_headers=["Authorization","Content-Type","X-Request-ID","X-Device-ID"])
app.add_middleware(MaintenanceMiddleware, redis=get_redis_service(settings))
app.add_middleware(SecurityHeadersMiddleware)
app.add_middleware(RequestContextMiddleware)
app.include_router(v1_router)


@app.get("/health")
async def health():
    return {"status":"ok"}


@app.get("/health/live")
async def live():
    return {"status":"alive"}


@app.get("/health/ready")
async def ready():
    db_ok=False
    redis_ok=False
    try:
        async with get_engine().connect() as conn:
            await conn.execute(text("SELECT 1"))
        db_ok=True
    except Exception:
        db_ok=False
    redis_ok=await get_redis_service(settings).ping()
    result={"status":"ready" if db_ok and redis_ok else "not_ready","database":"ok" if db_ok else "unavailable",
            "redis":"ok" if redis_ok else "unavailable",
            "deepseek":"configured" if settings.DEEPSEEK_API_KEY else "not configured"}
    return JSONResponse(result,status_code=200 if db_ok and redis_ok else 503)


@app.get("/version")
async def version():
    return {"version":settings.APP_VERSION,"git_commit":settings.GIT_COMMIT,"build_time":settings.BUILD_TIME,"environment":settings.APP_ENV}


@app.get("/metrics")
async def metrics(authorization: str | None = Header(default=None)):
    if not settings.PROMETHEUS_ENABLED:
        return Response(status_code=404)
    if settings.APP_ENV == "production" and settings.METRICS_TOKEN:
        expected=f"Bearer {settings.METRICS_TOKEN}"
        if authorization != expected:
            return Response(status_code=401)
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
