import uuid
from typing import Literal

from pydantic import BaseModel, Field


class UserAdminPatch(BaseModel):
    is_active: bool | None = None
    is_admin: bool | None = None
    display_name: str | None = Field(default=None, max_length=120)


class QuotaPatch(BaseModel):
    requests_per_minute: int = Field(ge=1, le=10000)
    requests_per_day: int = Field(ge=1, le=1000000)
    tokens_per_day: int = Field(ge=100, le=1000000000)
    max_output_tokens: int = Field(ge=1, le=8192)


class FeatureFlagUpsert(BaseModel):
    key: str = Field(pattern=r"^[a-z0-9_-]{2,100}$")
    enabled: bool
    rollout_percentage: int = Field(default=0, ge=0, le=100)
    description: str | None = Field(default=None, max_length=500)


class MaintenanceRequest(BaseModel):
    enabled: bool
    message: str = Field(default="Service is temporarily unavailable for maintenance", max_length=300)


class RevokeSessionsRequest(BaseModel):
    user_id: uuid.UUID


class UserFlagOverride(BaseModel):
    enabled: bool


class AppVersionUpsert(BaseModel):
    platform: str = Field(pattern="^(android|ios|web)$")
    minimum_supported_version: str = Field(min_length=1, max_length=32)
    latest_version: str = Field(min_length=1, max_length=32)
    force_update: bool = False
    release_notes: str | None = Field(default=None, max_length=10000)
    download_url: str | None = Field(default=None, max_length=2000)
    store_url: str | None = Field(default=None, max_length=2000)


class AdminApprovalDecision(BaseModel):
    decision: Literal["approved", "rejected"]
    note: str | None = Field(default=None, max_length=2000)
