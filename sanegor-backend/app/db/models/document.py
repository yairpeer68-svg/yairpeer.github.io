"""Uploaded document + analysis-result models."""

from __future__ import annotations

import enum
import uuid
from typing import TYPE_CHECKING

from sqlalchemy import Boolean, ForeignKey, Index, Integer, String, Text
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, SoftDeleteMixin, TimestampMixin, UUIDPrimaryKeyMixin
from app.db.types import json_column

if TYPE_CHECKING:
    from app.db.models.user import User


class DocumentStatus(str, enum.Enum):
    PENDING = "pending"
    PROCESSING = "processing"
    READY = "ready"
    FAILED = "failed"


class AnalysisKind(str, enum.Enum):
    DOCUMENT = "document"
    CONTRACT = "contract"
    CASE_SUMMARY = "case_summary"


class Document(Base, UUIDPrimaryKeyMixin, TimestampMixin, SoftDeleteMixin):
    """A file the user uploaded, plus its extracted text.

    ``extracted_text`` is written through :class:`~app.core.security.TextCipher`
    so the most sensitive column is encrypted at rest independently of disk
    encryption.
    """

    __tablename__ = "documents"
    __table_args__ = (Index("ix_documents_user_created", "user_id", "created_at"),)

    user_id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    filename: Mapped[str] = mapped_column(String(255), nullable=False)
    content_type: Mapped[str] = mapped_column(String(128), nullable=False)
    size_bytes: Mapped[int] = mapped_column(Integer, nullable=False)
    checksum_sha256: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    storage_key: Mapped[str] = mapped_column(String(512), nullable=False)

    status: Mapped[DocumentStatus] = mapped_column(
        String(16), nullable=False, default=DocumentStatus.PENDING, index=True
    )
    error: Mapped[str | None] = mapped_column(Text)

    extracted_text: Mapped[str | None] = mapped_column(Text)
    page_count: Mapped[int | None] = mapped_column(Integer)
    word_count: Mapped[int | None] = mapped_column(Integer)
    language: Mapped[str | None] = mapped_column(String(8))
    used_ocr: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    extra: Mapped[dict] = mapped_column(json_column(), nullable=False, default=dict)

    user: Mapped[User] = relationship(back_populates="documents", lazy="raise")
    analyses: Mapped[list[AnalysisResult]] = relationship(
        back_populates="document", cascade="all, delete-orphan", lazy="raise"
    )


class AnalysisResult(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    """A stored analysis run over a document.

    Caching by ``(document, kind)`` avoids paying for the same expensive model
    call twice; ``payload`` holds the structured findings.
    """

    __tablename__ = "analysis_results"
    __table_args__ = (Index("ix_analysis_document_kind", "document_id", "kind"),)

    document_id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("documents.id", ondelete="CASCADE"), index=True
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    kind: Mapped[AnalysisKind] = mapped_column(String(24), nullable=False)
    summary: Mapped[str] = mapped_column(Text, nullable=False, default="")
    payload: Mapped[dict] = mapped_column(json_column(), nullable=False, default=dict)
    citations: Mapped[list] = mapped_column(json_column(), nullable=False, default=list)
    complexity_score: Mapped[int | None] = mapped_column(Integer)
    risk_score: Mapped[int | None] = mapped_column(Integer)
    model: Mapped[str | None] = mapped_column(String(64))

    document: Mapped[Document] = relationship(back_populates="analyses", lazy="raise")


class GeneratedDocument(Base, UUIDPrimaryKeyMixin, TimestampMixin, SoftDeleteMixin):
    """A contract or letter the system drafted for the user."""

    __tablename__ = "generated_documents"
    __table_args__ = (Index("ix_generated_user_created", "user_id", "created_at"),)

    user_id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    category: Mapped[str] = mapped_column(String(16), nullable=False)  # contract | letter
    template_key: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    body_markdown: Mapped[str] = mapped_column(Text, nullable=False)
    inputs: Mapped[dict] = mapped_column(json_column(), nullable=False, default=dict)
    citations: Mapped[list] = mapped_column(json_column(), nullable=False, default=list)
    model: Mapped[str | None] = mapped_column(String(64))
