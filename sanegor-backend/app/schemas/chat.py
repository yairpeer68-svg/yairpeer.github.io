"""Chat and conversation-history schemas."""

from __future__ import annotations

from datetime import datetime
from typing import Annotated

from pydantic import Field, field_validator

from app.schemas.common import ApiModel, CitationOut


class AttachmentRef(ApiModel):
    """A previously uploaded document referenced by a chat turn."""

    document_id: str
    filename: str | None = None


class ChatRequest(ApiModel):
    """A user turn."""

    message: Annotated[str, Field(min_length=1, max_length=16_000)]
    conversation_id: str | None = None
    attachments: Annotated[list[AttachmentRef], Field(max_length=5)] = []
    stream: bool = True

    @field_validator("message")
    @classmethod
    def _not_blank(cls, value: str) -> str:
        cleaned = value.strip()
        if not cleaned:
            raise ValueError("ההודעה ריקה")
        return cleaned


class ChatResponse(ApiModel):
    """A completed non-streaming turn."""

    conversation_id: str
    message_id: str
    content: str
    citations: list[CitationOut] = []
    grounded: bool = False
    model: str
    latency_ms: int
    disclaimer: str


class MessageOut(ApiModel):
    id: str
    role: str
    content: str
    citations: list[CitationOut] = []
    attachments: list[dict] = []
    is_pinned: bool = False
    token_count: int = 0
    model: str | None = None
    error: str | None = None
    created_at: datetime

    @field_validator("id", "role", mode="before")
    @classmethod
    def _stringify(cls, value: object) -> str:
        return getattr(value, "value", None) or str(value)


class ConversationOut(ApiModel):
    id: str
    title: str
    kind: str
    is_pinned: bool
    is_favorite: bool
    message_count: int
    total_tokens: int
    created_at: datetime
    updated_at: datetime

    @field_validator("id", "kind", mode="before")
    @classmethod
    def _stringify(cls, value: object) -> str:
        return getattr(value, "value", None) or str(value)


class ConversationDetail(ConversationOut):
    messages: list[MessageOut] = []


class UpdateConversationRequest(ApiModel):
    title: Annotated[str, Field(min_length=1, max_length=200)] | None = None
    is_pinned: bool | None = None
    is_favorite: bool | None = None


class PinMessageRequest(ApiModel):
    is_pinned: bool
