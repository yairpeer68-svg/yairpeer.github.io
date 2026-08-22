import uuid
from datetime import datetime
from typing import Generic, TypeVar

from pydantic import BaseModel, Field

T = TypeVar("T")


class Page(BaseModel, Generic[T]):
    items: list[T]
    page: int
    page_size: int
    total: int


class MessageResponse(BaseModel):
    message: str


class Pagination(BaseModel):
    page: int = Field(1, ge=1, le=100000)
    page_size: int = Field(50, ge=1, le=200)


class AuditOut(BaseModel):
    id: uuid.UUID
    action: str
    target_type: str | None
    target_id: str | None
    request_id: str | None
    created_at: datetime

    model_config = {"from_attributes": True}
