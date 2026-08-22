import uuid
from dataclasses import dataclass

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings, get_settings
from app.core.errors import AppError
from app.db.session import get_session
from app.models.entities import Session, User, Device, SecurityEvent
from app.security.tokens import decode_access_token

bearer = HTTPBearer(auto_error=False)


@dataclass
class AuthContext:
    user: User
    session_id: uuid.UUID


async def get_auth_context(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
    db: AsyncSession = Depends(get_session),
    settings: Settings = Depends(get_settings),
) -> AuthContext:
    if not credentials or credentials.scheme.lower() != "bearer":
        raise AppError("AUTH_REQUIRED", "Authentication required", 401)
    try:
        claims = decode_access_token(settings, credentials.credentials)
    except AppError:
        try:
            db.add(SecurityEvent(event_type="invalid_jwt", severity="medium",
                                 ip_address=request.client.host if request.client else None,
                                 request_id=getattr(request.state, "request_id", None), metadata_json={}))
            await db.commit()
        except Exception:
            await db.rollback()
        raise
    try:
        user_id = uuid.UUID(claims["sub"])
        session_id = uuid.UUID(claims["sid"])
    except (KeyError, ValueError) as exc:
        raise AppError("INVALID_TOKEN", "Invalid access token", 401) from exc
    user = await db.get(User, user_id)
    session = await db.get(Session, session_id)
    if not user or not user.is_active or user.deleted_at is not None:
        raise AppError("ACCOUNT_DISABLED", "Account is disabled", 403)
    if not session or session.user_id != user.id or session.revoked_at is not None:
        raise AppError("SESSION_REVOKED", "Session has been revoked", 401)
    if session.device_id is not None:
        device = await db.get(Device, session.device_id)
        if device is not None and device.revoked_at is not None:
            try:
                db.add(SecurityEvent(user_id=user.id, event_type="revoked_device_usage", severity="high",
                                     ip_address=request.client.host if request.client else None,
                                     request_id=getattr(request.state, "request_id", None),
                                     metadata_json={"device_id": str(device.id)}))
                await db.commit()
            except Exception:
                await db.rollback()
            raise AppError("DEVICE_REVOKED", "Device has been revoked", 403)
    request.state.user_id = str(user.id)
    return AuthContext(user=user, session_id=session_id)


async def current_user(ctx: AuthContext = Depends(get_auth_context)) -> User:
    return ctx.user


async def require_admin(ctx: AuthContext = Depends(get_auth_context)) -> User:
    if not ctx.user.is_admin:
        raise AppError("ADMIN_REQUIRED", "Administrator privileges required", 403)
    return ctx.user
