"""Fusion and re-ranking of retrieval candidates.

Dense and lexical candidate lists are merged with Reciprocal Rank Fusion, then
adjusted by domain heuristics that reflect how Israeli legal material is
actually weighted:

* primary legislation outranks a district ruling on the same point;
* Supreme Court rulings outrank lower instances;
* recency matters for case law but barely at all for a statute;
* consecutive chunks of the same source are de-duplicated so one long ruling
  cannot crowd out every other authority.
"""

from __future__ import annotations

from datetime import date

from app.core.logging import get_logger
from app.services.rag.retriever import RetrievedChunk

logger = get_logger(__name__)

# RRF constant. 60 is the value from the original Cormack et al. paper and is
# insensitive enough that tuning it is rarely worth it.
_RRF_K = 60

_SOURCE_TYPE_WEIGHT: dict[str, float] = {
    "legislation": 1.15,
    "regulation": 1.08,
    "ruling": 1.00,
    "guideline": 0.92,
    "form": 0.85,
}

_COURT_WEIGHT: dict[str, float] = {
    "supreme": 1.12,
    "labor_national": 1.08,
    "district": 1.02,
    "administrative": 1.02,
    "labor_regional": 0.98,
    "family": 0.98,
    "magistrate": 0.95,
    "traffic": 0.90,
    "other": 0.90,
}


def _recency_weight(published: date | None, source_type: str) -> float:
    """Mild recency preference, applied to case law only."""
    if published is None or source_type != "ruling":
        return 1.0
    years = max((date.today() - published).days / 365.25, 0.0)
    if years <= 3:
        return 1.06
    if years <= 10:
        return 1.0
    if years <= 25:
        return 0.96
    return 0.92


def fuse(
    dense: list[RetrievedChunk],
    lexical: list[RetrievedChunk],
    *,
    dense_weight: float = 0.65,
    lexical_weight: float = 0.35,
) -> list[RetrievedChunk]:
    """Merge two ranked lists with weighted Reciprocal Rank Fusion."""
    merged: dict[str, RetrievedChunk] = {}
    scores: dict[str, float] = {}

    for weight, ranked in ((dense_weight, dense), (lexical_weight, lexical)):
        for rank, chunk in enumerate(ranked, start=1):
            existing = merged.get(chunk.chunk_id)
            if existing is None:
                merged[chunk.chunk_id] = chunk
            else:
                # Keep whichever component score each list contributed.
                existing.dense_score = max(existing.dense_score, chunk.dense_score)
                existing.lexical_score = max(existing.lexical_score, chunk.lexical_score)
            scores[chunk.chunk_id] = scores.get(chunk.chunk_id, 0.0) + weight / (_RRF_K + rank)

    # Normalise RRF output into a comparable 0..1 band before boosting.
    if scores:
        top = max(scores.values()) or 1.0
        for chunk_id, score in scores.items():
            merged[chunk_id].final_score = score / top

    return sorted(merged.values(), key=lambda c: c.final_score, reverse=True)


def apply_authority_boosts(chunks: list[RetrievedChunk]) -> list[RetrievedChunk]:
    """Re-weight by source type, court level and recency."""
    for chunk in chunks:
        weight = _SOURCE_TYPE_WEIGHT.get(chunk.source_type, 1.0)
        if chunk.court:
            weight *= _COURT_WEIGHT.get(chunk.court, 1.0)
        weight *= _recency_weight(chunk.published_at, chunk.source_type)
        chunk.final_score *= weight
    return sorted(chunks, key=lambda c: c.final_score, reverse=True)


def diversify(chunks: list[RetrievedChunk], *, max_per_source: int = 2) -> list[RetrievedChunk]:
    """Cap how many chunks any single source may contribute."""
    seen: dict[str, int] = {}
    kept: list[RetrievedChunk] = []
    for chunk in chunks:
        count = seen.get(chunk.source_id, 0)
        if count >= max_per_source:
            continue
        seen[chunk.source_id] = count + 1
        kept.append(chunk)
    return kept


def rank(
    dense: list[RetrievedChunk],
    lexical: list[RetrievedChunk],
    *,
    final_k: int,
    min_score: float,
    max_per_source: int = 2,
) -> list[RetrievedChunk]:
    """Full ranking pipeline: fuse → boost → diversify → threshold → cut.

    Args:
        dense: Candidates from vector search, best first.
        lexical: Candidates from keyword search, best first.
        final_k: Number of chunks that may enter the prompt.
        min_score: Chunks below this are dropped rather than padded in —
            an irrelevant "source" is worse than no source.
        max_per_source: Chunk cap per legal source.
    """
    fused = fuse(dense, lexical)
    boosted = apply_authority_boosts(fused)
    diverse = diversify(boosted, max_per_source=max_per_source)
    selected = [chunk for chunk in diverse if chunk.final_score >= min_score][:final_k]

    logger.debug(
        "rag_ranked",
        dense=len(dense),
        lexical=len(lexical),
        fused=len(fused),
        selected=len(selected),
        top_score=round(selected[0].final_score, 4) if selected else 0.0,
    )
    return selected
