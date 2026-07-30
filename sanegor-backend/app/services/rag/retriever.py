"""Vector + lexical retrieval over the legal corpus.

Two independent recall paths are combined:

* **Dense** — pgvector cosine distance (``<=>``) against ``legal_chunks``.
  An IVFFlat index makes this sub-linear; see the initial migration.
* **Lexical** — PostgreSQL full-text search with the ``simple`` configuration.
  Hebrew has no bundled stemmer, and ``simple`` (no stemming, no stop-words)
  is the honest choice: it matches surface forms exactly, which for statute
  names and case numbers is precisely what we want.

Dense search alone misses exact identifiers such as ``ע"א 1234/56``; lexical
search alone misses paraphrase.  The ranker fuses them.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import Any

from sqlalchemy import Select, func, or_, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.logging import get_logger
from app.db.models.legal_source import (
    CourtLevel,
    LegalChunk,
    LegalDomain,
    LegalSource,
    SourceType,
)
from app.services.ai.embeddings import Vector, cosine_similarity

logger = get_logger(__name__)


@dataclass(slots=True)
class RetrievalFilters:
    """Optional narrowing applied before scoring."""

    source_types: list[SourceType] | None = None
    domains: list[LegalDomain] | None = None
    courts: list[CourtLevel] | None = None
    proceeding_type: str | None = None
    date_from: date | None = None
    date_to: date | None = None

    @property
    def is_empty(self) -> bool:
        return not any(
            (
                self.source_types,
                self.domains,
                self.courts,
                self.proceeding_type,
                self.date_from,
                self.date_to,
            )
        )


@dataclass(slots=True)
class RetrievedChunk:
    """A candidate chunk together with the scores that produced it."""

    chunk_id: str
    source_id: str
    ordinal: int
    heading: str | None
    content: str
    citation_key: str
    title: str
    source_type: str
    court: str | None
    case_number: str | None
    published_at: date | None
    source_url: str | None
    dense_score: float = 0.0
    lexical_score: float = 0.0
    final_score: float = 0.0

    @classmethod
    def from_row(cls, chunk: LegalChunk, source: LegalSource, **scores: float) -> RetrievedChunk:
        return cls(
            chunk_id=str(chunk.id),
            source_id=str(source.id),
            ordinal=chunk.ordinal,
            heading=chunk.heading,
            content=chunk.content,
            citation_key=source.citation_key,
            title=source.title,
            source_type=str(source.source_type),
            court=str(source.court) if source.court else None,
            case_number=source.case_number,
            published_at=source.published_at,
            source_url=source.source_url,
            **scores,  # type: ignore[arg-type]
        )


class LegalRetriever:
    """Runs the dense and lexical searches over ``legal_chunks``."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    @property
    def _is_postgres(self) -> bool:
        return self._session.bind is not None and self._session.bind.dialect.name == "postgresql"

    def _apply_filters(self, stmt: Select[Any], filters: RetrievalFilters | None) -> Select[Any]:
        if filters is None or filters.is_empty:
            return stmt
        if filters.source_types:
            stmt = stmt.where(LegalSource.source_type.in_([t.value for t in filters.source_types]))
        if filters.domains:
            stmt = stmt.where(LegalSource.domain.in_([d.value for d in filters.domains]))
        if filters.courts:
            stmt = stmt.where(LegalSource.court.in_([c.value for c in filters.courts]))
        if filters.proceeding_type:
            stmt = stmt.where(LegalSource.proceeding_type == filters.proceeding_type)
        if filters.date_from:
            stmt = stmt.where(LegalSource.published_at >= filters.date_from)
        if filters.date_to:
            stmt = stmt.where(LegalSource.published_at <= filters.date_to)
        return stmt

    # ------------------------------------------------------------------- dense
    async def dense_search(
        self,
        embedding: Vector,
        *,
        limit: int,
        filters: RetrievalFilters | None = None,
    ) -> list[RetrievedChunk]:
        """Nearest neighbours by cosine distance."""
        if not embedding:
            return []
        if self._is_postgres:
            return await self._dense_search_pg(embedding, limit, filters)
        return await self._dense_search_python(embedding, limit, filters)

    async def _dense_search_pg(
        self, embedding: Vector, limit: int, filters: RetrievalFilters | None
    ) -> list[RetrievedChunk]:
        # `<=>` is cosine distance in [0, 2]; similarity = 1 - distance.
        distance = LegalChunk.embedding.op("<=>")(embedding)  # type: ignore[attr-defined]
        stmt = (
            select(LegalChunk, LegalSource, distance.label("distance"))
            .join(LegalSource, LegalChunk.source_id == LegalSource.id)
            .where(LegalChunk.embedding.isnot(None))
            .order_by(distance)
            .limit(limit)
        )
        stmt = self._apply_filters(stmt, filters)
        rows = (await self._session.execute(stmt)).all()
        return [
            RetrievedChunk.from_row(chunk, source, dense_score=max(0.0, 1.0 - float(dist)))
            for chunk, source, dist in rows
        ]

    async def _dense_search_python(
        self, embedding: Vector, limit: int, filters: RetrievalFilters | None
    ) -> list[RetrievedChunk]:
        """In-process cosine scan.

        Only used by the SQLite test-suite, where the corpus is a handful of
        rows; on PostgreSQL the indexed path above always runs.
        """
        stmt = (
            select(LegalChunk, LegalSource)
            .join(LegalSource, LegalChunk.source_id == LegalSource.id)
            .where(LegalChunk.embedding.isnot(None))
            .limit(2_000)
        )
        stmt = self._apply_filters(stmt, filters)
        rows = (await self._session.execute(stmt)).all()
        scored = [
            RetrievedChunk.from_row(
                chunk, source, dense_score=cosine_similarity(embedding, chunk.embedding or [])
            )
            for chunk, source in rows
        ]
        scored.sort(key=lambda c: c.dense_score, reverse=True)
        return scored[:limit]

    # ----------------------------------------------------------------- lexical
    async def lexical_search(
        self,
        query: str,
        *,
        limit: int,
        filters: RetrievalFilters | None = None,
    ) -> list[RetrievedChunk]:
        """Full-text (PostgreSQL) or LIKE-based (SQLite) keyword search."""
        query = query.strip()
        if not query:
            return []
        if self._is_postgres:
            return await self._lexical_search_pg(query, limit, filters)
        return await self._lexical_search_like(query, limit, filters)

    async def _lexical_search_pg(
        self, query: str, limit: int, filters: RetrievalFilters | None
    ) -> list[RetrievedChunk]:
        tsquery = func.websearch_to_tsquery("simple", query)
        rank = func.ts_rank_cd(func.to_tsvector("simple", LegalChunk.content), tsquery)
        stmt = (
            select(LegalChunk, LegalSource, rank.label("rank"))
            .join(LegalSource, LegalChunk.source_id == LegalSource.id)
            .where(
                or_(
                    func.to_tsvector("simple", LegalChunk.content).op("@@")(tsquery),
                    func.to_tsvector("simple", LegalSource.title).op("@@")(tsquery),
                )
            )
            .order_by(text("rank DESC"))
            .limit(limit)
        )
        stmt = self._apply_filters(stmt, filters)
        rows = (await self._session.execute(stmt)).all()
        if not rows:
            return []
        top = max(float(r) for _, _, r in rows) or 1.0
        return [
            RetrievedChunk.from_row(chunk, source, lexical_score=float(rank_value) / top)
            for chunk, source, rank_value in rows
        ]

    async def _lexical_search_like(
        self, query: str, limit: int, filters: RetrievalFilters | None
    ) -> list[RetrievedChunk]:
        terms = [t for t in query.split() if len(t) > 1][:6]
        if not terms:
            return []
        stmt = (
            select(LegalChunk, LegalSource)
            .join(LegalSource, LegalChunk.source_id == LegalSource.id)
            .where(or_(*[LegalChunk.content.ilike(f"%{term}%") for term in terms]))
            .limit(limit)
        )
        stmt = self._apply_filters(stmt, filters)
        rows = (await self._session.execute(stmt)).all()
        results: list[RetrievedChunk] = []
        for chunk, source in rows:
            hits = sum(1 for term in terms if term in chunk.content)
            results.append(RetrievedChunk.from_row(chunk, source, lexical_score=hits / len(terms)))
        results.sort(key=lambda c: c.lexical_score, reverse=True)
        return results

    # ------------------------------------------------------------------ browse
    async def search_sources(
        self,
        query: str | None,
        *,
        filters: RetrievalFilters | None = None,
        limit: int = 20,
        offset: int = 0,
    ) -> tuple[list[LegalSource], int]:
        """Metadata-level search used by the browse/search screens."""
        stmt = select(LegalSource)
        count_stmt = select(func.count()).select_from(LegalSource)

        if query and (needle := query.strip()):
            pattern = f"%{needle}%"
            condition = or_(
                LegalSource.title.ilike(pattern),
                LegalSource.short_title.ilike(pattern),
                LegalSource.case_number.ilike(pattern),
                LegalSource.citation_key.ilike(pattern),
                LegalSource.parties.ilike(pattern),
            )
            stmt = stmt.where(condition)
            count_stmt = count_stmt.where(condition)

        stmt = self._apply_filters(stmt, filters)
        if filters is not None and not filters.is_empty:
            count_stmt = self._apply_filters(count_stmt, filters)

        total = int((await self._session.execute(count_stmt)).scalar_one())
        stmt = (
            stmt.order_by(LegalSource.published_at.desc().nullslast(), LegalSource.title)
            .limit(limit)
            .offset(offset)
        )
        sources = list((await self._session.execute(stmt)).scalars().all())
        return sources, total
