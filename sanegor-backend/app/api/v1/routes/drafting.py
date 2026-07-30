"""Contract and letter generation endpoints."""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Query

from app.api.deps import (
    CurrentUser,
    get_audit_service,
    get_drafting_service,
    rate_limit_ai,
    rate_limit_default,
)
from app.db.models.audit import AuditAction
from app.schemas.common import CitationOut
from app.schemas.documents import GeneratedDocumentOut, GenerateRequest, TemplateOut
from app.services.ai.prompts import DISCLAIMER_HE
from app.services.audit import AuditService
from app.services.legal.drafting import DraftingService, DraftResult
from app.services.legal.templates import list_templates

DraftDep = Annotated[DraftingService, Depends(get_drafting_service)]
AuditDep = Annotated[AuditService, Depends(get_audit_service)]

contracts_router = APIRouter(prefix="/contracts", tags=["contracts"])
letters_router = APIRouter(prefix="/letters", tags=["letters"])


def _response(draft: DraftResult, record_id: str, created_at: object) -> GeneratedDocumentOut:
    return GeneratedDocumentOut(
        id=record_id,
        category=draft.category,
        template_key=draft.template_key,
        title=draft.title,
        body_markdown=draft.body_markdown,
        citations=[CitationOut(**c.to_dict()) for c in draft.citations],
        missing_fields=draft.missing_fields,
        model=draft.model,
        created_at=created_at,  # type: ignore[arg-type]
        disclaimer=DISCLAIMER_HE,
    )


# ------------------------------------------------------------------- contracts
@contracts_router.get(
    "/templates",
    response_model=list[TemplateOut],
    dependencies=[Depends(rate_limit_default)],
    summary="רשימת תבניות חוזים",
)
async def contract_templates(_user: CurrentUser) -> list[TemplateOut]:
    """Every contract template with the fields the client should render."""
    return [TemplateOut(**t.to_dict()) for t in list_templates("contract")]


@contracts_router.post(
    "/generate",
    response_model=GeneratedDocumentOut,
    dependencies=[Depends(rate_limit_ai)],
    summary="יצירת חוזה",
)
async def generate_contract(
    payload: GenerateRequest,
    user: CurrentUser,
    service: DraftDep,
    audit: AuditDep,
) -> GeneratedDocumentOut:
    """Draft a contract from a template plus the user's inputs.

    Fields the user left blank are rendered as ``______`` and reported in
    ``missing_fields`` rather than invented.
    """
    draft = await service.generate_contract(
        payload.template_key, payload.inputs, user_id=str(user.id)
    )
    record = await service.persist(draft, user_id=str(user.id), inputs=payload.inputs)
    await audit.record(
        AuditAction.CONTRACT_GENERATE,
        user_id=str(user.id),
        resource_type="generated_document",
        resource_id=str(record.id),
        metadata={"template_key": draft.template_key},
    )
    return _response(draft, str(record.id), record.created_at)


# --------------------------------------------------------------------- letters
@letters_router.get(
    "/templates",
    response_model=list[TemplateOut],
    dependencies=[Depends(rate_limit_default)],
    summary="רשימת תבניות מכתבים",
)
async def letter_templates(_user: CurrentUser) -> list[TemplateOut]:
    """Every letter template with the fields the client should render."""
    return [TemplateOut(**t.to_dict()) for t in list_templates("letter")]


@letters_router.post(
    "/generate",
    response_model=GeneratedDocumentOut,
    dependencies=[Depends(rate_limit_ai)],
    summary="יצירת מכתב משפטי",
)
async def generate_letter(
    payload: GenerateRequest,
    user: CurrentUser,
    service: DraftDep,
    audit: AuditDep,
) -> GeneratedDocumentOut:
    """Draft a legal letter from a template plus the user's inputs."""
    draft = await service.generate_letter(
        payload.template_key, payload.inputs, user_id=str(user.id)
    )
    record = await service.persist(draft, user_id=str(user.id), inputs=payload.inputs)
    await audit.record(
        AuditAction.LETTER_GENERATE,
        user_id=str(user.id),
        resource_type="generated_document",
        resource_id=str(record.id),
        metadata={"template_key": draft.template_key},
    )
    return _response(draft, str(record.id), record.created_at)


# --------------------------------------------------------- generated documents
generated_router = APIRouter(prefix="/generated", tags=["generated"])


@generated_router.get(
    "",
    response_model=list[GeneratedDocumentOut],
    dependencies=[Depends(rate_limit_default)],
    summary="מסמכים שנוצרו",
)
async def list_generated(
    user: CurrentUser,
    service: DraftDep,
    category: Annotated[str | None, Query(pattern="^(contract|letter)$")] = None,
    limit: Annotated[int, Query(ge=1, le=100)] = 20,
    offset: Annotated[int, Query(ge=0)] = 0,
) -> list[GeneratedDocumentOut]:
    """Contracts and letters this user generated."""
    rows = await service.list_generated(
        user_id=str(user.id), category=category, limit=limit, offset=offset
    )
    return [
        GeneratedDocumentOut(
            id=str(row.id),
            category=row.category,
            template_key=row.template_key,
            title=row.title,
            body_markdown=row.body_markdown,
            citations=[CitationOut(**c) for c in row.citations],
            model=row.model,
            created_at=row.created_at,
            disclaimer=DISCLAIMER_HE,
        )
        for row in rows
    ]
