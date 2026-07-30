"""Optional Celery worker.

Celery is off by default (``CELERY_ENABLED=false``): document processing runs
inline, which is simpler and fast enough for typical uploads.  Turn it on when
OCR over large scanned PDFs starts holding request workers.
"""

from __future__ import annotations

from celery import Celery

from app.core.config import get_settings

settings = get_settings()

celery_app = Celery(
    "sanegor",
    broker=settings.celery_broker_url,
    backend=settings.celery_result_backend,
    include=["app.worker.tasks"],
)

celery_app.conf.update(
    task_serializer="json",
    result_serializer="json",
    accept_content=["json"],
    timezone="Asia/Jerusalem",
    enable_utc=True,
    task_track_started=True,
    task_time_limit=900,
    task_soft_time_limit=840,
    worker_prefetch_multiplier=1,      # long tasks: don't hoard the queue
    worker_max_tasks_per_child=50,     # bound leaks from native OCR libraries
    broker_connection_retry_on_startup=True,
    beat_schedule={
        "purge-expired-refresh-tokens": {
            "task": "app.worker.tasks.purge_expired_tokens",
            "schedule": 24 * 60 * 60,
        },
    },
)

__all__ = ["celery_app"]
