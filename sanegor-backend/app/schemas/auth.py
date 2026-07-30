"""Authentication request/response schemas."""

from __future__ import annotations

from datetime import datetime
from typing import Annotated

from pydantic import EmailStr, Field, field_validator

from app.schemas.common import ApiModel

# Israeli mobile numbers, with or without the international prefix.
_PHONE_PATTERN = r"^(\+972|0)([23489]|5[0-9]|7[0-9])[0-9]{7}$"


class RegisterRequest(ApiModel):
    email: EmailStr
    password: Annotated[str, Field(min_length=10, max_length=128)]
    full_name: Annotated[str, Field(min_length=2, max_length=120)]
    phone: Annotated[str, Field(pattern=_PHONE_PATTERN)] | None = None

    @field_validator("full_name")
    @classmethod
    def _strip_name(cls, value: str) -> str:
        cleaned = " ".join(value.split())
        if len(cleaned) < 2:
            raise ValueError("שם מלא קצר מדי")
        return cleaned


class LoginRequest(ApiModel):
    email: EmailStr
    password: Annotated[str, Field(min_length=1, max_length=128)]


class OAuthLoginRequest(ApiModel):
    """ID token issued by Google or Apple, verified server-side."""

    id_token: Annotated[str, Field(min_length=10, max_length=4096)]


class RefreshRequest(ApiModel):
    refresh_token: Annotated[str, Field(min_length=10, max_length=4096)]


class LogoutRequest(ApiModel):
    refresh_token: str | None = None
    all_devices: bool = False


class ForgotPasswordRequest(ApiModel):
    email: EmailStr


class ResetPasswordRequest(ApiModel):
    token: Annotated[str, Field(min_length=10, max_length=4096)]
    new_password: Annotated[str, Field(min_length=10, max_length=128)]


class ChangePasswordRequest(ApiModel):
    current_password: Annotated[str, Field(min_length=1, max_length=128)]
    new_password: Annotated[str, Field(min_length=10, max_length=128)]


class VerifyEmailRequest(ApiModel):
    token: Annotated[str, Field(min_length=10, max_length=4096)]


class TokenResponse(ApiModel):
    access_token: str
    refresh_token: str
    token_type: str = "Bearer"
    expires_in: int


class UserOut(ApiModel):
    """Public view of an account."""

    id: str
    email: str
    full_name: str
    phone: str | None = None
    role: str
    provider: str
    is_active: bool
    is_email_verified: bool
    created_at: datetime
    last_login_at: datetime | None = None
    preferences: dict = {}

    @field_validator("id", "role", "provider", mode="before")
    @classmethod
    def _stringify(cls, value: object) -> str:
        return getattr(value, "value", None) or str(value)


class AuthResponse(ApiModel):
    user: UserOut
    tokens: TokenResponse


class UpdateProfileRequest(ApiModel):
    full_name: Annotated[str, Field(min_length=2, max_length=120)] | None = None
    phone: Annotated[str, Field(pattern=_PHONE_PATTERN)] | None = None
    preferences: dict | None = None
