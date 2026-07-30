"""Contract and letter drafting."""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from datetime import date

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import NotFoundError, ValidationError
from app.core.logging import get_logger
from app.db.models.document import GeneratedDocument
from app.services.ai.citations import Citation, validate_and_collect
from app.services.ai.context import ContextBuilder
from app.services.ai.deepseek import DeepSeekClient
from app.services.ai.prompts import contract_generation_prompt, letter_generation_prompt
from app.services.legal.templates import (
    LegalTemplate,
    get_contract_template,
    get_letter_template,
)
from app.services.rag.pipeline import RagPipeline

logger = get_logger(__name__)


@dataclass(slots=True)
class DraftResult:
    """A generated document, ready to persist and export."""

    title: str
    body_markdown: str
    template_key: str
    category: str
    citations: list[Citation]
    model: str
    latency_ms: int
    missing_fields: list[str]


class DraftingService:
    """Generates contracts and letters from templates plus user input."""

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

    async def generate_contract(
        self, template_key: str, inputs: dict[str, object], *, user_id: str
    ) -> DraftResult:
        template = get_contract_template(template_key)
        if template is None:
            raise NotFoundError(
                "סוג החוזה המבוקש אינו קיים", details={"template_key": template_key}
            )
        return await self._generate(template, inputs, user_id=user_id)

    async def generate_letter(
        self, template_key: str, inputs: dict[str, object], *, user_id: str
    ) -> DraftResult:
        template = get_letter_template(template_key)
        if template is None:
            raise NotFoundError(
                "סוג המכתב המבוקש אינו קיים", details={"template_key": template_key}
            )
        return await self._generate(template, inputs, user_id=user_id)

    # -------------------------------------------------------------- internals
    async def _generate(
        self, template: LegalTemplate, inputs: dict[str, object], *, user_id: str
    ) -> DraftResult:
        started = time.perf_counter()
        missing = self._validate_inputs(template, inputs)

        # Retrieval is driven by the template's own subject matter plus what
        # the user actually described, so a rental draft pulls tenancy law.
        retrieval_query = self._retrieval_query(template, inputs)
        context = await self._rag.retrieve(retrieval_query, final_k=4)

        system_prompt = (
            contract_generation_prompt(context.blocks)
            if template.category == "contract"
            else letter_generation_prompt(context.blocks)
        )
        user_message = self._render_inputs(template, inputs, missing)

        builder = ContextBuilder(
            self._settings.ai_context_token_budget, self._settings.deepseek_max_tokens
        )
        messages, _ = builder.build(system_prompt=system_prompt, history=[], question=user_message)

        result = await self._deepseek.complete(
            list(messages), temperature=0.25, max_tokens=self._settings.deepseek_max_tokens
        )
        outcome = validate_and_collect(result.content, context.blocks, context.citations_by_index)

        title = self._title(template, inputs)
        latency_ms = int((time.perf_counter() - started) * 1000)
        logger.info(
            "draft_generated",
            template=template.key,
            category=template.category,
            citations=len(outcome.citations),
            latency_ms=latency_ms,
        )
        return DraftResult(
            title=title,
            body_markdown=outcome.text,
            template_key=template.key,
            category=template.category,
            citations=outcome.citations,
            model=result.model or self._deepseek.model,
            latency_ms=latency_ms,
            missing_fields=missing,
        )

    @staticmethod
    def _validate_inputs(template: LegalTemplate, inputs: dict[str, object]) -> list[str]:
        """Return the labels of required fields the user left blank.

        A missing field is not fatal — the draft renders it as ``______`` — but
        it is reported so the app can warn before the user sends the document.
        """
        known = {field.key for field in template.fields}
        unknown = set(inputs) - known
        if unknown:
            raise ValidationError(
                "נשלחו שדות שאינם חלק מהתבנית",
                details={"unknown_fields": sorted(unknown)},
            )
        return [
            field.label
            for field in template.fields
            if field.required and not str(inputs.get(field.key, "") or "").strip()
        ]

    @staticmethod
    def _retrieval_query(template: LegalTemplate, inputs: dict[str, object]) -> str:
        parts = [template.name, *template.search_hints]
        for key in ("subject", "services", "works_description", "facts", "grounds", "matter"):
            if value := str(inputs.get(key, "") or "").strip():
                parts.append(value[:300])
        return " ".join(parts)

    @staticmethod
    def _render_inputs(
        template: LegalTemplate, inputs: dict[str, object], missing: list[str]
    ) -> str:
        lines = [template.instruction_block(), "", "פרטים שסיפק המשתמש:"]
        for field in template.fields:
            value = inputs.get(field.key)
            if value is None or str(value).strip() == "":
                continue
            rendered = ("כן" if value else "לא") if field.type == "boolean" else str(value).strip()
            lines.append(f"- {field.label}: {rendered}")

        if missing:
            lines += [
                "",
                "פרטים חיוניים שהמשתמש לא סיפק — סמן אותם במסמך כ-______ " "ואל תמציא אותם:",
                *[f"- {label}" for label in missing],
            ]
        lines += ["", f"תאריך היום: {date.today().isoformat()}", "", "נסח כעת את המסמך המלא."]
        return "\n".join(lines)

    @staticmethod
    def _title(template: LegalTemplate, inputs: dict[str, object]) -> str:
        for key in ("subject", "matter", "property_address", "position", "business_name"):
            if value := str(inputs.get(key, "") or "").strip():
                return f"{template.name} — {value[:60]}"
        parties = [
            str(inputs.get("party_a_name", "") or "").strip(),
            str(inputs.get("party_b_name", "") or "").strip(),
        ]
        if all(parties):
            return f"{template.name} — {parties[0]} / {parties[1]}"
        return template.name

    async def persist(
        self, draft: DraftResult, *, user_id: str, inputs: dict[str, object]
    ) -> GeneratedDocument:
        """Store a draft so it appears in the user's documents list."""
        record = GeneratedDocument(
            user_id=uuid.UUID(user_id),
            category=draft.category,
            template_key=draft.template_key,
            title=draft.title,
            body_markdown=draft.body_markdown,
            inputs=inputs,
            citations=[c.to_dict() for c in draft.citations],
            model=draft.model,
        )
        self._session.add(record)
        await self._session.flush()
        return record

    async def list_generated(
        self,
        *,
        user_id: str,
        category: str | None = None,
        limit: int = 20,
        offset: int = 0,
    ) -> list[GeneratedDocument]:
        """Documents this user has generated, newest first."""
        conditions = [
            GeneratedDocument.user_id == uuid.UUID(user_id),
            GeneratedDocument.deleted_at.is_(None),
        ]
        if category:
            conditions.append(GeneratedDocument.category == category)

        rows = (
            (
                await self._session.execute(
                    select(GeneratedDocument)
                    .where(*conditions)
                    .order_by(GeneratedDocument.created_at.desc())
                    .limit(limit)
                    .offset(offset)
                )
            )
            .scalars()
            .all()
        )
        return list(rows)

    async def get_generated(self, document_id: str, *, user_id: str) -> GeneratedDocument:
        """Load one generated document owned by ``user_id``."""
        try:
            identifier = uuid.UUID(document_id)
        except ValueError as exc:
            raise NotFoundError("המסמך לא נמצא") from exc

        record = (
            await self._session.execute(
                select(GeneratedDocument).where(
                    GeneratedDocument.id == identifier,
                    GeneratedDocument.user_id == uuid.UUID(user_id),
                    GeneratedDocument.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if record is None:
            raise NotFoundError("המסמך לא נמצא")
        return record
