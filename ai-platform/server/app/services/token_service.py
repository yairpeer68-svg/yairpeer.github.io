from datetime import UTC, datetime, timedelta

from fastapi import Request
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import AppError
from app.models.entities import EmailVerificationToken, PasswordResetToken, RefreshToken, Session, User
from app.repositories.audit import write_audit
from app.security.passwords import hash_password, validate_password_policy
from app.security.tokens import generate_opaque_token, hash_token


class AccountTokenService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_password_reset(self, user: User) -> str:
        raw = generate_opaque_token()
        self.db.add(PasswordResetToken(user_id=user.id, token_hash=hash_token(raw),
                                       expires_at=datetime.now(UTC) + timedelta(minutes=30)))
        await self.db.commit()
        return raw

    async def reset_password(self, request: Request, raw: str, new_password: str) -> None:
        validate_password_policy(new_password)
        token = await self.db.scalar(select(PasswordResetToken).where(
            PasswordResetToken.token_hash == hash_token(raw)).with_for_update())
        now = datetime.now(UTC)
        if not token or token.used_at is not None or token.expires_at <= now:
            raise AppError("INVALID_RESET_TOKEN", "Reset token is invalid or expired", 400)
        user = await self.db.get(User, token.user_id)
        if not user or not user.is_active:
            raise AppError("INVALID_RESET_TOKEN", "Reset token is invalid or expired", 400)
        user.password_hash = hash_password(new_password)
        token.used_at = now
        await self.db.execute(update(Session).where(Session.user_id == user.id, Session.revoked_at.is_(None)).values(revoked_at=now))
        await self.db.execute(update(RefreshToken).where(RefreshToken.user_id == user.id, RefreshToken.revoked_at.is_(None)).values(
            revoked_at=now, revoke_reason="password_reset"))
        await write_audit(self.db, request, "password_reset", user.id, "user", str(user.id))
        await self.db.commit()

    async def create_email_verification(self, user: User) -> str:
        raw = generate_opaque_token()
        self.db.add(EmailVerificationToken(user_id=user.id, token_hash=hash_token(raw),
                                           expires_at=datetime.now(UTC) + timedelta(hours=24)))
        await self.db.commit()
        return raw

    async def verify_email(self, request: Request, raw: str) -> None:
        token = await self.db.scalar(select(EmailVerificationToken).where(
            EmailVerificationToken.token_hash == hash_token(raw)).with_for_update())
        now = datetime.now(UTC)
        if not token or token.used_at is not None or token.expires_at <= now:
            raise AppError("INVALID_VERIFICATION_TOKEN", "Verification token is invalid or expired", 400)
        user = await self.db.get(User, token.user_id)
        if not user:
            raise AppError("INVALID_VERIFICATION_TOKEN", "Verification token is invalid or expired", 400)
        token.used_at = now
        user.email_verified_at = now
        await write_audit(self.db, request, "email_verified", user.id, "user", str(user.id))
        await self.db.commit()
