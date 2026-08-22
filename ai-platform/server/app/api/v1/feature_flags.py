from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies.auth import current_user
from app.db.session import get_session
from app.models.entities import FeatureFlag, User, UserFeatureFlag
from app.services.feature_flags import evaluate_flag

router = APIRouter()


@router.get("")
async def flags(user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    """Resolve every flag for the caller.

    Two queries total. The previous implementation issued one flag lookup plus one
    override lookup per flag, so the cost grew linearly with the flag count.
    """
    all_flags = list((await db.scalars(select(FeatureFlag).order_by(FeatureFlag.key))).all())
    overrides = {
        row.flag_key: row.enabled
        for row in (await db.scalars(
            select(UserFeatureFlag).where(UserFeatureFlag.user_id == user.id)
        )).all()
    }
    return {flag.key: evaluate_flag(flag, user.id, overrides.get(flag.key)) for flag in all_flags}
