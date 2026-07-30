"""Chat endpoints, including the SSE stream."""

from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from app.api.deps import (
    CurrentUser,
    get_audit_service,
    get_chat_service,
    rate_limit_ai,
)
from app.core.logging import get_logger
from app.db.models.audit import AuditAction
from app.schemas.chat import ChatRequest, ChatResponse
from app.schemas.common import CitationOut
from app.services.ai.prompts import DISCLAIMER_HE
from app.services.audit import AuditService
from app.services.chat import ChatService

logger = get_logger(__name__)

router = APIRouter(prefix="/chat", tags=["chat"], dependencies=[Depends(rate_limit_ai)])

ChatDep = Annotated[ChatService, Depends(get_chat_service)]
AuditDep = Annotated[AuditService, Depends(get_audit_service)]

# Disable proxy buffering so deltas reach the phone as they are produced.
_SSE_HEADERS = {
    "Cache-Control": "no-cache, no-transform",
    "Connection": "keep-alive",
    "X-Accel-Buffering": "no",
}


@router.post(
    "",
    response_model=ChatResponse,
    summary="שליחת הודעה לצ'אט המשפטי",
    response_description="תשובה מלאה, או זרם SSE כאשר stream=true",
)
async def chat(
    payload: ChatRequest,
    request: Request,
    user: CurrentUser,
    service: ChatDep,
    audit: AuditDep,
) -> ChatResponse | StreamingResponse:
    """Answer a legal question.

    With ``stream: true`` (the default) the response is an SSE stream of
    ``start`` / ``delta`` / ``done`` events; with ``stream: false`` it is a
    single JSON body. Both paths persist the turn and attach only citations
    that resolve to rows in the legal corpus.
    """
    attachments = [a.model_dump() for a in payload.attachments]

    if payload.stream:
        return StreamingResponse(
            _guarded_stream(
                service.stream(
                    payload.message,
                    user_id=str(user.id),
                    conversation_id=payload.conversation_id,
                    attachments=attachments,
                ),
                request,
            ),
            media_type="text/event-stream",
            headers=_SSE_HEADERS,
        )

    turn = await service.complete(
        payload.message,
        user_id=str(user.id),
        conversation_id=payload.conversation_id,
        attachments=attachments,
    )
    await audit.record(
        AuditAction.CHAT_COMPLETION,
        user_id=str(user.id),
        resource_type="conversation",
        resource_id=turn.conversation_id,
        metadata={"grounded": turn.grounded, "latency_ms": turn.latency_ms},
    )
    return ChatResponse(
        conversation_id=turn.conversation_id,
        message_id=turn.message_id,
        content=turn.content,
        citations=[CitationOut(**c.to_dict()) for c in turn.citations],
        grounded=turn.grounded,
        model=turn.model,
        latency_ms=turn.latency_ms,
        disclaimer=DISCLAIMER_HE,
    )


async def _guarded_stream(
    source: AsyncIterator[str], request: Request
) -> AsyncIterator[str]:
    """Stop generating as soon as the client goes away.

    Without this check a user closing the app would leave the model call
    running to completion and keep billing for tokens nobody will read.
    """
    async for frame in source:
        if await request.is_disconnected():
            logger.info("chat_stream_client_disconnected")
            return
        yield frame
