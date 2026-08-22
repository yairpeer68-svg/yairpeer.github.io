from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings, get_settings
from app.db.session import get_session
from app.models.entities import AppVersion

router = APIRouter()


@router.get("/app-version")
async def app_version(platform: str = Query("android", pattern="^(android|ios|web)$"),
                      db: AsyncSession = Depends(get_session)):
    item = await db.scalar(select(AppVersion).where(AppVersion.platform == platform).order_by(AppVersion.created_at.desc()))
    if item is None:
        return {"configured": False, "platform": platform}
    return {"configured": True, "platform": platform,
            "minimum_supported_version": item.minimum_supported_version,
            "latest_version": item.latest_version, "force_update": item.force_update,
            "release_notes": item.release_notes, "download_url": item.download_url, "store_url": item.store_url}


@router.get("/integrations")
async def integrations(settings: Settings = Depends(get_settings)):
    return {"deepseek": "configured" if settings.DEEPSEEK_API_KEY else "not configured",
            "play_integrity": "configured" if settings.PLAY_INTEGRITY_PROJECT_NUMBER and settings.PLAY_INTEGRITY_CREDENTIALS_JSON else "not configured",
            "fcm": "configured" if settings.FCM_CREDENTIALS_JSON else "not configured",
            "sentry": "configured" if settings.SENTRY_DSN else "not configured"}
