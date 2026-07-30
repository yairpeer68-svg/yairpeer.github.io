"""Declarative base and shared column mixins."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from sqlalchemy import DateTime, MetaData, func
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

# Explicit naming convention so Alembic autogenerate produces stable,
# human-readable constraint names instead of database defaults.
NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


class Base(DeclarativeBase):
    """Base class for every ORM model."""

    metadata = MetaData(naming_convention=NAMING_CONVENTION)


def utcnow() -> datetime:
    """Timezone-aware ``now`` — used as the Python-side default."""
    return datetime.now(UTC)


def as_aware(value: datetime) -> datetime:
    """Force ``value`` to be timezone-aware, assuming UTC when it is naive.

    PostgreSQL round-trips ``TIMESTAMPTZ`` as aware datetimes, but SQLite —
    used by the unit-test suite — hands back naive ones. Comparing the two
    raises, so every comparison against :func:`utcnow` goes through here.
    """
    return value if value.tzinfo is not None else value.replace(tzinfo=UTC)


class UUIDPrimaryKeyMixin:
    """Adds a random UUID primary key.

    UUIDs keep identifiers non-enumerable, which matters for an API where
    resource ids travel to a mobile client.
    """

    id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )


class TimestampMixin:
    """Adds ``created_at`` / ``updated_at`` maintained by the database."""

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False, index=True
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
        nullable=False,
    )


class SoftDeleteMixin:
    """Adds ``deleted_at`` for reversible deletion.

    Legal work product should not vanish on a mis-tap; queries filter on
    ``deleted_at IS NULL`` and a retention job hard-deletes later.
    """

    deleted_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True, index=True
    )

    @property
    def is_deleted(self) -> bool:
        return self.deleted_at is not None
