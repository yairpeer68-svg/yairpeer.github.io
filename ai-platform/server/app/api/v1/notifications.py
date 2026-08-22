import uuid
from datetime import UTC, datetime
from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies.auth import current_user
from app.core.errors import AppError
from app.db.session import get_session
from app.models.entities import Notification, User
from app.schemas.common import MessageResponse

router = APIRouter()


@router.get("")
async def list_notifications(user: User = Depends(current_user), db: AsyncSession = Depends(get_session),
                             unread_only: bool = False, limit: int = 50):
    limit = min(max(limit, 1), 200)
    stmt = select(Notification).where(Notification.user_id == user.id)
    if unread_only:
        stmt = stmt.where(Notification.read_at.is_(None))
    items = list((await db.scalars(stmt.order_by(Notification.created_at.desc()).limit(limit))).all())
    return [{"id": str(x.id), "title": x.title, "body": x.body, "kind": x.kind,
             "data": x.data_json, "read_at": x.read_at, "created_at": x.created_at} for x in items]


@router.post("/{notification_id}/read", response_model=MessageResponse)
async def mark_read(notification_id: uuid.UUID, user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    item = await db.get(Notification, notification_id)
    if not item or item.user_id != user.id:
        raise AppError("NOTIFICATION_NOT_FOUND", "Notification not found", 404)
    item.read_at = item.read_at or datetime.now(UTC)
    await db.commit()
    return MessageResponse(message="Notification marked as read")
