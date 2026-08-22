import uuid
from fastapi import Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.entities import AdminAction, AuditLog, SecurityEvent


async def write_audit(session: AsyncSession, request: Request, action: str, actor_user_id: uuid.UUID | None = None,
                      target_type: str | None = None, target_id: str | None = None, metadata: dict | None = None) -> None:
    session.add(AuditLog(actor_user_id=actor_user_id, action=action, target_type=target_type, target_id=target_id,
                         ip_address=request.client.host if request.client else None,
                         device=request.headers.get("X-Device-ID"), request_id=getattr(request.state, "request_id", None),
                         metadata_json=metadata or {}))


async def write_security_event(session: AsyncSession, request: Request, event_type: str, severity: str = "medium",
                               user_id: uuid.UUID | None = None, metadata: dict | None = None) -> None:
    session.add(SecurityEvent(user_id=user_id, event_type=event_type, severity=severity,
                              ip_address=request.client.host if request.client else None,
                              request_id=getattr(request.state, "request_id", None), metadata_json=metadata or {}))


async def write_admin_action(session: AsyncSession, admin_user_id: uuid.UUID, action: str,
                             target_type: str | None = None, target_id: str | None = None,
                             metadata: dict | None = None) -> None:
    """Privileged-mutation trail, separate from the general audit log and never pruned with it."""
    session.add(AdminAction(admin_user_id=admin_user_id, action=action, target_type=target_type,
                            target_id=target_id, metadata_json=metadata or {}))
