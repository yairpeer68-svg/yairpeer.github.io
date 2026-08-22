import hashlib
import hmac
import time

from app.core.errors import AppError


def verify_hmac_webhook(payload: bytes, signature_hex: str, timestamp: str, secret: str, tolerance_seconds: int = 300) -> str:
    if not secret:
        raise AppError("WEBHOOK_NOT_CONFIGURED", "Webhook secret is not configured", 503)
    try:
        ts = int(timestamp)
    except (TypeError, ValueError) as exc:
        raise AppError("INVALID_WEBHOOK_TIMESTAMP", "Invalid webhook timestamp", 400) from exc
    if abs(int(time.time()) - ts) > tolerance_seconds:
        raise AppError("STALE_WEBHOOK", "Webhook timestamp is outside the allowed window", 400)
    signed = f"{ts}.".encode() + payload
    expected = hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, signature_hex):
        raise AppError("INVALID_WEBHOOK_SIGNATURE", "Invalid webhook signature", 401)
    return hashlib.sha256(payload).hexdigest()
