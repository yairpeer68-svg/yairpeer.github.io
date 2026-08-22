import logging
from datetime import UTC, datetime, timedelta

from sqlalchemy import delete
from sqlalchemy.ext.asyncio import async_sessionmaker

from app.core.config import get_settings
from app.db.session import get_engine
from app.models.entities import (AIRequest, AuditLog, EmailVerificationToken, EngineeringEvent,
                                 PasswordResetToken, RefreshToken, SecurityEvent)

log = logging.getLogger("tasks.cleanup")


async def cleanup_retention() -> dict[str, int]:
    """Apply the configured retention windows.

    Scheduled by the ``scheduler`` service; see ``app/workers/scheduler.py``. Without a
    scheduler these windows were configured but never enforced.
    """
    settings = get_settings()
    now = datetime.now(UTC)
    factory = async_sessionmaker(get_engine(), expire_on_commit=False)

    def deleted(result: object) -> int:
        return int(getattr(result, "rowcount", 0) or 0)

    async with factory() as db:
        expired = await db.execute(delete(RefreshToken).where(
            RefreshToken.expires_at < now, RefreshToken.revoked_at.is_not(None)))
        resets = await db.execute(delete(PasswordResetToken).where(PasswordResetToken.expires_at < now))
        verifications = await db.execute(delete(EmailVerificationToken).where(EmailVerificationToken.expires_at < now))
        audit = await db.execute(delete(AuditLog).where(
            AuditLog.created_at < now - timedelta(days=settings.AUDIT_RETENTION_DAYS)))
        security = await db.execute(delete(SecurityEvent).where(
            SecurityEvent.created_at < now - timedelta(days=settings.AUDIT_RETENTION_DAYS)))
        ai = await db.execute(delete(AIRequest).where(
            AIRequest.created_at < now - timedelta(days=settings.AI_METADATA_RETENTION_DAYS)))
        events = await db.execute(delete(EngineeringEvent).where(
            EngineeringEvent.created_at < now - timedelta(days=settings.AUDIT_RETENTION_DAYS)))
        await db.commit()
        result = {
            "refresh_tokens": deleted(expired),
            "password_resets": deleted(resets),
            "email_verifications": deleted(verifications),
            "audit_logs": deleted(audit),
            "security_events": deleted(security),
            "ai_requests": deleted(ai),
            "engineering_events": deleted(events),
        }
        log.info("retention_sweep_completed", extra={"event": "retention_sweep"})
        return result
