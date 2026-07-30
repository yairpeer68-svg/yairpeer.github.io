"""Audit-log writer."""

from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.logging import get_logger, request_id_ctx
from app.db.models.audit import AuditLog

logger = get_logger(__name__)

# Keys that must never be persisted, even if a caller passes them by mistake.
_FORBIDDEN_METADATA = frozenset(
    {"password", "token", "access_token", "refresh_token", "content", "text", "body"}
)


class AuditService:
    """Writes append-only security events."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def record(
        self,
        action: str,
        *,
        user_id: str | None = None,
        resource_type: str | None = None,
        resource_id: str | None = None,
        outcome: str = "success",
        ip_address: str | None = None,
        user_agent: str | None = None,
        status_code: int | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        """Record one event.

        Auditing never breaks the request it describes: a failure here is
        logged and swallowed, because losing an audit row is preferable to
        failing a user's action that already succeeded.
        """
        try:
            self._session.add(
                AuditLog(
                    user_id=uuid.UUID(user_id) if user_id else None,
                    action=action,
                    resource_type=resource_type,
                    resource_id=str(resource_id)[:64] if resource_id else None,
                    outcome=outcome,
                    ip_address=(ip_address or "")[:64] or None,
                    user_agent=(user_agent or "")[:255] or None,
                    request_id=request_id_ctx.get(),
                    status_code=status_code,
                    metadata_=self._scrub(metadata or {}),
                )
            )
            await self._session.flush()
        except Exception as exc:  # noqa: BLE001
            logger.error("audit_write_failed", action=action, error=str(exc))

    @staticmethod
    def _scrub(metadata: dict[str, Any]) -> dict[str, Any]:
        return {
            key: value
            for key, value in metadata.items()
            if key.lower() not in _FORBIDDEN_METADATA
        }

    async def list_for_user(
        self, user_id: str, *, limit: int = 50, offset: int = 0
    ) -> tuple[list[AuditLog], int]:
        """Recent events for one user — powers the 'account activity' screen."""
        condition = AuditLog.user_id == uuid.UUID(user_id)
        total = int(
            (
                await self._session.execute(
                    select(func.count()).select_from(AuditLog).where(condition)
                )
            ).scalar_one()
        )
        rows = (
            await self._session.execute(
                select(AuditLog)
                .where(condition)
                .order_by(AuditLog.created_at.desc())
                .limit(limit)
                .offset(offset)
            )
        ).scalars().all()
        return list(rows), total
