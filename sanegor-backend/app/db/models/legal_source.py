"""The legal corpus: sources and their embedded chunks.

This is the only place citations may come from.  A :class:`LegalSource` is a
piece of primary material (a statute, a regulation, a ruling) that an operator
deliberately ingested; :class:`LegalChunk` is a retrievable slice of it with an
embedding.  The RAG pipeline can only cite chunk rows that exist here, which is
how the "never invent a reference" requirement is enforced structurally rather
than by asking the model nicely.
"""

from __future__ import annotations

import enum
import uuid
from datetime import date

from sqlalchemy import Date, ForeignKey, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.config import get_settings
from app.db.base import Base, TimestampMixin, UUIDPrimaryKeyMixin
from app.db.types import VectorType, json_column

# The vector column width is fixed when the table is created, so it is read
# from configuration at import time rather than per-instance.
EMBEDDING_DIMENSIONS = get_settings().embedding_dimensions


class SourceType(str, enum.Enum):
    """Kind of primary material."""

    LEGISLATION = "legislation"      # חקיקה ראשית
    REGULATION = "regulation"        # תקנות
    RULING = "ruling"                # פסיקה
    GUIDELINE = "guideline"          # הנחיות רשות
    FORM = "form"                    # טפסים רשמיים


class CourtLevel(str, enum.Enum):
    """Israeli court hierarchy, for filtering rulings."""

    SUPREME = "supreme"              # בית המשפט העליון
    DISTRICT = "district"            # מחוזי
    MAGISTRATE = "magistrate"        # שלום
    LABOR_NATIONAL = "labor_national"  # ארצי לעבודה
    LABOR_REGIONAL = "labor_regional"  # אזורי לעבודה
    FAMILY = "family"                # לענייני משפחה
    TRAFFIC = "traffic"              # תעבורה
    ADMINISTRATIVE = "administrative"  # לעניינים מנהליים
    OTHER = "other"


class LegalDomain(str, enum.Enum):
    """Practice areas used to narrow retrieval."""

    CIVIL = "civil"
    CONTRACTS = "contracts"
    LABOR = "labor"
    FAMILY = "family"
    CRIMINAL = "criminal"
    ADMINISTRATIVE = "administrative"
    TENANCY = "tenancy"
    CONSUMER = "consumer"
    CORPORATE = "corporate"
    TORTS = "torts"
    PRIVACY = "privacy"
    TAX = "tax"
    OTHER = "other"


class LegalSource(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    """A single ingested legal document."""

    __tablename__ = "legal_sources"
    __table_args__ = (
        UniqueConstraint("citation_key", name="uq_legal_sources_citation_key"),
        Index("ix_legal_sources_type_domain", "source_type", "domain"),
        Index("ix_legal_sources_published", "published_at"),
    )

    # Stable human-readable key, e.g. "חוק-החוזים-חלק-כללי-1973" — this is what
    # the model is shown and what the client resolves back to a source card.
    citation_key: Mapped[str] = mapped_column(String(160), nullable=False)
    title: Mapped[str] = mapped_column(String(400), nullable=False)
    short_title: Mapped[str | None] = mapped_column(String(200))
    source_type: Mapped[SourceType] = mapped_column(String(24), nullable=False, index=True)
    domain: Mapped[LegalDomain] = mapped_column(
        String(24), nullable=False, default=LegalDomain.OTHER, index=True
    )

    # Rulings
    case_number: Mapped[str | None] = mapped_column(String(80), index=True)
    court: Mapped[CourtLevel | None] = mapped_column(String(32), index=True)
    judges: Mapped[list] = mapped_column(json_column(), nullable=False, default=list)
    parties: Mapped[str | None] = mapped_column(String(400))
    proceeding_type: Mapped[str | None] = mapped_column(String(80), index=True)

    # Legislation
    section_range: Mapped[str | None] = mapped_column(String(80))
    amendment: Mapped[str | None] = mapped_column(String(120))

    published_at: Mapped[date | None] = mapped_column(Date)
    # Provenance is mandatory: an operator must record where material came from.
    source_url: Mapped[str | None] = mapped_column(String(1000))
    publisher: Mapped[str] = mapped_column(String(200), nullable=False, default="")
    language: Mapped[str] = mapped_column(String(8), nullable=False, default="he")
    checksum_sha256: Mapped[str | None] = mapped_column(String(64), index=True)
    chunk_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    extra: Mapped[dict] = mapped_column(json_column(), nullable=False, default=dict)

    chunks: Mapped[list[LegalChunk]] = relationship(
        back_populates="source", cascade="all, delete-orphan", lazy="raise"
    )

    def __repr__(self) -> str:  # pragma: no cover
        return f"<LegalSource {self.citation_key}>"


class LegalChunk(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    """An embedded, retrievable slice of a :class:`LegalSource`.

    The embedding dimension is fixed at table-creation time from
    ``EMBEDDING_DIMENSIONS``; changing the embedding model therefore requires a
    migration, which is deliberate — silently mixing vector spaces would make
    retrieval quietly wrong.
    """

    __tablename__ = "legal_chunks"
    __table_args__ = (
        UniqueConstraint("source_id", "ordinal", name="uq_legal_chunks_source_ordinal"),
        Index("ix_legal_chunks_source", "source_id"),
    )

    source_id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("legal_sources.id", ondelete="CASCADE")
    )
    ordinal: Mapped[int] = mapped_column(Integer, nullable=False)
    heading: Mapped[str | None] = mapped_column(String(300))
    content: Mapped[str] = mapped_column(Text, nullable=False)
    token_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    embedding: Mapped[list[float] | None] = mapped_column(
        VectorType(EMBEDDING_DIMENSIONS), nullable=True
    )

    source: Mapped[LegalSource] = relationship(back_populates="chunks", lazy="joined")
