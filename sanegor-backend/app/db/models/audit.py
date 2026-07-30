"""Append-only audit log."""

from __future__ import annotations

import uuid

from sqlalchemy import ForeignKey, Index, Integer, String
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base, TimestampMixin, UUIDPrimaryKeyMixin
from app.db.types import json_column


class AuditLog(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    """One security-relevant event.

    Rows are written but never updated or deleted by application code. The
    ``metadata_`` payload must not contain document bodies or credentials —
    only identifiers and outcomes.
    """

    __tablename__ = "audit_logs"
    __table_args__ = (
        Index("ix_audit_logs_user_created", "user_id", "created_at"),
        Index("ix_audit_logs_action_created", "action", "created_at"),
    )

    user_id: Mapped[uuid.UUID | None] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )
    action: Mapped[str] = mapped_column(String(64), nullable=False)
    resource_type: Mapped[str | None] = mapped_column(String(64))
    resource_id: Mapped[str | None] = mapped_column(String(64))
    outcome: Mapped[str] = mapped_column(String(16), nullable=False, default="success")
    ip_address: Mapped[str | None] = mapped_column(String(64))
    user_agent: Mapped[str | None] = mapped_column(String(255))
    request_id: Mapped[str | None] = mapped_column(String(64), index=True)
    status_code: Mapped[int | None] = mapped_column(Integer)
    metadata_: Mapped[dict] = mapped_column("metadata", json_column(), nullable=False, default=dict)


class AuditAction:
    """Canonical action names, kept as constants to avoid typo drift."""

    LOGIN_SUCCESS = "auth.login.success"
    LOGIN_FAILED = "auth.login.failed"
    LOGOUT = "auth.logout"
    REGISTER = "auth.register"
    TOKEN_REFRESH = "auth.token.refresh"
    PASSWORD_RESET_REQUEST = "auth.password.reset_request"
    PASSWORD_RESET_COMPLETE = "auth.password.reset_complete"
    EMAIL_VERIFIED = "auth.email.verified"
    ACCOUNT_LOCKED = "auth.account.locked"

    CHAT_COMPLETION = "chat.completion"
    DOCUMENT_UPLOAD = "document.upload"
    DOCUMENT_DELETE = "document.delete"
    DOCUMENT_ANALYSIS = "analysis.document"
    CONTRACT_ANALYSIS = "analysis.contract"
    CONTRACT_GENERATE = "contract.generate"
    LETTER_GENERATE = "letter.generate"
    EXPORT = "document.export"
    HISTORY_DELETE = "history.delete"

    CORPUS_INGEST = "corpus.ingest"
    ROLE_CHANGED = "admin.role.changed"
