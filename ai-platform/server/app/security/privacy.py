from app.core.config import Settings
from app.core.errors import AppError


def encrypt_prompt_if_enabled(settings: Settings, plaintext: str) -> str | None:
    if not settings.PROMPT_LOGGING_ENABLED:
        return None
    if not settings.PROMPT_RETENTION_ENCRYPTION_KEY:
        raise AppError("PROMPT_ENCRYPTION_NOT_CONFIGURED", "Prompt retention is enabled without an encryption key", 503)
    try:
        from cryptography.fernet import Fernet
        f = Fernet(settings.PROMPT_RETENTION_ENCRYPTION_KEY.encode())
        return f.encrypt(plaintext.encode("utf-8")).decode("ascii")
    except (ValueError, TypeError) as exc:
        raise AppError("PROMPT_ENCRYPTION_INVALID_KEY", "Prompt retention encryption key is invalid", 503) from exc
