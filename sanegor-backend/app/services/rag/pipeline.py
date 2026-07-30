"""The RAG pipeline: question in, grounded context out.

Steps 1-5 of the specified flow live here (embed → search → rank → select →
build prompt).  Steps 6-9 (call the model, stream, cite, persist) belong to the
chat service, which consumes the :class:`GroundedContext` produced here.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.logging import get_logger
from app.services.ai.citations import Citation
from app.services.ai.context import truncate_to_tokens
from app.services.ai.embeddings import EmbeddingProvider
from app.services.ai.prompts import SourceBlock
from app.services.rag.ranker import rank
from app.services.rag.retriever import LegalRetriever, RetrievalFilters, RetrievedChunk

logger = get_logger(__name__)

# Hard ceiling per chunk so one very long section cannot eat the whole budget.
_MAX_CHUNK_TOKENS = 900


@dataclass(slots=True)
class GroundedContext:
    """Everything retrieval produced for a single question."""

    blocks: list[SourceBlock] = field(default_factory=list)
    citations_by_index: dict[int, Citation] = field(default_factory=dict)
    chunks: list[RetrievedChunk] = field(default_factory=list)
    latency_ms: int = 0
    degraded: bool = False
    degraded_reason: str | None = None

    @property
    def valid_indices(self) -> set[int]:
        return {block.index for block in self.blocks}

    @property
    def is_empty(self) -> bool:
        return not self.blocks


class RagPipeline:
    """Turns a natural-language question into grounded, citable context."""

    def __init__(
        self,
        session: AsyncSession,
        embeddings: EmbeddingProvider,
        settings: Settings,
    ) -> None:
        self._retriever = LegalRetriever(session)
        self._embeddings = embeddings
        self._settings = settings

    async def retrieve(
        self,
        question: str,
        *,
        filters: RetrievalFilters | None = None,
        top_k: int | None = None,
        final_k: int | None = None,
    ) -> GroundedContext:
        """Run retrieval for ``question``.

        Retrieval never raises into the request: if the vector store or the
        embedding service is unavailable the pipeline returns an empty,
        ``degraded`` context.  The prompt then instructs the model to answer
        at a general level without citing anything — a degraded answer with no
        references is acceptable, a fabricated reference is not.
        """
        if not self._settings.rag_enabled:
            return GroundedContext(degraded=True, degraded_reason="rag_disabled")

        question = question.strip()
        if not question:
            return GroundedContext()

        started = time.perf_counter()
        top_k = top_k or self._settings.rag_top_k
        final_k = final_k or self._settings.rag_final_k

        try:
            embedding = await self._embeddings.embed_one(question)
            dense = await self._retriever.dense_search(embedding, limit=top_k, filters=filters)
        except Exception as exc:
            logger.warning("rag_dense_failed", error=str(exc))
            dense = []
            degraded_reason: str | None = "dense_unavailable"
        else:
            degraded_reason = None

        try:
            lexical = await self._retriever.lexical_search(question, limit=top_k, filters=filters)
        except Exception as exc:
            logger.warning("rag_lexical_failed", error=str(exc))
            lexical = []
            degraded_reason = degraded_reason or "lexical_unavailable"

        selected = rank(
            dense,
            lexical,
            final_k=final_k,
            min_score=self._settings.rag_min_score,
        )

        context = self._build_context(selected)
        context.latency_ms = int((time.perf_counter() - started) * 1000)
        context.degraded = degraded_reason is not None
        context.degraded_reason = degraded_reason

        logger.info(
            "rag_retrieved",
            question_chars=len(question),
            dense=len(dense),
            lexical=len(lexical),
            selected=len(selected),
            latency_ms=context.latency_ms,
            degraded=context.degraded,
        )
        return context

    def _build_context(self, chunks: list[RetrievedChunk]) -> GroundedContext:
        """Convert ranked chunks into prompt blocks and citation metadata."""
        context = GroundedContext(chunks=chunks)
        for index, chunk in enumerate(chunks, start=1):
            content = truncate_to_tokens(chunk.content, _MAX_CHUNK_TOKENS)
            published = chunk.published_at.isoformat() if chunk.published_at else None

            context.blocks.append(
                SourceBlock(
                    index=index,
                    citation_key=chunk.citation_key,
                    title=chunk.title,
                    heading=chunk.heading,
                    content=content,
                    published=published,
                )
            )
            context.citations_by_index[index] = Citation(
                index=index,
                citation_key=chunk.citation_key,
                title=chunk.title,
                source_type=chunk.source_type,
                heading=chunk.heading,
                court=chunk.court,
                case_number=chunk.case_number,
                published_at=published,
                url=chunk.source_url,
                snippet=_snippet(content),
                score=round(chunk.final_score, 4),
            )
        return context


def _snippet(content: str, max_chars: int = 320) -> str:
    """Short preview shown on the source card in the app."""
    flat = " ".join(content.split())
    if len(flat) <= max_chars:
        return flat
    return flat[:max_chars].rsplit(" ", 1)[0] + "…"
