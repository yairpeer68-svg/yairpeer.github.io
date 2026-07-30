"""Document, analysis, drafting and export schemas."""

from __future__ import annotations

from datetime import datetime
from typing import Annotated, Any, Literal

from pydantic import Field, field_validator

from app.schemas.common import ApiModel, CitationOut


class DocumentOut(ApiModel):
    id: str
    filename: str
    content_type: str
    size_bytes: int
    status: str
    page_count: int | None = None
    word_count: int | None = None
    language: str | None = None
    used_ocr: bool = False
    error: str | None = None
    created_at: datetime

    @field_validator("id", "status", mode="before")
    @classmethod
    def _stringify(cls, value: object) -> str:
        return getattr(value, "value", None) or str(value)


class UploadResponse(ApiModel):
    document: DocumentOut
    warnings: list[str] = []


class DocumentTextResponse(ApiModel):
    document_id: str
    text: str
    word_count: int
    language: str | None = None
    used_ocr: bool = False


# ------------------------------------------------------------------- analysis
class AnalysisRequest(ApiModel):
    document_id: str
    focus: Annotated[str, Field(max_length=500)] | None = None
    refresh: bool = False


class RiskItem(ApiModel):
    severity: Literal["high", "medium", "low"] = "medium"
    title: str = ""
    clause: str | None = None
    detail: str = ""
    recommendation: str | None = None


class AnalysisResponse(ApiModel):
    document_id: str
    kind: str
    summary: str
    payload: dict[str, Any]
    citations: list[CitationOut] = []
    complexity_score: int | None = None
    risk_score: int | None = None
    model: str = ""
    cached: bool = False
    disclaimer: str


# ------------------------------------------------------------------ templates
class TemplateFieldOut(ApiModel):
    key: str
    label: str
    type: str
    required: bool
    hint: str | None = None
    options: list[str] = []


class TemplateOut(ApiModel):
    key: str
    name: str
    description: str
    category: str
    icon: str
    fields: list[TemplateFieldOut]
    required_sections: list[str] = []
    legal_notes: list[str] = []


# ------------------------------------------------------------------- drafting
class GenerateRequest(ApiModel):
    """Fill a template. ``inputs`` is validated against the template itself."""

    template_key: Annotated[str, Field(min_length=1, max_length=64)]
    inputs: dict[str, Any] = {}

    @field_validator("inputs")
    @classmethod
    def _bound_inputs(cls, value: dict[str, Any]) -> dict[str, Any]:
        if len(value) > 60:
            raise ValueError("נשלחו יותר מדי שדות")
        for key, item in value.items():
            if len(str(key)) > 64:
                raise ValueError("שם שדה ארוך מדי")
            if isinstance(item, str) and len(item) > 8_000:
                raise ValueError(f"הערך של השדה '{key}' ארוך מדי")
        return value


class GeneratedDocumentOut(ApiModel):
    id: str
    category: str
    template_key: str
    title: str
    body_markdown: str
    citations: list[CitationOut] = []
    missing_fields: list[str] = []
    model: str | None = None
    created_at: datetime | None = None
    disclaimer: str

    @field_validator("id", mode="before")
    @classmethod
    def _stringify(cls, value: object) -> str:
        return str(value)


# --------------------------------------------------------------------- export
ExportFormat = Literal["pdf", "docx", "md", "txt"]


class ExportRequest(ApiModel):
    """Export a conversation, a generated document or raw content."""

    format: ExportFormat = "pdf"
    title: Annotated[str, Field(max_length=200)] | None = None
    conversation_id: str | None = None
    generated_document_id: str | None = None
    content: Annotated[str, Field(max_length=200_000)] | None = None
    include_disclaimer: bool = True

    @field_validator("content")
    @classmethod
    def _clean(cls, value: str | None) -> str | None:
        return value.strip() if value else value
