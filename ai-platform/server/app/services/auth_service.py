import uuid
from datetime import UTC, datetime, timedelta

from fastapi import Request
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import AppError
from app.models.entities import RefreshToken, Session, User, Device, AIQuota, Subscription
from app.repositories.audit import write_audit, write_security_event
from app.repositories.users import UserRepository
from app.security.passwords import hash_password, validate_password_policy, verify_password
from app.security.tokens import create_access_token, generate_opaque_token, hash_token


class AuthService:
    def __init__(self, db: AsyncSession, settings: Settings):
        self.db = db
        self.settings = settings
        self.users = UserRepository(db)

    async def register(self, request: Request, email: str, password: str, display_name: str | None) -> User:
        validate_password_policy(password)
        if await self.users.by_email(email):
            raise AppError("EMAIL_EXISTS", "An account with this email already exists", 409)
        user = await self.users.create(email=email, password_hash=hash_password(password), display_name=display_name)
        self.db.add(AIQuota(user_id=user.id, requests_per_minute=self.settings.AI_RATE_LIMIT_PER_MINUTE,
                            requests_per_day=200, tokens_per_day=100000, max_output_tokens=self.settings.AI_MAX_RESPONSE_TOKENS))
        self.db.add(Subscription(user_id=user.id, plan="free", status="active", provider="mock"))
        await write_audit(self.db, request, "register", user.id, "user", str(user.id))
        await self.db.commit()
        return user

    async def login(self, request: Request, email: str, password: str, device_id: uuid.UUID | None) -> tuple[User, str, str]:
        user = await self.users.by_email(email)
        if not user or not verify_password(password, user.password_hash):
            await write_security_event(self.db, request, "failed_login", "medium", user.id if user else None,
                                       {"email_hash": hash_token(email)})
            await self.db.commit()
            raise AppError("INVALID_CREDENTIALS", "Invalid email or password", 401)
        if not user.is_active:
            raise AppError("ACCOUNT_DISABLED", "Account is disabled", 403)
        if device_id is not None:
            device = await self.db.get(Device, device_id)
            if device is None or device.user_id != user.id:
                await write_security_event(self.db, request, "unknown_device_binding", "medium", user.id, {})
                await self.db.commit()
                raise AppError("INVALID_DEVICE", "Device binding is invalid", 403)
            if device.revoked_at is not None:
                await write_security_event(self.db, request, "revoked_device_login", "high", user.id, {"device_id": str(device.id)})
                await self.db.commit()
                raise AppError("DEVICE_REVOKED", "This device has been revoked", 403)
        session = Session(user_id=user.id, device_id=device_id,
                          ip_address=request.client.host if request.client else None,
                          user_agent=request.headers.get("User-Agent", "")[:512])
        self.db.add(session)
        await self.db.flush()
        raw_refresh = generate_opaque_token()
        refresh = RefreshToken(session_id=session.id, user_id=user.id, device_id=device_id,
                               token_hash=hash_token(raw_refresh), family_id=uuid.uuid4(),
                               expires_at=datetime.now(UTC) + timedelta(days=self.settings.REFRESH_TOKEN_DAYS))
        self.db.add(refresh)
        await write_audit(self.db, request, "login", user.id, "session", str(session.id))
        await self.db.commit()
        return user, create_access_token(self.settings, user.id, session.id, user.is_admin), raw_refresh

    async def rotate_refresh(self, request: Request, raw_token: str) -> tuple[str, str]:
        digest = hash_token(raw_token)
        token = await self.db.scalar(select(RefreshToken).where(RefreshToken.token_hash == digest).with_for_update())
        now = datetime.now(UTC)
        if token is None:
            raise AppError("INVALID_REFRESH_TOKEN", "Invalid refresh token", 401)
        user = await self.users.by_id(token.user_id)
        if token.used_at is not None or token.revoked_at is not None:
            await self.db.execute(update(RefreshToken).where(RefreshToken.family_id == token.family_id,
                                                             RefreshToken.revoked_at.is_(None)).values(
                revoked_at=now, revoke_reason="reuse_detected"))
            await self.db.execute(
                update(Session).where(Session.id == token.session_id, Session.revoked_at.is_(None)).values(revoked_at=now)
            )
            await write_security_event(self.db, request, "refresh_token_reuse", "high", token.user_id,
                                       {"family_id": str(token.family_id)})
            await self.db.commit()
            raise AppError("REFRESH_TOKEN_REUSE", "Refresh token reuse detected; session family revoked", 401)
        if token.expires_at <= now:
            token.revoked_at = now
            token.revoke_reason = "expired"
            await self.db.commit()
            raise AppError("REFRESH_TOKEN_EXPIRED", "Refresh token expired", 401)
        if not user or not user.is_active:
            raise AppError("ACCOUNT_DISABLED", "Account is disabled", 403)
        session = await self.db.get(Session, token.session_id)
        if not session or session.revoked_at is not None:
            raise AppError("SESSION_REVOKED", "Session has been revoked", 401)
        token.used_at = now
        token.revoked_at = now
        token.revoke_reason = "rotated"
        raw_new = generate_opaque_token()
        child = RefreshToken(session_id=token.session_id, user_id=token.user_id, device_id=token.device_id,
                             token_hash=hash_token(raw_new), family_id=token.family_id, parent_id=token.id,
                             expires_at=now + timedelta(days=self.settings.REFRESH_TOKEN_DAYS))
        self.db.add(child)
        session.last_seen = now
        await self.db.commit()
        return create_access_token(self.settings, user.id, session.id, user.is_admin), raw_new

    async def logout(self, request: Request, user: User, session_id: uuid.UUID, raw_refresh: str | None) -> None:
        now = datetime.now(UTC)
        session = await self.db.get(Session, session_id)
        if session and session.user_id == user.id:
            session.revoked_at = now
        if raw_refresh:
            digest = hash_token(raw_refresh)
            token = await self.db.scalar(select(RefreshToken).where(RefreshToken.token_hash == digest,
                                                                    RefreshToken.user_id == user.id))
            if token:
                token.revoked_at = now
                token.revoke_reason = "logout"
        await write_audit(self.db, request, "logout", user.id, "session", str(session_id))
        await self.db.commit()

    async def revoke_all(self, request: Request, user: User, reason: str = "user_requested") -> None:
        now = datetime.now(UTC)
        await self.db.execute(update(Session).where(Session.user_id == user.id, Session.revoked_at.is_(None)).values(revoked_at=now))
        await self.db.execute(update(RefreshToken).where(RefreshToken.user_id == user.id, RefreshToken.revoked_at.is_(None)).values(
            revoked_at=now, revoke_reason=reason))
        await write_audit(self.db, request, "revoke_all_sessions", user.id, "user", str(user.id))
        await self.db.commit()

    async def change_password(self, request: Request, user: User, current_password: str, new_password: str) -> None:
        if not verify_password(current_password, user.password_hash):
            raise AppError("INVALID_PASSWORD", "Current password is incorrect", 400)
        validate_password_policy(new_password)
        user.password_hash = hash_password(new_password)
        await self.revoke_all(request, user, "password_changed")
        await write_audit(self.db, request, "password_change", user.id, "user", str(user.id))
        await self.db.commit()
