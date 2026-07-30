"""Document upload, listing, download and deletion."""

from __future__ import annotations

import urllib.parse
from typing import Annotated

from fastapi import APIRouter, Depends, File, Query, Request, UploadFile, status
from fastapi.responses import Response

from app.api.deps import (
    CurrentUser,
    client_ip,
    get_audit_service,
    get_document_service,
    rate_limit_default,
    rate_limit_upload,
    user_agent,
)
from app.core.errors import PayloadTooLargeError
from app.core.logging import get_logger
from app.db.models.audit import AuditAction
from app.schemas.common import MessageResponse, Page
from app.schemas.documents import DocumentOut, DocumentTextResponse, UploadResponse
from app.services.audit import AuditService
from app.services.documents.service import DocumentService

logger = get_logger(__name__)

router = APIRouter(prefix="/documents", tags=["documents"])

DocumentDep = Annotated[DocumentService, Depends(get_document_service)]
AuditDep = Annotated[AuditService, Depends(get_audit_service)]

# Read the upload in bounded chunks so a lying Content-Length header cannot
# make the process buffer an unbounded body.
_CHUNK_SIZE = 1024 * 1024


@router.post(
    "/upload",
    response_model=UploadResponse,
    status_code=status.HTTP_201_CREATED,
    dependencies=[Depends(rate_limit_upload)],
    summary="העלאת מסמך",
)
async def upload_document(
    request: Request,
    user: CurrentUser,
    service: DocumentDep,
    audit: AuditDep,
    file: Annotated[UploadFile, File(description="PDF / DOCX / TXT / JPG / PNG")],
) -> UploadResponse:
    """Upload a file, extract its text and return the stored record.

    Images and scanned PDFs are routed through OCR automatically.
    """
    settings = request.app.state.settings
    data = await _read_bounded(file, settings.max_upload_bytes, settings.max_upload_mb)

    outcome = await service.upload(
        data=data,
        filename=file.filename or "document",
        content_type=file.content_type or "application/octet-stream",
        user_id=str(user.id),
    )
    await audit.record(
        AuditAction.DOCUMENT_UPLOAD,
        user_id=str(user.id),
        resource_type="document",
        resource_id=str(outcome.document.id),
        ip_address=client_ip(request),
        user_agent=user_agent(request),
        metadata={
            "content_type": outcome.document.content_type,
            "size_bytes": outcome.document.size_bytes,
            "used_ocr": outcome.document.used_ocr,
        },
    )
    return UploadResponse(
        document=DocumentOut.model_validate(outcome.document), warnings=outcome.warnings
    )


async def _read_bounded(file: UploadFile, max_bytes: int, max_mb: int) -> bytes:
    """Read an upload, aborting once it exceeds the limit."""
    chunks: list[bytes] = []
    total = 0
    while chunk := await file.read(_CHUNK_SIZE):
        total += len(chunk)
        if total > max_bytes:
            raise PayloadTooLargeError(f"הקובץ גדול מ-{max_mb} מגה-בייט")
        chunks.append(chunk)
    return b"".join(chunks)


@router.get(
    "",
    response_model=Page[DocumentOut],
    dependencies=[Depends(rate_limit_default)],
    summary="רשימת מסמכים",
)
async def list_documents(
    user: CurrentUser,
    service: DocumentDep,
    limit: Annotated[int, Query(ge=1, le=100)] = 20,
    offset: Annotated[int, Query(ge=0)] = 0,
    query: Annotated[str | None, Query(max_length=200)] = None,
) -> Page[DocumentOut]:
    """The signed-in user's uploaded documents."""
    documents, total = await service.list(
        user_id=str(user.id), limit=limit, offset=offset, query=query
    )
    return Page[DocumentOut](
        items=[DocumentOut.model_validate(d) for d in documents],
        total=total,
        limit=limit,
        offset=offset,
    )


@router.get(
    "/{document_id}",
    response_model=DocumentOut,
    dependencies=[Depends(rate_limit_default)],
    summary="פרטי מסמך",
)
async def get_document(
    document_id: str, user: CurrentUser, service: DocumentDep
) -> DocumentOut:
    """Metadata for one document."""
    return DocumentOut.model_validate(await service.get(document_id, user_id=str(user.id)))


@router.get(
    "/{document_id}/text",
    response_model=DocumentTextResponse,
    dependencies=[Depends(rate_limit_default)],
    summary="טקסט שחולץ מהמסמך",
)
async def get_document_text(
    document_id: str, user: CurrentUser, service: DocumentDep
) -> DocumentTextResponse:
    """The extracted (decrypted) text of a document."""
    document = await service.get(document_id, user_id=str(user.id))
    text = await service.get_text(document_id, user_id=str(user.id))
    return DocumentTextResponse(
        document_id=str(document.id),
        text=text,
        word_count=document.word_count or 0,
        language=document.language,
        used_ocr=document.used_ocr,
    )


@router.get(
    "/{document_id}/download",
    dependencies=[Depends(rate_limit_default)],
    summary="הורדת הקובץ המקורי",
    response_class=Response,
)
async def download_document(
    document_id: str, user: CurrentUser, service: DocumentDep
) -> Response:
    """Stream back the originally uploaded bytes."""
    document, data = await service.download(document_id, user_id=str(user.id))
    # RFC 5987 encoding so Hebrew filenames survive the header.
    quoted = urllib.parse.quote(document.filename)
    return Response(
        content=data,
        media_type=document.content_type,
        headers={
            "Content-Disposition": f"attachment; filename*=UTF-8''{quoted}",
            "X-Content-Type-Options": "nosniff",
        },
    )


@router.delete(
    "/{document_id}",
    response_model=MessageResponse,
    dependencies=[Depends(rate_limit_default)],
    summary="מחיקת מסמך",
)
async def delete_document(
    document_id: str,
    request: Request,
    user: CurrentUser,
    service: DocumentDep,
    audit: AuditDep,
) -> MessageResponse:
    """Delete a document and erase its stored file."""
    await service.delete(document_id, user_id=str(user.id))
    await audit.record(
        AuditAction.DOCUMENT_DELETE,
        user_id=str(user.id),
        resource_type="document",
        resource_id=document_id,
        ip_address=client_ip(request),
    )
    return MessageResponse(message="המסמך נמחק")
