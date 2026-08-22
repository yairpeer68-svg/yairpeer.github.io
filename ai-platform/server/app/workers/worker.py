import asyncio

import dramatiq
from dramatiq.brokers.redis import RedisBroker

from app.core.config import get_settings
from app.tasks.cleanup import cleanup_retention

settings=get_settings()
broker=RedisBroker(url=settings.REDIS_URL)
dramatiq.set_broker(broker)


@dramatiq.actor(max_retries=3, min_backoff=5000)
def cleanup_expired_data():
    return asyncio.run(cleanup_retention())


@dramatiq.actor(max_retries=0)
def notification_job(user_id: str, title: str, body: str):
    """Push dispatch hook.

    Durable in-app notifications are written by the service layer, so a missing push
    adapter is a no-op rather than a retry storm: retrying a permanently unconfigured
    integration only fills the dead-letter queue.
    """
    if not settings.FCM_CREDENTIALS_JSON:
        return "not configured"
    return "adapter not enabled"


@dramatiq.actor(max_retries=5, min_backoff=10000)
def send_transactional_email(recipient: str, subject: str, text: str):
    from app.services.email import send_email_sync
    result = send_email_sync(settings, recipient, subject, text)
    if result == "not configured":
        raise RuntimeError("SMTP is not configured")
    return result


@dramatiq.actor(max_retries=0, time_limit=(settings.ENGINEERING_RUN_TIMEOUT_SECONDS + 300) * 1000)
def engineering_run_job(run_id: str):
    from app.engineering.orchestrator import run_engineering_run
    return asyncio.run(run_engineering_run(run_id))
