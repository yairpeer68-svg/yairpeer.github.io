"""Legal search over the ingested corpus.

Search only ever returns material that exists in ``legal_sources``.  When the
corpus is empty the response says so explicitly (``corpus_empty``) instead of
silently returning nothing, so the app can tell the user that no material has
been loaded rather than implying no law is on point.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Query
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import (
    CurrentUser,
    SessionDep,
    get_embeddings,
    get_rag_pipeline,
    rate_limit_default,
)
from app.db.models.legal_source import LegalSource
from app.schemas.search import (
    LegalPassageOut,
    LegalSearchRequest,
    LegalSearchResponse,
    LegalSourceOut,
)
from app.services.ai.embeddings import EmbeddingProvider
from app.services.rag.pipeline import RagPipeline
from app.services.rag.retriever import LegalRetriever, RetrievalFilters

router = APIRouter(prefix="/search", tags=["search"], dependencies=[Depends(rate_limit_default)])

RagDep = Annotated[RagPipeline, Depends(get_rag_pipeline)]
EmbeddingsDep = Annotated[EmbeddingProvider, Depends(get_embeddings)]

_EMPTY_CORPUS_NOTICE = (
    "מאגר המקורות המשפטיים ריק. עד לטעינת חקיקה ופסיקה, המערכת לא תציג "
    "אסמכתאות ותענה ברמה עקרונית בלבד."
)


def _to_source_out(source: LegalSource) -> LegalSourceOut:
    return LegalSourceOut(
        id=str(source.id),
        citation_key=source.citation_key,
        title=source.title,
        short_title=source.short_title,
        source_type=str(source.source_type),
        domain=str(source.domain),
        case_number=source.case_number,
        court=str(source.court) if source.court else None,
        judges=list(source.judges or []),
        parties=source.parties,
        proceeding_type=source.proceeding_type,
        section_range=source.section_range,
        published_at=source.published_at,
        source_url=source.source_url,
        publisher=source.publisher,
        chunk_count=source.chunk_count,
    )


async def _corpus_is_empty(session: AsyncSession) -> bool:
    total = (await session.execute(select(func.count()).select_from(LegalSource))).scalar_one()
    return int(total) == 0


@router.post("", response_model=LegalSearchResponse, summary="חיפוש משפטי")
async def legal_search(
    payload: LegalSearchRequest,
    _user: CurrentUser,
    session: SessionDep,
    rag: RagDep,
) -> LegalSearchResponse:
    """Search legislation and case law.

    Returns two complementary views: ``sources`` (metadata-level matches, the
    browse experience) and, when ``semantic`` is on and a query was supplied,
    ``passages`` — the specific sections that matched, ranked.
    """
    filters = RetrievalFilters(
        source_types=payload.source_types or None,
        domains=payload.domains or None,
        courts=payload.courts or None,
        proceeding_type=payload.proceeding_type,
        date_from=payload.date_from,
        date_to=payload.date_to,
    )

    if await _corpus_is_empty(session):
        return LegalSearchResponse(
            corpus_empty=True,
            notice=_EMPTY_CORPUS_NOTICE,
            limit=payload.limit,
            offset=payload.offset,
        )

    retriever = LegalRetriever(session)
    sources, total = await retriever.search_sources(
        payload.query, filters=filters, limit=payload.limit, offset=payload.offset
    )

    passages: list[LegalPassageOut] = []
    if payload.semantic and payload.query and payload.query.strip():
        context = await rag.retrieve(payload.query, filters=filters, final_k=8)
        by_id = {str(s.id): s for s in sources}
        for chunk in context.chunks:
            source = by_id.get(chunk.source_id)
            if source is None:
                source = (
                    await session.execute(
                        select(LegalSource).where(LegalSource.id == chunk.source_id)
                    )
                ).scalar_one_or_none()
            if source is None:
                continue
            passages.append(
                LegalPassageOut(
                    source=_to_source_out(source),
                    heading=chunk.heading,
                    snippet=" ".join(chunk.content.split())[:400],
                    score=round(chunk.final_score, 4),
                )
            )

    return LegalSearchResponse(
        sources=[_to_source_out(s) for s in sources],
        passages=passages,
        total=total,
        limit=payload.limit,
        offset=payload.offset,
    )


@router.get(
    "/sources/{citation_key}",
    response_model=LegalSourceOut,
    summary="פרטי מקור משפטי",
)
async def get_source(citation_key: str, _user: CurrentUser, session: SessionDep) -> LegalSourceOut:
    """Resolve a citation key from an answer back to its source record."""
    from app.core.errors import NotFoundError

    source = (
        await session.execute(select(LegalSource).where(LegalSource.citation_key == citation_key))
    ).scalar_one_or_none()
    if source is None:
        raise NotFoundError("המקור המשפטי לא נמצא במאגר")
    return _to_source_out(source)


@router.get("/stats", summary="סטטיסטיקת המאגר המשפטי")
async def corpus_stats(_user: CurrentUser, session: SessionDep) -> dict[str, object]:
    """Corpus composition — what has actually been loaded."""
    rows = (
        await session.execute(
            select(LegalSource.source_type, func.count()).group_by(LegalSource.source_type)
        )
    ).all()
    chunk_total = (
        await session.execute(select(func.coalesce(func.sum(LegalSource.chunk_count), 0)))
    ).scalar_one()

    by_type = {str(source_type): int(count) for source_type, count in rows}
    return {
        "sources_total": sum(by_type.values()),
        "chunks_total": int(chunk_total),
        "by_type": by_type,
        "corpus_empty": sum(by_type.values()) == 0,
    }


@router.get("/suggest", summary="הצעות חיפוש")
async def suggest(
    _user: CurrentUser,
    session: SessionDep,
    q: Annotated[str, Query(min_length=2, max_length=100)],
    limit: Annotated[int, Query(ge=1, le=20)] = 8,
) -> list[dict[str, str]]:
    """Title autocomplete over the corpus."""
    pattern = f"%{q.strip()}%"
    rows = (
        await session.execute(
            select(LegalSource.citation_key, LegalSource.title, LegalSource.source_type)
            .where(LegalSource.title.ilike(pattern))
            .order_by(LegalSource.title)
            .limit(limit)
        )
    ).all()
    return [
        {"citation_key": key, "title": title, "source_type": str(source_type)}
        for key, title, source_type in rows
    ]
