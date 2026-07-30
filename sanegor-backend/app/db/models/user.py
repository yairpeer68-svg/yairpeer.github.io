"""User, role and refresh-token models."""

from __future__ import annotations

import enum
import uuid
from datetime import datetime
from typing import TYPE_CHECKING

from sqlalchemy import Boolean, DateTime, Enum, ForeignKey, Index, String, UniqueConstraint
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, SoftDeleteMixin, TimestampMixin, UUIDPrimaryKeyMixin, as_aware
from app.db.types import json_column

if TYPE_CHECKING:
    from app.db.models.conversation import Conversation
    from app.db.models.document import Document


class UserRole(str, enum.Enum):
    """Role-based access control levels, ordered from least to most powerful."""

    GUEST = "guest"
    USER = "user"
    LAWYER = "lawyer"
    ADMIN = "admin"

    @property
    def rank(self) -> int:
        return _ROLE_RANK[self]

    def satisfies(self, required: UserRole) -> bool:
        """True when this role is at least as powerful as ``required``."""
        return self.rank >= required.rank


_ROLE_RANK: dict[UserRole, int] = {
    UserRole.GUEST: 0,
    UserRole.USER: 1,
    UserRole.LAWYER: 2,
    UserRole.ADMIN: 3,
}


class AuthProvider(str, enum.Enum):
    LOCAL = "local"
    GOOGLE = "google"
    APPLE = "apple"


class User(Base, UUIDPrimaryKeyMixin, TimestampMixin, SoftDeleteMixin):
    """An account. ``email`` is stored lower-cased and is the login identity."""

    __tablename__ = "users"
    __table_args__ = (
        Index("ix_users_email_active", "email", unique=True),
        {"comment": "Application accounts"},
    )

    email: Mapped[str] = mapped_column(String(320), nullable=False)
    hashed_password: Mapped[str | None] = mapped_column(String(255), nullable=True)
    full_name: Mapped[str] = mapped_column(String(120), nullable=False, default="")
    phone: Mapped[str | None] = mapped_column(String(32), nullable=True)

    role: Mapped[UserRole] = mapped_column(
        Enum(UserRole, name="user_role", values_callable=lambda e: [m.value for m in e]),
        nullable=False,
        default=UserRole.USER,
        index=True,
    )
    provider: Mapped[AuthProvider] = mapped_column(
        Enum(AuthProvider, name="auth_provider", values_callable=lambda e: [m.value for m in e]),
        nullable=False,
        default=AuthProvider.LOCAL,
    )
    provider_subject: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)

    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    is_email_verified: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    failed_login_count: Mapped[int] = mapped_column(nullable=False, default=0)
    locked_until: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    preferences: Mapped[dict] = mapped_column(json_column(), nullable=False, default=dict)

    conversations: Mapped[list[Conversation]] = relationship(
        back_populates="user", cascade="all, delete-orphan", lazy="raise"
    )
    documents: Mapped[list[Document]] = relationship(
        back_populates="user", cascade="all, delete-orphan", lazy="raise"
    )
    refresh_tokens: Mapped[list[RefreshToken]] = relationship(
        back_populates="user", cascade="all, delete-orphan", lazy="raise"
    )

    @property
    def is_locked(self) -> bool:
        from app.db.base import utcnow

        return self.locked_until is not None and as_aware(self.locked_until) > utcnow()

    def __repr__(self) -> str:  # pragma: no cover
        return f"<User {self.email} role={self.role.value}>"


class RefreshToken(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    """A persisted refresh-token handle.

    Only the ``jti`` is stored — never the token itself — so a database leak
    cannot be replayed against the API.
    """

    __tablename__ = "refresh_tokens"
    __table_args__ = (UniqueConstraint("jti", name="uq_refresh_tokens_jti"),)

    user_id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    jti: Mapped[str] = mapped_column(String(64), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    user_agent: Mapped[str | None] = mapped_column(String(255))
    ip_address: Mapped[str | None] = mapped_column(String(64))

    user: Mapped[User] = relationship(back_populates="refresh_tokens", lazy="joined")

    @property
    def is_valid(self) -> bool:
        from app.db.base import utcnow

        return self.revoked_at is None and as_aware(self.expires_at) > utcnow()
