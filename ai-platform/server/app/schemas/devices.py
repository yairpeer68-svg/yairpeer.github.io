import uuid
from datetime import datetime

from pydantic import BaseModel, Field


class DeviceRegister(BaseModel):
    device_id: str = Field(min_length=8, max_length=128)
    installation_id: str = Field(min_length=8, max_length=128)
    platform: str = Field(pattern="^(android|ios|web|desktop)$")
    device_name: str | None = Field(default=None, max_length=128)
    app_version: str | None = Field(default=None, max_length=32)
    os_version: str | None = Field(default=None, max_length=64)
    push_token: str | None = Field(default=None, max_length=4096)
    attestation_token: str | None = Field(default=None, max_length=10000)


class DeviceOut(BaseModel):
    id: uuid.UUID
    device_id: str
    installation_id: str
    platform: str
    device_name: str | None
    app_version: str | None
    os_version: str | None
    trusted: bool
    last_seen: datetime
    created_at: datetime
    revoked_at: datetime | None

    model_config = {"from_attributes": True}
