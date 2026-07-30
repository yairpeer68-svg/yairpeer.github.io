"""Conversation and message models."""

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


class MessageRole(str, enum.Enum):
    USER = "user"
    ASSISTANT = "assistant"
    SYSTEM = "system"


class ConversationKind(str, enum.Enum):
    """What produced the conversation — drives prompt selection and UI icons."""

    CHAT = "chat"
    DOCUMENT_ANALYSIS = "document_analysis"
    CONTRACT_ANALYSIS = "contract_analysis"
    CONTRACT_DRAFT = "contract_draft"
    LETTER_DRAFT = "letter_draft"
    CASE_SUMMARY = "case_summary"


class Conversation(Base, UUIDPrimaryKeyMixin, TimestampMixin, SoftDeleteMixin):
    """A thread of messages owned by exactly one user."""

    __tablename__ = "conversations"
    __table_args__ = (
        Index("ix_conversations_user_updated", "user_id", "updated_at"),
        Index("ix_conversations_user_pinned", "user_id", "is_pinned"),
    )

    user_id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    title: Mapped[str] = mapped_column(String(200), nullable=False, default="שיחה חדשה")
    kind: Mapped[ConversationKind] = mapped_column(
        String(32), nullable=False, default=ConversationKind.CHAT
    )
    is_pinned: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    is_favorite: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    message_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    total_tokens: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    extra: Mapped[dict] = mapped_column(json_column(), nullable=False, default=dict)

    user: Mapped[User] = relationship(back_populates="conversations", lazy="raise")
    messages: Mapped[list[Message]] = relationship(
        back_populates="conversation",
        cascade="all, delete-orphan",
        order_by="Message.created_at",
        lazy="raise",
    )


class Message(Base, UUIDPrimaryKeyMixin, TimestampMixin):
    """One turn in a conversation.

    ``citations`` holds the sources that were actually retrieved for this
    answer.  It is written by the RAG pipeline, never by the model, which is
    what makes "no invented references" enforceable.
    """

    __tablename__ = "messages"
    __table_args__ = (Index("ix_messages_conversation_created", "conversation_id", "created_at"),)

    conversation_id: Mapped[uuid.UUID] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("conversations.id", ondelete="CASCADE"), index=True
    )
    role: Mapped[MessageRole] = mapped_column(String(16), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    citations: Mapped[list] = mapped_column(json_column(), nullable=False, default=list)
    attachments: Mapped[list] = mapped_column(json_column(), nullable=False, default=list)
    is_pinned: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    token_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    model: Mapped[str | None] = mapped_column(String(64))
    latency_ms: Mapped[int | None] = mapped_column(Integer)
    error: Mapped[str | None] = mapped_column(Text)

    conversation: Mapped[Conversation] = relationship(back_populates="messages", lazy="raise")
