"""Password hashing, JWT issuing/verification and payload encryption.

Design notes
------------
* Passwords use Argon2id (memory-hard) rather than bcrypt.
* Access tokens are short lived; refresh tokens carry a ``jti`` so a logout or
  a password change can revoke them through the Redis deny-list.
* ``TextCipher`` implements encryption-at-rest for extracted document text,
  which is the most sensitive content the system stores.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any, Literal

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError
from cryptography.fernet import Fernet, InvalidToken

from app.core.config import Settings
from app.core.errors import AuthenticationError, ValidationError

TokenType = Literal["access", "refresh", "verify_email", "reset_password"]

_hasher = PasswordHasher(time_cost=3, memory_cost=64 * 1024, parallelism=2)


# --------------------------------------------------------------------- passwords
def hash_password(plain: str) -> str:
    """Return an Argon2id hash of ``plain``."""
    return _hasher.hash(plain)


def verify_password(plain: str, hashed: str) -> bool:
    """Constant-time password check that never raises on malformed hashes."""
    try:
        return _hasher.verify(hashed, plain)
    except (VerifyMismatchError, VerificationError, InvalidHashError):
        return False


def needs_rehash(hashed: str) -> bool:
    """True when the stored hash uses outdated Argon2 parameters."""
    try:
        return _hasher.check_needs_rehash(hashed)
    except InvalidHashError:
        return True


def validate_password_strength(password: str, min_length: int) -> None:
    """Reject passwords that fail the baseline policy.

    Raises:
        ValidationError: with a Hebrew, user-facing explanation.
    """
    problems: list[str] = []
    if len(password) < min_length:
        problems.append(f"אורך מינימלי {min_length} תווים")
    if not any(c.isdigit() for c in password):
        problems.append("נדרשת לפחות ספרה אחת")
    if not any(c.isalpha() for c in password):
        problems.append("נדרשת לפחות אות אחת")
    if password.lower() in _COMMON_PASSWORDS:
        problems.append("הסיסמה נפוצה מדי")
    if problems:
        raise ValidationError("הסיסמה אינה עומדת בדרישות", details={"reasons": problems})


_COMMON_PASSWORDS = frozenset(
    {
        "password",
        "password1",
        "12345678",
        "123456789",
        "1234567890",
        "qwertyuiop",
        "aa123456",
        "israel123",
        "abcd1234",
    }
)


# ------------------------------------------------------------------------ tokens
@dataclass(frozen=True, slots=True)
class TokenPair:
    """A freshly minted access/refresh pair."""

    access_token: str
    refresh_token: str
    expires_in: int
    token_type: str = "Bearer"


@dataclass(frozen=True, slots=True)
class TokenClaims:
    """Decoded and validated JWT claims."""

    subject: str
    token_type: TokenType
    jti: str
    role: str
    expires_at: datetime
    raw: dict[str, Any]


class TokenService:
    """Issues and verifies the application's JWTs."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    def _encode(
        self,
        subject: str,
        token_type: TokenType,
        ttl: timedelta,
        *,
        role: str = "user",
        extra: dict[str, Any] | None = None,
    ) -> tuple[str, str, datetime]:
        now = datetime.now(UTC)
        expires_at = now + ttl
        jti = uuid.uuid4().hex
        payload: dict[str, Any] = {
            "sub": subject,
            "typ": token_type,
            "role": role,
            "jti": jti,
            "iat": int(now.timestamp()),
            "nbf": int(now.timestamp()),
            "exp": int(expires_at.timestamp()),
            "iss": self._settings.app_name,
        }
        if extra:
            payload.update(extra)
        token = jwt.encode(
            payload, self._settings.secret_key, algorithm=self._settings.jwt_algorithm
        )
        return token, jti, expires_at

    def create_pair(self, subject: str, role: str = "user") -> tuple[TokenPair, str]:
        """Return ``(pair, refresh_jti)``; the caller persists the ``jti``."""
        ttl = timedelta(minutes=self._settings.access_token_ttl_minutes)
        access, _, _ = self._encode(subject, "access", ttl, role=role)
        refresh, refresh_jti, _ = self._encode(
            subject,
            "refresh",
            timedelta(days=self._settings.refresh_token_ttl_days),
            role=role,
        )
        return (
            TokenPair(
                access_token=access,
                refresh_token=refresh,
                expires_in=int(ttl.total_seconds()),
            ),
            refresh_jti,
        )

    def create_action_token(self, subject: str, token_type: TokenType, ttl: timedelta) -> str:
        """Single-purpose token for e-mail verification / password reset."""
        token, _, _ = self._encode(subject, token_type, ttl)
        return token

    def decode(self, token: str, expected_type: TokenType) -> TokenClaims:
        """Decode ``token`` and assert its type.

        Raises:
            AuthenticationError: on any signature, expiry or type mismatch.
        """
        try:
            payload = jwt.decode(
                token,
                self._settings.secret_key,
                algorithms=[self._settings.jwt_algorithm],
                issuer=self._settings.app_name,
                options={"require": ["exp", "sub", "typ", "jti"]},
            )
        except jwt.ExpiredSignatureError as exc:
            raise AuthenticationError("תוקף ההתחברות פג. יש להתחבר מחדש") from exc
        except jwt.PyJWTError as exc:
            raise AuthenticationError("אסימון גישה שגוי") from exc

        if payload.get("typ") != expected_type:
            raise AuthenticationError("אסימון גישה שגוי")

        return TokenClaims(
            subject=str(payload["sub"]),
            token_type=expected_type,
            jti=str(payload["jti"]),
            role=str(payload.get("role", "user")),
            expires_at=datetime.fromtimestamp(payload["exp"], UTC),
            raw=payload,
        )


# -------------------------------------------------------------------- encryption
class TextCipher:
    """Symmetric encryption for text stored at rest.

    When no ``ENCRYPTION_KEY`` is configured the cipher becomes a pass-through
    so local development works out of the box; production configuration is
    validated separately in :mod:`app.core.config`.
    """

    _PREFIX = "enc:v1:"

    def __init__(self, key: str) -> None:
        self._fernet: Fernet | None = None
        if key:
            self._fernet = Fernet(self._normalise(key))

    @staticmethod
    def _normalise(key: str) -> bytes:
        """Accept either a real Fernet key or any passphrase."""
        raw = key.encode()
        try:
            if len(base64.urlsafe_b64decode(raw)) == 32:
                return raw
        except (ValueError, TypeError):
            pass
        return base64.urlsafe_b64encode(hashlib.sha256(raw).digest())

    @property
    def enabled(self) -> bool:
        return self._fernet is not None

    def encrypt(self, plaintext: str) -> str:
        if self._fernet is None or not plaintext:
            return plaintext
        return self._PREFIX + self._fernet.encrypt(plaintext.encode()).decode()

    def decrypt(self, stored: str) -> str:
        if not stored or not stored.startswith(self._PREFIX):
            return stored
        if self._fernet is None:
            raise ValueError("ENCRYPTION_KEY is required to read encrypted content")
        try:
            return self._fernet.decrypt(stored[len(self._PREFIX) :].encode()).decode()
        except InvalidToken as exc:
            raise ValueError("stored content could not be decrypted") from exc


# ------------------------------------------------------------------------- misc
def generate_opaque_token(length: int = 48) -> str:
    """URL-safe random token for one-off links."""
    return secrets.token_urlsafe(length)


def constant_time_compare(left: str, right: str) -> bool:
    """Timing-safe string comparison."""
    return hmac.compare_digest(left.encode(), right.encode())
