"""Document, contract and ruling analysis."""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import NotFoundError, ValidationError
from app.core.logging import get_logger
from app.core.security import TextCipher
from app.db.models.document import AnalysisKind, AnalysisResult, Document, DocumentStatus
from app.services.ai.citations import Citation, validate_and_collect
from app.services.ai.context import ContextBuilder
from app.services.ai.deepseek import DeepSeekClient, parse_json_response
from app.services.ai.prompts import (
    build_document_context,
    case_summary_prompt,
    contract_analysis_prompt,
    document_analysis_prompt,
)
from app.services.rag.pipeline import RagPipeline

logger = get_logger(__name__)

# Retrieval query is built from the document's opening, which carries the type
# and subject matter; the whole document would dilute the embedding.
_RETRIEVAL_EXCERPT_CHARS = 1500


@dataclass(slots=True)
class AnalysisOutcome:
    """Structured result of one analysis run."""

    kind: AnalysisKind
    summary: str
    payload: dict[str, Any]
    citations: list[Citation] = field(default_factory=list)
    complexity_score: int | None = None
    risk_score: int | None = None
    model: str = ""
    latency_ms: int = 0
    cached: bool = False


class AnalysisService:
    """Runs the analysis prompts against a stored document."""

    def __init__(
        self,
        session: AsyncSession,
        deepseek: DeepSeekClient,
        rag: RagPipeline,
        cipher: TextCipher,
        settings: Settings,
    ) -> None:
        self._session = session
        self._deepseek = deepseek
        self._rag = rag
        self._cipher = cipher
        self._settings = settings

    async def analyse(
        self,
        document_id: str,
        *,
        user_id: str,
        kind: AnalysisKind,
        focus: str | None = None,
        refresh: bool = False,
    ) -> AnalysisOutcome:
        """Analyse ``document_id`` on behalf of ``user_id``.

        Results are cached per ``(document, kind)`` unless ``refresh`` is set
        or the caller supplied a ``focus``, which makes the run bespoke.
        """
        document = await self._load_document(document_id, user_id)

        if not refresh and not focus:
            if (cached := await self._cached(document.id, kind)) is not None:
                logger.info("analysis_cache_hit", document_id=document_id, kind=kind.value)
                return cached

        text = self._plaintext(document)
        started = time.perf_counter()

        context = await self._rag.retrieve(text[:_RETRIEVAL_EXCERPT_CHARS], final_k=5)
        system_prompt = {
            AnalysisKind.DOCUMENT: document_analysis_prompt,
            AnalysisKind.CONTRACT: contract_analysis_prompt,
            AnalysisKind.CASE_SUMMARY: case_summary_prompt,
        }[kind](context.blocks)

        user_message = build_document_context(text)
        if focus:
            user_message += f"\n\nהמשתמש ביקש להתמקד במיוחד ב: {focus.strip()[:500]}"

        builder = ContextBuilder(
            self._settings.ai_context_token_budget, self._settings.deepseek_max_tokens
        )
        messages, _ = builder.build(
            system_prompt=system_prompt, history=[], question=user_message
        )

        # Case summaries are prose; the other two are structured JSON.
        json_mode = kind is not AnalysisKind.CASE_SUMMARY
        completion = await self._deepseek.complete(
            list(messages), temperature=0.15, json_mode=json_mode
        )
        outcome_text = validate_and_collect(
            completion.content, context.blocks, context.citations_by_index
        )

        if json_mode:
            payload = await parse_json_response(completion)
            # Re-validate markers inside the JSON string values.
            payload = self._sanitise_payload(payload, context)
            summary = str(payload.get("summary", "")).strip()
        else:
            payload = {"markdown": outcome_text.text}
            summary = self._first_paragraph(outcome_text.text)

        result = AnalysisOutcome(
            kind=kind,
            summary=summary,
            payload=payload,
            citations=outcome_text.citations,
            complexity_score=self._clamp_score(payload.get("complexity_score")),
            risk_score=self._clamp_score(payload.get("risk_score")),
            model=completion.model or self._deepseek.model,
            latency_ms=int((time.perf_counter() - started) * 1000),
        )

        if not focus:
            await self._store(document, user_id, result)

        logger.info(
            "analysis_completed",
            document_id=document_id,
            kind=kind.value,
            latency_ms=result.latency_ms,
            citations=len(result.citations),
        )
        return result

    # -------------------------------------------------------------- internals
    async def _load_document(self, document_id: str, user_id: str) -> Document:
        try:
            document_uuid = uuid.UUID(document_id)
        except ValueError as exc:
            raise NotFoundError("המסמך לא נמצא") from exc

        document = (
            await self._session.execute(
                select(Document).where(
                    Document.id == document_uuid,
                    Document.user_id == uuid.UUID(user_id),
                    Document.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()

        if document is None:
            raise NotFoundError("המסמך לא נמצא")
        if document.status is not DocumentStatus.READY:
            raise ValidationError(
                "עיבוד המסמך טרם הסתיים", details={"status": str(document.status)}
            )
        return document

    def _plaintext(self, document: Document) -> str:
        text = self._cipher.decrypt(document.extracted_text or "")
        if not text.strip():
            raise ValidationError("למסמך אין תוכן טקסטואלי לניתוח")
        return text

    async def _cached(self, document_id: uuid.UUID, kind: AnalysisKind) -> AnalysisOutcome | None:
        record = (
            await self._session.execute(
                select(AnalysisResult)
                .where(AnalysisResult.document_id == document_id, AnalysisResult.kind == kind)
                .order_by(AnalysisResult.created_at.desc())
                .limit(1)
            )
        ).scalar_one_or_none()

        if record is None:
            return None
        return AnalysisOutcome(
            kind=kind,
            summary=record.summary,
            payload=record.payload,
            citations=[Citation(**c) for c in record.citations],
            complexity_score=record.complexity_score,
            risk_score=record.risk_score,
            model=record.model or "",
            cached=True,
        )

    async def _store(self, document: Document, user_id: str, outcome: AnalysisOutcome) -> None:
        self._session.add(
            AnalysisResult(
                document_id=document.id,
                user_id=uuid.UUID(user_id),
                kind=outcome.kind,
                summary=outcome.summary,
                payload=outcome.payload,
                citations=[c.to_dict() for c in outcome.citations],
                complexity_score=outcome.complexity_score,
                risk_score=outcome.risk_score,
                model=outcome.model,
            )
        )
        await self._session.flush()

    @staticmethod
    def _sanitise_payload(payload: dict[str, Any], context: object) -> dict[str, Any]:
        """Strip citation markers the model invented inside JSON values."""
        blocks = context.blocks  # type: ignore[attr-defined]
        by_index = context.citations_by_index  # type: ignore[attr-defined]

        def clean(value: Any) -> Any:
            if isinstance(value, str):
                return validate_and_collect(value, blocks, by_index).text
            if isinstance(value, list):
                return [clean(item) for item in value]
            if isinstance(value, dict):
                return {key: clean(item) for key, item in value.items()}
            return value

        return {key: clean(value) for key, value in payload.items()}

    @staticmethod
    def _clamp_score(value: Any) -> int | None:
        """Coerce a model-supplied score into 1..10, or drop it."""
        try:
            score = int(value)
        except (TypeError, ValueError):
            return None
        return max(1, min(10, score))

    @staticmethod
    def _first_paragraph(text: str, max_chars: int = 400) -> str:
        for block in text.split("\n\n"):
            cleaned = block.strip().lstrip("#").strip()
            if len(cleaned) > 40:
                return cleaned[:max_chars]
        return text.strip()[:max_chars]
