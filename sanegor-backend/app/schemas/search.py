"""Legal-search schemas."""

from __future__ import annotations

from datetime import date
from typing import Annotated

from pydantic import Field

from app.db.models.legal_source import CourtLevel, LegalDomain, SourceType
from app.schemas.common import ApiModel


class LegalSearchRequest(ApiModel):
    """Search the ingested corpus.

    Every filter maps to a column on ``legal_sources``; results therefore
    always correspond to material an operator actually loaded.
    """

    query: Annotated[str, Field(max_length=500)] | None = None
    source_types: list[SourceType] = []
    domains: list[LegalDomain] = []
    courts: list[CourtLevel] = []
    proceeding_type: Annotated[str, Field(max_length=80)] | None = None
    date_from: date | None = None
    date_to: date | None = None
    semantic: bool = True
    limit: Annotated[int, Field(ge=1, le=50)] = 20
    offset: Annotated[int, Field(ge=0, le=10_000)] = 0


class LegalSourceOut(ApiModel):
    id: str
    citation_key: str
    title: str
    short_title: str | None = None
    source_type: str
    domain: str
    case_number: str | None = None
    court: str | None = None
    judges: list[str] = []
    parties: str | None = None
    proceeding_type: str | None = None
    section_range: str | None = None
    published_at: date | None = None
    source_url: str | None = None
    publisher: str = ""
    chunk_count: int = 0


class LegalPassageOut(ApiModel):
    """A matched passage, with the score that surfaced it."""

    source: LegalSourceOut
    heading: str | None = None
    snippet: str
    score: float


class LegalSearchResponse(ApiModel):
    sources: list[LegalSourceOut] = []
    passages: list[LegalPassageOut] = []
    total: int = 0
    limit: int = 20
    offset: int = 0
    corpus_empty: bool = False
    notice: str | None = None
