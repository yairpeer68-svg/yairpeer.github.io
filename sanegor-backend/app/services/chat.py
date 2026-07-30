"""Chat orchestration.

Implements steps 6-9 of the RAG flow: send the grounded prompt to DeepSeek,
stream the answer, validate citations as tokens arrive, and persist the turn.

Streaming contract (Server-Sent Events)::

    event: start     {"conversation_id", "message_id", "sources": [...]}
    event: delta     {"text": "..."}                     (repeated)
    event: done      {"citations": [...], "disclaimer": "...", ...}
    event: error     {"code", "message"}

The client renders ``sources`` immediately — they are known before the model
answers — so the user sees what the answer is grounded in from the first frame.
"""

from __future__ import annotations

import json
import time
import uuid
from collections.abc import AsyncIterator
from dataclasses import dataclass

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import AppError, NotFoundError, UpstreamError
from app.core.logging import get_logger
from app.db.models.conversation import (
    Conversation,
    ConversationKind,
    Message,
    MessageRole,
)
from app.services.ai.citations import Citation, StreamingCitationGuard, validate_and_collect
from app.services.ai.context import ContextBuilder, count_tokens
from app.services.ai.deepseek import DeepSeekClient
from app.services.ai.prompts import (
    DISCLAIMER_HE,
    chat_system_prompt,
    conversation_title_prompt,
)
from app.services.rag.pipeline import GroundedContext, RagPipeline

logger = get_logger(__name__)

# Turns of prior conversation offered to the context builder. The builder drops
# more if the budget is tight; this bounds the query cost.
_HISTORY_TURNS = 20


@dataclass(slots=True)
class ChatTurn:
    """A completed exchange, ready to return to a non-streaming caller."""

    conversation_id: str
    message_id: str
    content: str
    citations: list[Citation]
    model: str
    latency_ms: int
    token_count: int
    grounded: bool


def sse(event: str, data: dict[str, object]) -> str:
    """Format one Server-Sent Event frame."""
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


