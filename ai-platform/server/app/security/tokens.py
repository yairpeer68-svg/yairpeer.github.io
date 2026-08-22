import hashlib
import secrets
import uuid
from datetime import UTC, datetime, timedelta

import jwt

from app.core.config import Settings
from app.core.errors import AppError


def create_access_token(settings: Settings, user_id: uuid.UUID, session_id: uuid.UUID, is_admin: bool) -> str:
    now = datetime.now(UTC)
    payload = {
        "sub": str(user_id),
        "sid": str(session_id),
        "adm": bool(is_admin),
        "iat": int(now.timestamp()),
        "exp": int((now + timedelta(minutes=settings.ACCESS_TOKEN_MINUTES)).timestamp()),
        "iss": settings.APP_NAME,
        "jti": str(uuid.uuid4()),
    }
    return jwt.encode(payload, settings.JWT_SECRET, algorithm=settings.JWT_ALGORITHM)


def decode_access_token(settings: Settings, token: str) -> dict:
    try:
        return jwt.decode(token, settings.JWT_SECRET, algorithms=[settings.JWT_ALGORITHM], issuer=settings.APP_NAME)
    except jwt.ExpiredSignatureError as exc:
        raise AppError("TOKEN_EXPIRED", "Access token expired", 401) from exc
    except jwt.PyJWTError as exc:
        raise AppError("INVALID_TOKEN", "Invalid access token", 401) from exc


def generate_opaque_token(bytes_len: int = 48) -> str:
    return secrets.token_urlsafe(bytes_len)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()
