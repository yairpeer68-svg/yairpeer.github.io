import hashlib
import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.entities import FeatureFlag, UserFeatureFlag


def evaluate_flag(flag: FeatureFlag, user_id: uuid.UUID | None, override: bool | None = None) -> bool:
    """Resolve one already-loaded flag. An explicit per-user override always wins."""
    if override is not None:
        return override
    if flag.enabled:
        return True
    if user_id and flag.rollout_percentage > 0:
        bucket = int(hashlib.sha256(f"{flag.key}:{user_id}".encode()).hexdigest()[:8], 16) % 100
        return bucket < flag.rollout_percentage
    return False


async def flag_enabled(db: AsyncSession, key: str, user_id: uuid.UUID | None = None) -> bool:
    flag = await db.get(FeatureFlag, key)
    if not flag:
        return False
    override = None
    if user_id:
        row = await db.scalar(select(UserFeatureFlag).where(
            UserFeatureFlag.user_id == user_id, UserFeatureFlag.flag_key == key))
        if row is not None:
            override = row.enabled
    return evaluate_flag(flag, user_id, override)
