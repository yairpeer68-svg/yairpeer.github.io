"""Conversation history endpoints."""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Query

from app.api.deps import CurrentUser, get_audit_service, get_chat_service, rate_limit_default
from app.db.models.audit import AuditAction
from app.schemas.chat import (
    ConversationDetail,
    ConversationOut,
    MessageOut,
    PinMessageRequest,
    UpdateConversationRequest,
)
from app.schemas.common import MessageResponse, Page
from app.services.audit import AuditService
from app.services.chat import ChatService

router = APIRouter(
    prefix="/history", tags=["history"], dependencies=[Depends(rate_limit_default)]
)

ChatDep = Annotated[ChatService, Depends(get_chat_service)]
AuditDep = Annotated[AuditService, Depends(get_audit_service)]


@router.get("", response_model=Page[ConversationOut], summary="רשימת שיחות")
async def list_conversations(
    user: CurrentUser,
    service: ChatDep,
    limit: Annotated[int, Query(ge=1, le=100)] = 20,
    offset: Annotated[int, Query(ge=0)] = 0,
    favorites_only: bool = False,
    query: Annotated[str | None, Query(max_length=200)] = None,
) -> Page[ConversationOut]:
    """Paginated conversation list, pinned first."""
    conversations, total = await service.list_conversations(
        str(user.id),
        limit=limit,
        offset=offset,
        favorites_only=favorites_only,
        query=query,
    )
    return Page[ConversationOut](
        items=[ConversationOut.model_validate(c) for c in conversations],
        total=total,
        limit=limit,
        offset=offset,
    )


@router.get(
    "/{conversation_id}", response_model=ConversationDetail, summary="שיחה מלאה"
)
async def get_conversation(
    conversation_id: str,
    user: CurrentUser,
    service: ChatDep,
    limit: Annotated[int, Query(ge=1, le=200)] = 100,
    offset: Annotated[int, Query(ge=0)] = 0,
) -> ConversationDetail:
    """One conversation with its messages, oldest first."""
    conversation, messages, _ = await service.list_messages(
        conversation_id, user_id=str(user.id), limit=limit, offset=offset
    )
    detail = ConversationDetail.model_validate(conversation)
    detail.messages = [MessageOut.model_validate(m) for m in messages]
    return detail


@router.get(
    "/{conversation_id}/search",
    response_model=list[MessageOut],
    summary="חיפוש בתוך שיחה",
)
async def search_conversation(
    conversation_id: str,
    user: CurrentUser,
    service: ChatDep,
    q: Annotated[str, Query(min_length=1, max_length=200)],
) -> list[MessageOut]:
    """Find messages inside one conversation."""
    messages = await service.search_messages(
        conversation_id, user_id=str(user.id), query=q
    )
    return [MessageOut.model_validate(m) for m in messages]


@router.patch(
    "/{conversation_id}", response_model=ConversationOut, summary="עדכון שיחה"
)
async def update_conversation(
    conversation_id: str,
    payload: UpdateConversationRequest,
    user: CurrentUser,
    service: ChatDep,
) -> ConversationOut:
    """Rename, pin or favourite a conversation."""
    conversation = await service.update_conversation(
        conversation_id,
        user_id=str(user.id),
        title=payload.title,
        is_pinned=payload.is_pinned,
        is_favorite=payload.is_favorite,
    )
    return ConversationOut.model_validate(conversation)


@router.delete(
    "/{conversation_id}", response_model=MessageResponse, summary="מחיקת שיחה"
)
async def delete_conversation(
    conversation_id: str,
    user: CurrentUser,
    service: ChatDep,
    audit: AuditDep,
) -> MessageResponse:
    """Soft-delete a conversation."""
    await service.delete_conversation(conversation_id, user_id=str(user.id))
    await audit.record(
        AuditAction.HISTORY_DELETE,
        user_id=str(user.id),
        resource_type="conversation",
        resource_id=conversation_id,
    )
    return MessageResponse(message="השיחה נמחקה")


@router.post(
    "/messages/{message_id}/pin", response_model=MessageOut, summary="נעיצת הודעה"
)
async def pin_message(
    message_id: str,
    payload: PinMessageRequest,
    user: CurrentUser,
    service: ChatDep,
) -> MessageOut:
    """Pin or unpin one message."""
    message = await service.set_message_pinned(
        message_id, user_id=str(user.id), pinned=payload.is_pinned
    )
    return MessageOut.model_validate(message)
