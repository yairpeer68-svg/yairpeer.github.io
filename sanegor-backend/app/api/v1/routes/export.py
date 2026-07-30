"""Export endpoints: conversations and generated documents to PDF/DOCX/MD."""

from __future__ import annotations

import asyncio
import urllib.parse
from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from fastapi.responses import Response

from app.api.deps import (
    CurrentUser,
    get_audit_service,
    get_chat_service,
    get_drafting_service,
    rate_limit_default,
)
from app.core.errors import ValidationError
from app.db.models.audit import AuditAction
from app.schemas.documents import ExportRequest
from app.services.ai.prompts import DISCLAIMER_HE
from app.services.audit import AuditService
from app.services.chat import ChatService
from app.services.documents.generator import DocxExporter, ExportMetadata, PdfExporter
from app.services.legal.drafting import DraftingService

router = APIRouter(
    prefix="/export", tags=["export"], dependencies=[Depends(rate_limit_default)]
)

ChatDep = Annotated[ChatService, Depends(get_chat_service)]
DraftDep = Annotated[DraftingService, Depends(get_drafting_service)]
AuditDep = Annotated[AuditService, Depends(get_audit_service)]

_MEDIA_TYPES = {
    "pdf": "application/pdf",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "md": "text/markdown; charset=utf-8",
    "txt": "text/plain; charset=utf-8",
}


@router.post("", summary="ייצוא מסמך", response_class=Response)
async def export_document(
    payload: ExportRequest,
    request: Request,
    user: CurrentUser,
    chat: ChatDep,
    drafting: DraftDep,
    audit: AuditDep,
) -> Response:
    """Export a conversation, a generated document, or supplied content.

    Exactly one source must be given. PDF and DOCX are rendered right-to-left;
    ``md``/``txt`` return the text unchanged.
    """
    sources = [payload.conversation_id, payload.generated_document_id, payload.content]
    if sum(1 for s in sources if s) != 1:
        raise ValidationError(
            "יש לציין בדיוק מקור אחד לייצוא: שיחה, מסמך שנוצר, או תוכן"
        )

    if payload.conversation_id:
        title, body = await _conversation_body(chat, payload.conversation_id, str(user.id))
    elif payload.generated_document_id:
        record = await drafting.get_generated(
            payload.generated_document_id, user_id=str(user.id)
        )
        title, body = record.title, record.body_markdown
    else:
        title, body = (payload.title or "מסמך"), (payload.content or "")

    title = payload.title or title
    meta = ExportMetadata(
        title=title,
        subtitle=f"נוצר בסנגור · {date.today().isoformat()}",
        created=date.today(),
        include_disclaimer=payload.include_disclaimer,
    )

    # Rendering is CPU-bound and synchronous; keep it off the event loop.
    if payload.format == "pdf":
        data = await asyncio.to_thread(PdfExporter().render, body, meta)
    elif payload.format == "docx":
        data = await asyncio.to_thread(DocxExporter().render, body, meta)
    else:
        text = f"# {title}\n\n{body}"
        if payload.include_disclaimer:
            text += f"\n\n---\n\n{DISCLAIMER_HE}\n"
        data = text.encode("utf-8")

    await audit.record(
        AuditAction.EXPORT,
        user_id=str(user.id),
        resource_type="export",
        resource_id=payload.conversation_id or payload.generated_document_id,
        metadata={"format": payload.format},
    )

    filename = _safe_filename(title, payload.format)
    return Response(
        content=data,
        media_type=_MEDIA_TYPES[payload.format],
        headers={
            "Content-Disposition": f"attachment; filename*=UTF-8''{filename}",
            "X-Content-Type-Options": "nosniff",
        },
    )


async def _conversation_body(
    chat: ChatService, conversation_id: str, user_id: str
) -> tuple[str, str]:
    """Render a conversation as markdown, including its sources."""
    conversation, messages, _ = await chat.list_messages(
        conversation_id, user_id=user_id, limit=200
    )
    lines: list[str] = []
    for message in messages:
        speaker = "שאלה" if str(message.role) == "user" else "תשובה"
        lines += [f"## {speaker}", "", message.content.strip(), ""]
        if message.citations:
            lines.append("**מקורות:**")
            lines += [
                f"- [מקור {c.get('index')}] {c.get('title')}"
                + (f" — {c['heading']}" if c.get("heading") else "")
                for c in message.citations
            ]
            lines.append("")
        lines.append("---")
        lines.append("")
    return conversation.title, "\n".join(lines)


def _safe_filename(title: str, extension: str) -> str:
    """Build a URL-encoded, filesystem-safe download name."""
    cleaned = "".join(ch for ch in title if ch.isalnum() or ch in " -_()״׳").strip()
    cleaned = (cleaned or "sanegor")[:80].replace(" ", "_")
    return urllib.parse.quote(f"{cleaned}.{extension}")