class ChatService:
    """Owns conversation state and answer generation."""

    def __init__(
        self,
        session: AsyncSession,
        deepseek: DeepSeekClient,
        rag: RagPipeline,
        settings: Settings,
    ) -> None:
        self._session = session
        self._deepseek = deepseek
        self._rag = rag
        self._settings = settings

    # ---------------------------------------------------------- conversations
    async def get_or_create_conversation(
        self,
        conversation_id: str | None,
        *,
        user_id: str,
        kind: ConversationKind = ConversationKind.CHAT,
    ) -> Conversation:
        """Load the user's conversation, or start a new one."""
        if conversation_id:
            conversation = await self._load_conversation(conversation_id, user_id)
            return conversation

        conversation = Conversation(user_id=uuid.UUID(user_id), kind=kind)
        self._session.add(conversation)
        await self._session.flush()
        return conversation

    async def _load_conversation(self, conversation_id: str, user_id: str) -> Conversation:
        try:
            identifier = uuid.UUID(conversation_id)
        except ValueError as exc:
            raise NotFoundError("השיחה לא נמצאה") from exc

        conversation = (
            await self._session.execute(
                select(Conversation).where(
                    Conversation.id == identifier,
                    Conversation.user_id == uuid.UUID(user_id),
                    Conversation.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()

        if conversation is None:
            raise NotFoundError("השיחה לא נמצאה")
        return conversation

    async def _history(self, conversation_id: uuid.UUID) -> list[tuple[str, str]]:
        """Recent turns, oldest first, excluding failed assistant messages."""
        rows = (
            await self._session.execute(
                select(Message.role, Message.content)
                .where(Message.conversation_id == conversation_id, Message.error.is_(None))
                .order_by(Message.created_at.desc())
                .limit(_HISTORY_TURNS)
            )
        ).all()
        return [(str(role), content) for role, content in reversed(rows)]

    # ------------------------------------------------------------- generation
    async def _prepare(
        self, conversation: Conversation, question: str
    ) -> tuple[list, GroundedContext]:
        context = await self._rag.retrieve(question)
        history = await self._history(conversation.id)
        builder = ContextBuilder(
            self._settings.ai_context_token_budget, self._settings.deepseek_max_tokens
        )
        messages, report = builder.build(
            system_prompt=chat_system_prompt(context.blocks),
            history=history,
            question=question,
        )
        logger.debug(
            "chat_context_ready",
            sources=len(context.blocks),
            history_kept=len(messages) - 2,
            dropped=report.dropped_messages,
        )
        return list(messages), context

    async def complete(
        self,
        question: str,
        *,
        user_id: str,
        conversation_id: str | None = None,
        attachments: list[dict] | None = None,
    ) -> ChatTurn:
        """Answer without streaming."""
        started = time.perf_counter()
        conversation = await self.get_or_create_conversation(
            conversation_id, user_id=user_id
        )
        await self._append_user_message(conversation, question, attachments or [])

        messages, context = await self._prepare(conversation, question)
        result = await self._deepseek.complete(messages)
        outcome = validate_and_collect(
            result.content, context.blocks, context.citations_by_index
        )

        latency_ms = int((time.perf_counter() - started) * 1000)
        message = await self._append_assistant_message(
            conversation,
            outcome.text,
            citations=outcome.citations,
            model=result.model or self._deepseek.model,
            token_count=result.completion_tokens or count_tokens(outcome.text),
            latency_ms=latency_ms,
        )
        await self._maybe_title(conversation, question)

        return ChatTurn(
            conversation_id=str(conversation.id),
            message_id=str(message.id),
            content=outcome.text,
            citations=outcome.citations,
            model=result.model or self._deepseek.model,
            latency_ms=latency_ms,
            token_count=message.token_count,
            grounded=not context.is_empty,
        )

    async def stream(
        self,
        question: str,
        *,
        user_id: str,
        conversation_id: str | None = None,
        attachments: list[dict] | None = None,
    ) -> AsyncIterator[str]:
        """Answer as an SSE stream, persisting the turn when it completes."""
        started = time.perf_counter()

        try:
            conversation = await self.get_or_create_conversation(
                conversation_id, user_id=user_id
            )
            await self._append_user_message(conversation, question, attachments or [])
            messages, context = await self._prepare(conversation, question)
        except AppError as exc:
            yield sse("error", {"code": exc.code, "message": exc.message})
            return

        message_id = str(uuid.uuid4())
        yield sse(
            "start",
            {
                "conversation_id": str(conversation.id),
                "message_id": message_id,
                "sources": [c.to_dict() for c in context.citations_by_index.values()],
                "grounded": not context.is_empty,
            },
        )

        guard = StreamingCitationGuard(context.valid_indices)
        collected: list[str] = []
        failure: AppError | None = None

        try:
            async for delta in self._deepseek.stream(messages):
                if safe := guard.feed(delta):
                    collected.append(safe)
                    yield sse("delta", {"text": safe})
            if tail := guard.flush():
                collected.append(tail)
                yield sse("delta", {"text": tail})
        except AppError as exc:
            failure = exc
        except Exception as exc:  # noqa: BLE001 - stream must not kill the worker
            logger.exception("chat_stream_failed", error=str(exc))
            failure = UpstreamError()

        answer = "".join(collected).strip()
        latency_ms = int((time.perf_counter() - started) * 1000)

        if failure is not None and not answer:
            # Nothing usable was produced — record the failure, tell the client.
            await self._append_assistant_message(
                conversation,
                "",
                citations=[],
                model=self._deepseek.model,
                token_count=0,
                latency_ms=latency_ms,
                error=failure.code,
            )
            yield sse("error", {"code": failure.code, "message": failure.message})
            return

        citations = [
            context.citations_by_index[index]
            for index in sorted(guard.used_indices)
            if index in context.citations_by_index
        ]
        message = await self._append_assistant_message(
            conversation,
            answer,
            citations=citations,
            model=self._deepseek.model,
            token_count=count_tokens(answer),
            latency_ms=latency_ms,
            error=failure.code if failure else None,
        )
        title = await self._maybe_title(conversation, question)

        yield sse(
            "done",
            {
                "conversation_id": str(conversation.id),
                "message_id": str(message.id),
                "citations": [c.to_dict() for c in citations],
                "grounded": not context.is_empty,
                "truncated": failure is not None,
                "latency_ms": latency_ms,
                "conversation_title": title,
                "disclaimer": DISCLAIMER_HE,
            },
        )

    # ------------------------------------------------------------ persistence
    async def _append_user_message(
        self, conversation: Conversation, content: str, attachments: list[dict]
    ) -> Message:
        message = Message(
            conversation_id=conversation.id,
            role=MessageRole.USER,
            content=content,
            attachments=attachments,
            token_count=count_tokens(content),
        )
        self._session.add(message)
        conversation.message_count += 1
        await self._session.flush()
        return message

    async def _append_assistant_message(
        self,
        conversation: Conversation,
        content: str,
        *,
        citations: list[Citation],
        model: str,
        token_count: int,
        latency_ms: int,
        error: str | None = None,
    ) -> Message:
        message = Message(
            conversation_id=conversation.id,
            role=MessageRole.ASSISTANT,
            content=content,
            citations=[c.to_dict() for c in citations],
            token_count=token_count,
            model=model,
            latency_ms=latency_ms,
            error=error,
        )
        self._session.add(message)
        conversation.message_count += 1
        conversation.total_tokens += token_count
        await self._session.flush()
        return message

    async def _maybe_title(self, conversation: Conversation, first_message: str) -> str:
        """Name a new conversation from its opening question.

        Titling is best-effort: a failure here must never fail the answer the
        user is waiting for, so it falls back to a truncated question.
        """
        if conversation.title != "שיחה חדשה":
            return conversation.title

        fallback = " ".join(first_message.split())[:60] or "שיחה חדשה"
        if not self._deepseek.enabled:
            conversation.title = fallback
            await self._session.flush()
            return fallback

        try:
            from app.services.ai.deepseek import ChatMessage

            result = await self._deepseek.complete(
                [ChatMessage(role="user", content=conversation_title_prompt(first_message))],
                temperature=0.3,
                max_tokens=32,
            )
            title = " ".join(result.content.split()).strip(' "\'.:،')[:60]
        except Exception as exc:  # noqa: BLE001
            logger.warning("conversation_title_failed", error=str(exc))
            title = ""

        conversation.title = title or fallback
        await self._session.flush()
        return conversation.title

    # ----------------------------------------------------------------- history
    async def list_conversations(
        self,
        user_id: str,
        *,
        limit: int = 20,
        offset: int = 0,
        favorites_only: bool = False,
        query: str | None = None,
    ) -> tuple[list[Conversation], int]:
        """Paginated conversation list for the history screen."""
        conditions = [
            Conversation.user_id == uuid.UUID(user_id),
            Conversation.deleted_at.is_(None),
        ]
        if favorites_only:
            conditions.append(Conversation.is_favorite.is_(True))
        if query and (needle := query.strip()):
            conditions.append(Conversation.title.ilike(f"%{needle}%"))

        total = int(
            (
                await self._session.execute(
                    select(func.count()).select_from(Conversation).where(*conditions)
                )
            ).scalar_one()
        )
        rows = (
            await self._session.execute(
                select(Conversation)
                .where(*conditions)
                .order_by(Conversation.is_pinned.desc(), Conversation.updated_at.desc())
                .limit(limit)
                .offset(offset)
            )
        ).scalars().all()
        return list(rows), total

    async def list_messages(
        self, conversation_id: str, *, user_id: str, limit: int = 100, offset: int = 0
    ) -> tuple[Conversation, list[Message], int]:
        """Messages of one conversation, oldest first."""
        conversation = await self._load_conversation(conversation_id, user_id)
        total = int(
            (
                await self._session.execute(
                    select(func.count())
                    .select_from(Message)
                    .where(Message.conversation_id == conversation.id)
                )
            ).scalar_one()
        )
        rows = (
            await self._session.execute(
                select(Message)
                .where(Message.conversation_id == conversation.id)
                .order_by(Message.created_at)
                .limit(limit)
                .offset(offset)
            )
        ).scalars().all()
        return conversation, list(rows), total

    async def search_messages(
        self, conversation_id: str, *, user_id: str, query: str, limit: int = 50
    ) -> list[Message]:
        """Search within one conversation."""
        conversation = await self._load_conversation(conversation_id, user_id)
        needle = query.strip()
        if not needle:
            return []
        rows = (
            await self._session.execute(
                select(Message)
                .where(
                    Message.conversation_id == conversation.id,
                    Message.content.ilike(f"%{needle}%"),
                )
                .order_by(Message.created_at)
                .limit(limit)
            )
        ).scalars().all()
        return list(rows)

    async def delete_conversation(self, conversation_id: str, *, user_id: str) -> None:
        """Soft-delete a conversation."""
        from app.db.base import utcnow

        conversation = await self._load_conversation(conversation_id, user_id)
        conversation.deleted_at = utcnow()
        await self._session.flush()

    async def update_conversation(
        self,
        conversation_id: str,
        *,
        user_id: str,
        title: str | None = None,
        is_pinned: bool | None = None,
        is_favorite: bool | None = None,
    ) -> Conversation:
        """Rename, pin or favourite a conversation."""
        conversation = await self._load_conversation(conversation_id, user_id)
        if title is not None:
            conversation.title = title.strip()[:200] or conversation.title
        if is_pinned is not None:
            conversation.is_pinned = is_pinned
        if is_favorite is not None:
            conversation.is_favorite = is_favorite
        await self._session.flush()
        return conversation

    async def set_message_pinned(
        self, message_id: str, *, user_id: str, pinned: bool
    ) -> Message:
        """Pin or unpin a single message."""
        try:
            identifier = uuid.UUID(message_id)
        except ValueError as exc:
            raise NotFoundError("ההודעה לא נמצאה") from exc

        message = (
            await self._session.execute(
                select(Message)
                .join(Conversation, Message.conversation_id == Conversation.id)
                .where(Message.id == identifier, Conversation.user_id == uuid.UUID(user_id))
            )
        ).scalar_one_or_none()

        if message is None:
            raise NotFoundError("ההודעה לא נמצאה")
        message.is_pinned = pinned
        await self._session.flush()
        return message
