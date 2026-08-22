from fastapi import Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings, get_settings
from app.core.errors import AppError
from app.db.session import get_session
from app.repositories.audit import write_security_event
from app.monitoring.metrics import RATE_LIMIT_HITS
from app.services.redis_service import get_redis_service


async def _apply(scope: str, request: Request, limit: int, settings: Settings, db: AsyncSession) -> None:
    redis = get_redis_service(settings)
    client_ip = request.client.host if request.client else "unknown"
    try:
        await redis.rate_limit(f"ratelimit:{scope}:{client_ip}", limit, 60)
    except AppError as exc:
        if exc.status_code == 429:
            RATE_LIMIT_HITS.labels(scope).inc()
            try:
                await write_security_event(db, request, "excessive_requests", "medium", metadata={"scope": scope})
                await db.commit()
            except Exception:
                await db.rollback()
        raise
    except Exception as exc:
        raise AppError("RATE_LIMIT_BACKEND_UNAVAILABLE", "Rate limiting service is unavailable", 503) from exc


async def auth_rate_limit(request: Request, settings: Settings = Depends(get_settings), db: AsyncSession = Depends(get_session)) -> None:
    await _apply("auth", request, settings.AUTH_RATE_LIMIT_PER_MINUTE, settings, db)


async def admin_rate_limit(request: Request, settings: Settings = Depends(get_settings), db: AsyncSession = Depends(get_session)) -> None:
    await _apply("admin", request, settings.ADMIN_RATE_LIMIT_PER_MINUTE, settings, db)
