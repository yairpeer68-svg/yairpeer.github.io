"""Periodic maintenance loop.

Dramatiq has no built-in scheduler, so the retention actor previously existed without
anything ever enqueuing it. This process runs as its own container and enqueues the
sweep on a fixed interval; a missed tick is simply picked up on the next one.
"""
import asyncio
import logging

from app.core.config import get_settings
from app.core.logging import configure_logging

log = logging.getLogger("scheduler")


async def main() -> None:
    settings = get_settings()
    configure_logging(settings.LOG_LEVEL)
    interval = max(300, settings.RETENTION_SWEEP_INTERVAL_SECONDS)
    from app.workers.worker import cleanup_expired_data
    log.info("scheduler_started", extra={"event": "scheduler_started"})
    while True:
        try:
            cleanup_expired_data.send()
        except Exception as exc:
            log.error("retention_enqueue_failed", extra={"event": type(exc).__name__})
        await asyncio.sleep(interval)


if __name__ == "__main__":
    asyncio.run(main())
