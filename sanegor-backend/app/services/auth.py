"""Authentication and account lifecycle."""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import AuthenticationError, ConflictError, NotFoundError, ValidationError
from app.core.logging import get_logger
from app.core.security import (
    TokenPair,
    TokenService,
    hash_password,
    needs_rehash,
    validate_password_strength,
    verify_password,
)
from app.db.base import as_aware, utcnow
from app.db.models.user import AuthProvider, RefreshToken, User, UserRole

logger = get_logger(__name__)

# Brute-force protection: after this many consecutive failures the account is
# locked for a cooling-off period. This complements, not replaces, the IP rate
# limit — one stops password spraying against many accounts, the other stops
# a distributed attack against one account.
_MAX_FAILED_LOGINS = 8
_LOCKOUT_MINUTES = 15


@dataclass(slots=True)
class AuthResult:
    """A successful authentication."""

    user: User
    tokens: TokenPair


class AuthService:
    """Registration, login, refresh, and password management."""

    def __init__(self, session: AsyncSession, settings: Settings) -> None:
        self._session = session
        self._settings = settings
        self._tokens = TokenService(settings)

    # ------------------------------------------------------------------ lookup
    @staticmethod
    def normalise_email(email: str) -> str:
        return email.strip().lower()

    async def get_by_email(self, email: str) -> User | None:
        return (
            await self._session.execute(
                select(User).where(
                    User.email == self.normalise_email(email), User.deleted_at.is_(None)
                )
            )
        ).scalar_one_or_none()

    async def get_by_id(self, user_id: str) -> User | None:
        try:
            identifier = uuid.UUID(user_id)
        except ValueError:
            return None
        return (
            await self._session.execute(
                select(User).where(User.id == identifier, User.deleted_at.is_(None))
            )
        ).scalar_one_or_none()

    # ------------------------------------------------------------ registration
    async def register(
        self,
        *,
        email: str,
        password: str,
        full_name: str,
        phone: str | None = None,
    ) -> AuthResult:
        """Create a local account and sign the user in."""
        email = self.normalise_email(email)
        validate_password_strength(password, self._settings.password_min_length)

        if await self.get_by_email(email) is not None:
            raise ConflictError("כתובת הדוא״ל כבר רשומה במערכת")

        user = User(
            email=email,
            hashed_password=hash_password(password),
            full_name=full_name.strip()[:120],
            phone=(phone or "").strip()[:32] or None,
            role=UserRole.USER,
            provider=AuthProvider.LOCAL,
        )
        self._session.add(user)

        try:
            await self._session.flush()
        except IntegrityError as exc:
            # Lost a race against a concurrent registration.
            await self._session.rollback()
            raise ConflictError("כתובת הדוא״ל כבר רשומה במערכת") from exc

        logger.info("user_registered", user_id=str(user.id))
        return AuthResult(user=user, tokens=await self._issue(user))

    # ------------------------------------------------------------------- login
    async def login(
        self,
        *,
        email: str,
        password: str,
        user_agent: str | None = None,
        ip_address: str | None = None,
    ) -> AuthResult:
        """Verify credentials and issue tokens.

        The same error is returned for an unknown address and a wrong password
        so the endpoint cannot be used to enumerate registered users.
        """
        user = await self.get_by_email(email)

        if user is None or not user.hashed_password:
            # Spend comparable time on the miss path to blunt timing analysis.
            verify_password(password, _DUMMY_HASH)
            raise AuthenticationError("כתובת דוא״ל או סיסמה שגויים")

        if user.is_locked:
            remaining = int((as_aware(user.locked_until) - utcnow()).total_seconds() // 60) + 1  # type: ignore[arg-type]
            raise AuthenticationError(
                f"החשבון נעול זמנית. נסה שוב בעוד {remaining} דקות",
                details={"locked": True},
            )

        if not user.is_active:
            raise AuthenticationError("החשבון אינו פעיל")

        if not verify_password(password, user.hashed_password):
            await self._register_failure(user)
            raise AuthenticationError("כתובת דוא״ל או סיסמה שגויים")

        # Transparently upgrade hashes when the Argon2 parameters change.
        if needs_rehash(user.hashed_password):
            user.hashed_password = hash_password(password)

        user.failed_login_count = 0
        user.locked_until = None
        user.last_login_at = utcnow()
        await self._session.flush()

        logger.info("user_login", user_id=str(user.id))
        return AuthResult(
            user=user,
            tokens=await self._issue(user, user_agent=user_agent, ip_address=ip_address),
        )

    async def _register_failure(self, user: User) -> None:
        user.failed_login_count += 1
        if user.failed_login_count >= _MAX_FAILED_LOGINS:
            user.locked_until = utcnow() + timedelta(minutes=_LOCKOUT_MINUTES)
            user.failed_login_count = 0
            logger.warning("account_locked", user_id=str(user.id))
        await self._session.flush()

    # ------------------------------------------------------------------ oauth
    async def login_with_provider(
        self,
        *,
        provider: AuthProvider,
        subject: str,
        email: str,
        full_name: str = "",
        email_verified: bool = True,
    ) -> AuthResult:
        """Sign in (or provision) an account from a verified OAuth identity.

        The caller is responsible for verifying the provider's ID token before
        calling this; by the time we get here ``subject`` is trusted.
        """
        email = self.normalise_email(email)
        user = (
            await self._session.execute(
                select(User).where(
                    User.provider == provider,
                    User.provider_subject == subject,
                    User.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()

        if user is None:
            # Link to an existing local account with the same verified address.
            user = await self.get_by_email(email)
            if user is not None:
                user.provider = provider
                user.provider_subject = subject
                user.is_email_verified = user.is_email_verified or email_verified
            else:
                user = User(
                    email=email,
                    full_name=full_name.strip()[:120],
                    provider=provider,
                    provider_subject=subject,
                    is_email_verified=email_verified,
                    role=UserRole.USER,
                )
                self._session.add(user)

        if not user.is_active:
            raise AuthenticationError("החשבון אינו פעיל")

        user.last_login_at = utcnow()
        await self._session.flush()
        logger.info("user_login_oauth", user_id=str(user.id), provider=provider.value)
        return AuthResult(user=user, tokens=await self._issue(user))

    # ------------------------------------------------------------------ tokens
    async def _issue(
        self,
        user: User,
        *,
        user_agent: str | None = None,
        ip_address: str | None = None,
    ) -> TokenPair:
        pair, refresh_jti = self._tokens.create_pair(str(user.id), user.role.value)
        self._session.add(
            RefreshToken(
                user_id=user.id,
                jti=refresh_jti,
                expires_at=utcnow() + timedelta(days=self._settings.refresh_token_ttl_days),
                user_agent=(user_agent or "")[:255] or None,
                ip_address=(ip_address or "")[:64] or None,
            )
        )
        await self._session.flush()
        return pair

    async def refresh(self, refresh_token: str) -> AuthResult:
        """Exchange a refresh token for a new pair, rotating the old one.

        Rotation is single-use: the presented token is revoked as part of the
        exchange, so a stolen refresh token stops working the moment the
        legitimate client next refreshes.
        """
        claims = self._tokens.decode(refresh_token, "refresh")

        record = (
            await self._session.execute(select(RefreshToken).where(RefreshToken.jti == claims.jti))
        ).scalar_one_or_none()

        if record is None or not record.is_valid:
            logger.warning("refresh_token_rejected", jti=claims.jti)
            raise AuthenticationError("תוקף ההתחברות פג. יש להתחבר מחדש")

        user = await self.get_by_id(claims.subject)
        if user is None or not user.is_active:
            raise AuthenticationError("החשבון אינו פעיל")

        record.revoked_at = utcnow()
        await self._session.flush()
        return AuthResult(user=user, tokens=await self._issue(user))

    async def logout(self, refresh_token: str | None, *, user_id: str) -> None:
        """Revoke one refresh token, or all of the user's when none is given."""
        if refresh_token:
            try:
                claims = self._tokens.decode(refresh_token, "refresh")
            except AuthenticationError:
                return  # Already invalid; logging out is idempotent.
            record = (
                await self._session.execute(
                    select(RefreshToken).where(RefreshToken.jti == claims.jti)
                )
            ).scalar_one_or_none()
            if record is not None and record.revoked_at is None:
                record.revoked_at = utcnow()
        else:
            await self.revoke_all_tokens(user_id)
        await self._session.flush()

    async def revoke_all_tokens(self, user_id: str) -> int:
        """Revoke every live refresh token for a user. Returns the count."""
        records = (
            (
                await self._session.execute(
                    select(RefreshToken).where(
                        RefreshToken.user_id == uuid.UUID(user_id),
                        RefreshToken.revoked_at.is_(None),
                    )
                )
            )
            .scalars()
            .all()
        )
        now = utcnow()
        for record in records:
            record.revoked_at = now
        await self._session.flush()
        return len(records)

    # -------------------------------------------------------------- passwords
    async def change_password(
        self, *, user_id: str, current_password: str, new_password: str
    ) -> None:
        """Change a password and invalidate every existing session."""
        user = await self.get_by_id(user_id)
        if user is None:
            raise NotFoundError("המשתמש לא נמצא")
        if not user.hashed_password or not verify_password(current_password, user.hashed_password):
            raise AuthenticationError("הסיסמה הנוכחית שגויה")
        if current_password == new_password:
            raise ValidationError("הסיסמה החדשה זהה לנוכחית")

        validate_password_strength(new_password, self._settings.password_min_length)
        user.hashed_password = hash_password(new_password)
        await self._session.flush()
        await self.revoke_all_tokens(user_id)
        logger.info("password_changed", user_id=user_id)

    def create_reset_token(self, user: User) -> str:
        """Mint a short-lived password-reset token."""
        return self._tokens.create_action_token(
            str(user.id), "reset_password", timedelta(minutes=30)
        )

    async def reset_password(self, *, token: str, new_password: str) -> None:
        """Complete a reset and invalidate every existing session."""
        claims = self._tokens.decode(token, "reset_password")
        user = await self.get_by_id(claims.subject)
        if user is None:
            raise NotFoundError("המשתמש לא נמצא")

        validate_password_strength(new_password, self._settings.password_min_length)
        user.hashed_password = hash_password(new_password)
        user.failed_login_count = 0
        user.locked_until = None
        await self._session.flush()
        await self.revoke_all_tokens(str(user.id))
        logger.info("password_reset", user_id=str(user.id))

    # ------------------------------------------------------- email verification
    def create_verification_token(self, user: User) -> str:
        return self._tokens.create_action_token(str(user.id), "verify_email", timedelta(days=3))

    async def verify_email(self, token: str) -> User:
        claims = self._tokens.decode(token, "verify_email")
        user = await self.get_by_id(claims.subject)
        if user is None:
            raise NotFoundError("המשתמש לא נמצא")
        user.is_email_verified = True
        await self._session.flush()
        return user

    # ------------------------------------------------------------ maintenance
    async def purge_expired_tokens(self) -> int:
        """Delete refresh tokens that expired more than a week ago."""
        from sqlalchemy import delete

        cutoff = datetime.now(UTC) - timedelta(days=7)
        result = await self._session.execute(
            delete(RefreshToken).where(RefreshToken.expires_at < cutoff)
        )
        return int(result.rowcount or 0)


# A valid Argon2 hash of a random value, used to equalise timing on the
# "unknown e-mail" path. It intentionally matches no real password.
_DUMMY_HASH = (
    "$argon2id$v=19$m=65536,t=3,p=2$"
    "c2FuZWdvcmR1bW15c2FsdA$8s5J8xY3wS6QyQ0v5nqXKQ3vJ3B8b0oQZ0nS0Y1pKQY"
)
