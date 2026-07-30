"""Health, readiness and metrics endpoints.

``/health/live`` answers whether the process is up; ``/health/ready`` answers
whether it can actually serve — those are different questions, and conflating
them makes an orchestrator restart a pod that is merely waiting on Postgres.
"""

from __future__ import annotations

import time
from typing import Any

from fastapi import APIRouter, Request, Response, status
from sqlalchemy import text

from app.core.logging import get_logger

logger = get_logger(__name__)
router = APIRouter(tags=["health"])

_STARTED_AT = time.time()


@router.get("/health", summary="בדיקת בריאות")
async def health(request: Request) -> dict[str, Any]:
    """Liveness plus a summary of which optional features are configured."""
    settings = request.app.state.settings
    return {
        "status": "ok",
        "service": settings.app_name,
        "version": request.app.version,
        "environment": settings.environment,
        "uptime_seconds": round(time.time() - _STARTED_AT, 1),
        "features": {
            "deepseek": bool(settings.deepseek_api_key),
            "rag": settings.rag_enabled,
            "embedding_provider": settings.embedding_provider,
            "ocr": settings.ocr_enabled,
            "encryption_at_rest": request.app.state.cipher.enabled,
        },
    }


@router.get("/health/live", summary="Liveness probe")
async def live() -> dict[str, str]:
    """Always 200 while the process is running."""
    return {"status": "alive"}


@router.get("/health/ready", summary="Readiness probe")
async def ready(request: Request, response: Response) -> dict[str, Any]:
    """Check the dependencies required to serve traffic.

    Postgres is mandatory; Redis is not — losing the cache degrades rate
    limiting and caching but the API still answers, so it is reported without
    failing the probe.
    """
    checks: dict[str, Any] = {}

    started = time.perf_counter()
    try:
        async with request.app.state.database.session() as session:
            await session.execute(text("SELECT 1"))
        checks["database"] = {
            "status": "ok",
            "latency_ms": round((time.perf_counter() - started) * 1000, 2),
        }
    except Exception as exc:  # noqa: BLE001
        logger.error("readiness_database_failed", error=str(exc))
        checks["database"] = {"status": "error", "error": str(exc)[:200]}

    cache_ok = await request.app.state.cache.ping()
    checks["redis"] = {"status": "ok" if cache_ok else "unavailable", "required": False}

    ready_now = checks["database"]["status"] == "ok"
    if not ready_now:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return {"status": "ready" if ready_now else "not_ready", "checks": checks}
