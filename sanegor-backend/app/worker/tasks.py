"""Background tasks.

Each task opens its own event loop and database session — a Celery worker has
neither, and reusing the API's would be a use-after-close.
"""

from __future__ import annotations

import asyncio
from typing import Any

from app.core.config import get_settings
from app.core.logging import get_logger
from app.core.security import TextCipher
from app.db.session import Database
from app.services.documents.extractor import DocumentExtractor
from app.services.documents.ocr import OcrService
from app.services.documents.service import process_document_task
from app.services.storage import FileStorage
from app.worker.celery_app import celery_app

logger = get_logger(__name__)


def _run(coro: Any) -> Any:
    """Run an async coroutine from a synchronous Celery task."""
    return asyncio.run(coro)


@celery_app.task(name="app.worker.tasks.process_document", bind=True, max_retries=2)
def process_document(self: Any, document_id: str) -> dict[str, str]:  # noqa: ANN401
    """Extract text (and run OCR) for an uploaded document."""
    settings = get_settings()

    async def _work() -> None:
        database = Database(settings)
        try:
            ocr = OcrService(
                enabled=settings.ocr_enabled,
                languages=settings.ocr_languages,
                tesseract_cmd=settings.tesseract_cmd,
            )
            await process_document_task(
                database.session_factory,
                document_id,
                FileStorage(settings.storage_dir),
                DocumentExtractor(ocr),
                TextCipher(settings.encryption_key),
                settings,
            )
        finally:
            await database.dispose()

    try:
        _run(_work())
    except Exception as exc:  # noqa: BLE001
        logger.error("task_process_document_failed", document_id=document_id, error=str(exc))
        raise self.retry(exc=exc, countdown=30) from exc
    return {"document_id": document_id, "status": "processed"}


@celery_app.task(name="app.worker.tasks.purge_expired_tokens")
def purge_expired_tokens() -> dict[str, int]:
    """Delete refresh tokens that expired more than a week ago."""
    settings = get_settings()

    async def _work() -> int:
        from app.services.auth import AuthService

        database = Database(settings)
        try:
            async with database.session() as session:
                return await AuthService(session, settings).purge_expired_tokens()
        finally:
            await database.dispose()

    removed = _run(_work())
    logger.info("expired_tokens_purged", removed=removed)
    return {"removed": removed}
