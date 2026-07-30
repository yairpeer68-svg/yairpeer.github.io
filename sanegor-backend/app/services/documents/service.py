"""Document upload, processing and retrieval."""

from __future__ import annotations

import uuid
from dataclasses import dataclass

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.core.config import Settings
from app.core.errors import NotFoundError, PayloadTooLargeError, UnsupportedMediaTypeError
from app.core.logging import get_logger
from app.core.security import TextCipher
from app.db.base import utcnow
from app.db.models.document import Document, DocumentStatus
from app.services.documents.extractor import DocumentExtractor
from app.services.storage import FileStorage, sanitise_filename, sniff_content_type

logger = get_logger(__name__)


@dataclass(slots=True)
class UploadOutcome:
    """Result of accepting an upload."""

    document: Document
    warnings: list[str]


class DocumentService:
    """Stores uploads, extracts their text and serves them back."""

    def __init__(
        self,
        session: AsyncSession,
        storage: FileStorage,
        extractor: DocumentExtractor,
        cipher: TextCipher,
        settings: Settings,
    ) -> None:
        self._session = session
        self._storage = storage
        self._extractor = extractor
        self._cipher = cipher
        self._settings = settings

    # ------------------------------------------------------------------ upload
    async def upload(
        self,
        *,
        data: bytes,
        filename: str,
        content_type: str,
        user_id: str,
        process_now: bool = True,
    ) -> UploadOutcome:
        """Validate, store and (optionally) immediately process an upload.

        Validation order matters: size first (cheapest), then the declared type
        against the allow-list, then the magic bytes. Only then does anything
        touch the disk.
        """
        if len(data) > self._settings.max_upload_bytes:
            raise PayloadTooLargeError(
                f"הקובץ גדול מ-{self._settings.max_upload_mb} מגה-בייט",
                details={"max_bytes": self._settings.max_upload_bytes},
            )

        declared = (content_type or "").split(";")[0].strip().lower()
        if declared not in self._settings.allowed_upload_types:
            raise UnsupportedMediaTypeError(
                "סוג הקובץ אינו נתמך",
                details={
                    "declared": declared,
                    "allowed": self._settings.allowed_upload_types,
                },
            )
        verified = sniff_content_type(data, declared)

        safe_name = sanitise_filename(filename)
        suffix = f".{safe_name.rsplit('.', 1)[-1]}" if "." in safe_name else ""
        stored = await self._storage.save(data, owner_id=user_id, suffix=suffix)

        document = Document(
            user_id=uuid.UUID(user_id),
            filename=safe_name,
            content_type=verified,
            size_bytes=stored.size_bytes,
            checksum_sha256=stored.checksum_sha256,
            storage_key=stored.storage_key,
            status=DocumentStatus.PENDING,
        )
        self._session.add(document)
        await self._session.flush()

        warnings: list[str] = []
        if process_now:
            warnings = await self.process(document, data)

        logger.info(
            "document_uploaded",
            document_id=str(document.id),
            content_type=verified,
            bytes=stored.size_bytes,
        )
        return UploadOutcome(document=document, warnings=warnings)

    async def process(self, document: Document, data: bytes | None = None) -> list[str]:
        """Extract text into ``document``. Never raises — records failure instead."""
        document.status = DocumentStatus.PROCESSING
        await self._session.flush()

        try:
            payload = data if data is not None else await self._storage.read(document.storage_key)
            result = await self._extractor.extract(
                payload, document.content_type, document.filename
            )
        except Exception as exc:  # noqa: BLE001 - surfaced through the record
            document.status = DocumentStatus.FAILED
            document.error = str(exc)[:500]
            await self._session.flush()
            logger.warning(
                "document_processing_failed", document_id=str(document.id), error=str(exc)
            )
            return []

        document.extracted_text = self._cipher.encrypt(result.text)
        document.page_count = result.page_count
        document.word_count = result.word_count
        document.language = result.language
        document.used_ocr = result.used_ocr
        document.status = DocumentStatus.READY
        document.error = None
        document.extra = {"warnings": result.warnings}
        await self._session.flush()
        return result.warnings

    # -------------------------------------------------------------------- read
    async def get(self, document_id: str, *, user_id: str) -> Document:
        try:
            identifier = uuid.UUID(document_id)
        except ValueError as exc:
            raise NotFoundError("המסמך לא נמצא") from exc

        document = (
            await self._session.execute(
                select(Document).where(
                    Document.id == identifier,
                    Document.user_id == uuid.UUID(user_id),
                    Document.deleted_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if document is None:
            raise NotFoundError("המסמך לא נמצא")
        return document

    async def get_text(self, document_id: str, *, user_id: str) -> str:
        """Decrypted extracted text."""
        document = await self.get(document_id, user_id=user_id)
        return self._cipher.decrypt(document.extracted_text or "")

    async def list(
        self, *, user_id: str, limit: int = 20, offset: int = 0, query: str | None = None
    ) -> tuple[list[Document], int]:
        conditions = [
            Document.user_id == uuid.UUID(user_id),
            Document.deleted_at.is_(None),
        ]
        if query and (needle := query.strip()):
            conditions.append(Document.filename.ilike(f"%{needle}%"))

        total = int(
            (
                await self._session.execute(
                    select(func.count()).select_from(Document).where(*conditions)
                )
            ).scalar_one()
        )
        rows = (
            await self._session.execute(
                select(Document)
                .where(*conditions)
                .order_by(Document.created_at.desc())
                .limit(limit)
                .offset(offset)
            )
        ).scalars().all()
        return list(rows), total

    async def download(self, document_id: str, *, user_id: str) -> tuple[Document, bytes]:
        """Fetch the original bytes of a stored upload."""
        document = await self.get(document_id, user_id=user_id)
        return document, await self._storage.read(document.storage_key)

    # ------------------------------------------------------------------ delete
    async def delete(self, document_id: str, *, user_id: str, purge: bool = True) -> None:
        """Soft-delete the record and remove the file from disk.

        The row is kept (soft delete) so analyses remain attributable, but the
        file itself is erased — holding a user's uploaded contract after they
        asked for its deletion is not defensible.
        """
        document = await self.get(document_id, user_id=user_id)
        document.deleted_at = utcnow()
        document.extracted_text = None
        await self._session.flush()

        if purge:
            await self._storage.delete(document.storage_key)
        logger.info("document_deleted", document_id=document_id)


async def process_document_task(
    session_factory: async_sessionmaker[AsyncSession],
    document_id: str,
    storage: FileStorage,
    extractor: DocumentExtractor,
    cipher: TextCipher,
    settings: Settings,
) -> None:
    """Background entry point for out-of-band processing.

    Runs with its own session because the request's session is closed as soon
    as the response is sent.
    """
    async with session_factory() as session:
        document = (
            await session.execute(
                select(Document).where(Document.id == uuid.UUID(document_id))
            )
        ).scalar_one_or_none()
        if document is None:
            logger.warning("background_document_missing", document_id=document_id)
            return
        service = DocumentService(session, storage, extractor, cipher, settings)
        await service.process(document)
        await session.commit()
