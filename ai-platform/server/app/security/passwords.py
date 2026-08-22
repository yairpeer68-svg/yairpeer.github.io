from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerifyMismatchError
from argon2.low_level import Type

from app.core.errors import AppError

_hasher = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=2, hash_len=32, salt_len=16, type=Type.ID)
COMMON = {"password", "password123", "1234567890", "qwerty12345", "letmein1234"}


def validate_password_policy(password: str) -> None:
    if len(password) < 10:
        raise AppError("WEAK_PASSWORD", "Password must contain at least 10 characters", 422)
    if len(password) > 256:
        raise AppError("PASSWORD_TOO_LONG", "Password is too long", 422)
    normalized = password.casefold().strip()
    if normalized in COMMON or len(set(normalized)) < 5:
        raise AppError("WEAK_PASSWORD", "Password is too easy to guess", 422)


def hash_password(password: str) -> str:
    validate_password_policy(password)
    return _hasher.hash(password)


def verify_password(password: str, encoded: str) -> bool:
    try:
        return _hasher.verify(encoded, password)
    except (VerifyMismatchError, InvalidHashError):
        return False
