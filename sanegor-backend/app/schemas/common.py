"""Shared schema primitives."""

from __future__ import annotations

from typing import Annotated, Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field

T = TypeVar("T")


class ApiModel(BaseModel):
    """Base for every response model."""

    model_config = ConfigDict(from_attributes=True, populate_by_name=True)


class PaginationParams(BaseModel):
    """Standard limit/offset paging."""

    limit: Annotated[int, Field(ge=1, le=100)] = 20
    offset: Annotated[int, Field(ge=0, le=100_000)] = 0


class Page(ApiModel, Generic[T]):
    """A page of results plus the total count."""

    items: list[T]
    total: int
    limit: int
    offset: int

    @property
    def has_more(self) -> bool:
        return self.offset + len(self.items) < self.total


class MessageResponse(ApiModel):
    """Simple acknowledgement."""

    message: str
    success: bool = True


class ErrorDetail(ApiModel):
    """Body of the standard error envelope."""

    code: str
    message: str
    details: dict = Field(default_factory=dict)
    request_id: str | None = None


class ErrorResponse(ApiModel):
    """The envelope every failure is returned in."""

    error: ErrorDetail


class CitationOut(ApiModel):
    """A source backing an answer. Always resolves to a corpus row."""

    index: int
    citation_key: str
    title: str
    source_type: str
    heading: str | None = None
    court: str | None = None
    case_number: str | None = None
    published_at: str | None = None
    url: str | None = None
    snippet: str = ""
    score: float = 0.0
