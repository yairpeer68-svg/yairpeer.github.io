"""Corpus ingestion: chunking and embedding of legal material.

Ingestion is deliberately an operator action, not something a user request can
trigger.  Every source must carry provenance (``publisher`` and ideally
``source_url``), because the citation shown in the app is only as trustworthy
as what was loaded here.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field
from datetime import date

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.logging import get_logger
from app.db.models.legal_source import (
    CourtLevel,
    LegalChunk,
    LegalDomain,
    LegalSource,
    SourceType,
)
from app.services.ai.embeddings import EmbeddingProvider
from app.services.ai.context import count_tokens

logger = get_logger(__name__)

# Israeli statutes and rulings are structured by these markers; splitting on
# them keeps a section intact inside one chunk far more often than a blind
# character split would.
_SECTION_PATTERNS = [
    r"^\s*(?:סימן|פרק|חלק)\s+[֐-׿\w'\"]+\s*[:.\-–]?\s*$",
    r"^\s*\d+[א-ת]?\s*\.\s",          # "12. " / "12א. "
    r"^\s*סעיף\s+\d+",
    r"^\s*תקנה\s+\d+",
]
_SECTION_RE = re.compile("|".join(_SECTION_PATTERNS), re.MULTILINE)


@dataclass(slots=True)
class SourceDraft:
    """Input record for one legal source to ingest."""

    citation_key: str
    title: str
    content: str
    source_type: SourceType
    domain: LegalDomain = LegalDomain.OTHER
    short_title: str | None = None
    case_number: str | None = None
    court: CourtLevel | None = None
    judges: list[str] = field(default_factory=list)
    parties: str | None = None
    proceeding_type: str | None = None
    section_range: str | None = None
    amendment: str | None = None
    published_at: date | None = None
    source_url: str | None = None
    publisher: str = ""
    extra: dict = field(default_factory=dict)

    def checksum(self) -> str:
        return hashlib.sha256(self.content.encode("utf-8")).hexdigest()


def split_into_sections(text: str) -> list[tuple[str | None, str]]:
    """Split ``text`` on Israeli legal structure markers.

    Returns:
        ``(heading, body)`` pairs. ``heading`` is ``None`` for a preamble that
        appears before the first marker.
    """
    lines = text.splitlines()
    sections: list[tuple[str | None, list[str]]] = []
    current_heading: str | None = None
    current: list[str] = []

    for line in lines:
        if _SECTION_RE.match(line) and current:
            sections.append((current_heading, current))
            current_heading = line.strip()[:300]
            current = [line]
        elif _SECTION_RE.match(line):
            current_heading = line.strip()[:300]
            current = [line]
        else:
            current.append(line)

    if current:
        sections.append((current_heading, current))
    return [(heading, "\n".join(body).strip()) for heading, body in sections if any(body)]


def chunk_text(
    text: str, *, max_tokens: int, overlap_tokens: int
) -> list[tuple[str | None, str]]:
    """Chunk ``text`` for embedding, preferring section boundaries.

    Sections shorter than the budget are emitted whole. Oversized sections are
    split on paragraph boundaries with a token overlap so a rule that spans a
    boundary still appears intact in at least one chunk.
    """
    chunks: list[tuple[str | None, str]] = []

    for heading, section in split_into_sections(text):
        if not section.strip():
            continue
        if count_tokens(section) <= max_tokens:
            chunks.append((heading, section))
            continue

        paragraphs = [p.strip() for p in re.split(r"\n\s*\n", section) if p.strip()]
        buffer: list[str] = []
        buffer_tokens = 0

        for paragraph in paragraphs:
            para_tokens = count_tokens(paragraph)
            if buffer and buffer_tokens + para_tokens > max_tokens:
                chunks.append((heading, "\n\n".join(buffer)))
                # Carry the tail of the previous chunk forward as overlap.
                overlap: list[str] = []
                carried = 0
                for previous in reversed(buffer):
                    previous_tokens = count_tokens(previous)
                    if carried + previous_tokens > overlap_tokens:
                        break
                    overlap.insert(0, previous)
                    carried += previous_tokens
                buffer = [*overlap, paragraph]
                buffer_tokens = carried + para_tokens
            else:
                buffer.append(paragraph)
                buffer_tokens += para_tokens

        if buffer:
            chunks.append((heading, "\n\n".join(buffer)))

    return chunks


class CorpusIngestor:
    """Writes sources and their embedded chunks into the database."""

    def __init__(
        self,
        session: AsyncSession,
        embeddings: EmbeddingProvider,
        *,
        chunk_tokens: int = 700,
        overlap_tokens: int = 100,
    ) -> None:
        self._session = session
        self._embeddings = embeddings
        self._chunk_tokens = chunk_tokens
        self._overlap_tokens = overlap_tokens

    async def ingest(self, draft: SourceDraft, *, force: bool = False) -> LegalSource:
        """Insert or update one source, re-embedding its chunks.

        Re-ingesting unchanged content is a no-op unless ``force`` is set, so
        a nightly sync does not re-pay for embeddings it already has.
        """
        checksum = draft.checksum()
        existing = (
            await self._session.execute(
                select(LegalSource).where(LegalSource.citation_key == draft.citation_key)
            )
        ).scalar_one_or_none()

        if existing is not None and existing.checksum_sha256 == checksum and not force:
            logger.info("corpus_unchanged", citation_key=draft.citation_key)
            return existing

        source = existing or LegalSource(citation_key=draft.citation_key)
        source.title = draft.title
        source.short_title = draft.short_title
        source.source_type = draft.source_type
        source.domain = draft.domain
        source.case_number = draft.case_number
        source.court = draft.court
        source.judges = draft.judges
        source.parties = draft.parties
        source.proceeding_type = draft.proceeding_type
        source.section_range = draft.section_range
        source.amendment = draft.amendment
        source.published_at = draft.published_at
        source.source_url = draft.source_url
        source.publisher = draft.publisher
        source.checksum_sha256 = checksum
        source.extra = draft.extra

        if existing is None:
            self._session.add(source)
        await self._session.flush()

        # Replace chunks wholesale — partial updates would leave stale vectors.
        await self._session.execute(
            delete(LegalChunk).where(LegalChunk.source_id == source.id)
        )

        pieces = chunk_text(
            draft.content,
            max_tokens=self._chunk_tokens,
            overlap_tokens=self._overlap_tokens,
        )
        if not pieces:
            logger.warning("corpus_empty_source", citation_key=draft.citation_key)
            source.chunk_count = 0
            return source

        # Prefix the source title onto the embedded text so a chunk carries its
        # own context; retrieval on "חוק השכירות" then matches its sections.
        embed_inputs = [
            f"{draft.title}\n{heading or ''}\n{body}".strip() for heading, body in pieces
        ]
        vectors = await self._embeddings.embed(embed_inputs)

        for ordinal, ((heading, body), vector) in enumerate(
            zip(pieces, vectors, strict=True)
        ):
            self._session.add(
                LegalChunk(
                    source_id=source.id,
                    ordinal=ordinal,
                    heading=heading,
                    content=body,
                    token_count=count_tokens(body),
                    embedding=vector,
                )
            )

        source.chunk_count = len(pieces)
        await self._session.flush()
        logger.info(
            "corpus_ingested", citation_key=draft.citation_key, chunks=len(pieces)
        )
        return source

    async def ingest_many(
        self, drafts: list[SourceDraft], *, force: bool = False
    ) -> list[LegalSource]:
        """Ingest a batch sequentially, keeping memory flat."""
        return [await self.ingest(draft, force=force) for draft in drafts]
