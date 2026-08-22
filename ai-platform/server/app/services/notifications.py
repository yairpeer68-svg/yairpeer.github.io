import uuid
from dataclasses import dataclass

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.models.entities import Notification


@dataclass(frozen=True)
class PushResult:
    status: str
    provider_message_id: str | None = None


class PushProvider:
    async def send(self, token: str, title: str, body: str, data: dict) -> PushResult:
        raise NotImplementedError


class NotConfiguredPushProvider(PushProvider):
    async def send(self, token: str, title: str, body: str, data: dict) -> PushResult:
        return PushResult("not configured")


def build_push_provider(settings: Settings) -> PushProvider:
    # FCM is configuration-gated. This release stores notifications reliably even when FCM is not configured.
    return NotConfiguredPushProvider()


class NotificationService:
    def __init__(self, db: AsyncSession, settings: Settings):
        self.db = db
        self.push = build_push_provider(settings)

    async def create(self, user_id: uuid.UUID, title: str, body: str, kind: str = "system", data: dict | None = None) -> Notification:
        item = Notification(user_id=user_id, title=title, body=body, kind=kind, data_json=data or {})
        self.db.add(item)
        await self.db.commit()
        await self.db.refresh(item)
        return item
