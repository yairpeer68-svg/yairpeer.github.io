"""Document, contract and ruling analysis endpoints."""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends

from app.api.deps import (
    CurrentUser,
    get_analysis_service,
    get_audit_service,
    rate_limit_ai,
)
from app.db.models.audit import AuditAction
from app.db.models.document import AnalysisKind
from app.schemas.common import CitationOut
from app.schemas.documents import AnalysisRequest, AnalysisResponse
from app.services.ai.prompts import DISCLAIMER_HE
from app.services.audit import AuditService
from app.services.legal.analysis import AnalysisOutcome, AnalysisService

router = APIRouter(prefix="/analysis", tags=["analysis"], dependencies=[Depends(rate_limit_ai)])

AnalysisDep = Annotated[AnalysisService, Depends(get_analysis_service)]
AuditDep = Annotated[AuditService, Depends(get_audit_service)]


def _response(document_id: str, outcome: AnalysisOutcome) -> AnalysisResponse:
    return AnalysisResponse(
        document_id=document_id,
        kind=outcome.kind.value,
        summary=outcome.summary,
        payload=outcome.payload,
        citations=[CitationOut(**c.to_dict()) for c in outcome.citations],
        complexity_score=outcome.complexity_score,
        risk_score=outcome.risk_score,
        model=outcome.model,
        cached=outcome.cached,
        disclaimer=DISCLAIMER_HE,
    )


@router.post("/document", response_model=AnalysisResponse, summary="ניתוח מסמך")
async def analyse_document(
    payload: AnalysisRequest,
    user: CurrentUser,
    service: AnalysisDep,
    audit: AuditDep,
) -> AnalysisResponse:
    """General analysis: summary, key points, obligations, dates and risks."""
    outcome = await service.analyse(
        payload.document_id,
        user_id=str(user.id),
        kind=AnalysisKind.DOCUMENT,
        focus=payload.focus,
        refresh=payload.refresh,
    )
    await audit.record(
        AuditAction.DOCUMENT_ANALYSIS,
        user_id=str(user.id),
        resource_type="document",
        resource_id=payload.document_id,
        metadata={"cached": outcome.cached},
    )
    return _response(payload.document_id, outcome)


@router.post("/contract", response_model=AnalysisResponse, summary="ניתוח חוזה")
async def analyse_contract(
    payload: AnalysisRequest,
    user: CurrentUser,
    service: AnalysisDep,
    audit: AuditDep,
) -> AnalysisResponse:
    """Contract review: risks, missing clauses, imbalance and negotiation points."""
    outcome = await service.analyse(
        payload.document_id,
        user_id=str(user.id),
        kind=AnalysisKind.CONTRACT,
        focus=payload.focus,
        refresh=payload.refresh,
    )
    await audit.record(
        AuditAction.CONTRACT_ANALYSIS,
        user_id=str(user.id),
        resource_type="document",
        resource_id=payload.document_id,
        metadata={"risk_score": outcome.risk_score, "cached": outcome.cached},
    )
    return _response(payload.document_id, outcome)


@router.post("/case-summary", response_model=AnalysisResponse, summary="סיכום פסיקה")
async def summarise_case(
    payload: AnalysisRequest,
    user: CurrentUser,
    service: AnalysisDep,
) -> AnalysisResponse:
    """Summarise an uploaded court ruling in plain Hebrew."""
    outcome = await service.analyse(
        payload.document_id,
        user_id=str(user.id),
        kind=AnalysisKind.CASE_SUMMARY,
        focus=payload.focus,
        refresh=payload.refresh,
    )
    return _response(payload.document_id, outcome)
