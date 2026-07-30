"""Retrieval-augmented generation over the Israeli legal corpus."""

from app.services.rag.ingest import CorpusIngestor, SourceDraft, chunk_text
from app.services.rag.pipeline import GroundedContext, RagPipeline
from app.services.rag.ranker import rank
from app.services.rag.retriever import LegalRetriever, RetrievalFilters, RetrievedChunk

__all__ = [
    "CorpusIngestor",
    "GroundedContext",
    "LegalRetriever",
    "RagPipeline",
    "RetrievalFilters",
    "RetrievedChunk",
    "SourceDraft",
    "chunk_text",
    "rank",
]
