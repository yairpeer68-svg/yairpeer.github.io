import asyncio
import logging
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, Request
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies.auth import AuthContext, current_user, get_auth_context
from app.core.config import Settings, get_settings
from app.db.session import get_session
from app.models.entities import EngineeringProject, User
from app.repositories.audit import write_audit
from app.schemas.auth import UserOut
from app.schemas.common import MessageResponse
from app.engineering.workspace import Workspace
from app.services.auth_service import AuthService

log = logging.getLogger("users")

router = APIRouter()


@router.get("/me", response_model=UserOut)
async def me(user: User = Depends(current_user)):
    return user


@router.delete("/me", response_model=MessageResponse)
async def delete_me(request: Request, ctx: AuthContext = Depends(get_auth_context), db: AsyncSession = Depends(get_session),
                    settings: Settings = Depends(get_settings)):
    now = datetime.now(UTC)
    # Erase engineering workspaces before the account row is anonymised. Otherwise the
    # user's imported source code stayed on disk indefinitely with no owner to delete it.
    projects = list((await db.scalars(
        select(EngineeringProject).where(EngineeringProject.user_id == ctx.user.id)
    )).all())
    for project in projects:
        try:
            workspace = Workspace(settings, str(ctx.user.id), project.workspace_key)
            await asyncio.to_thread(workspace.clear)
            await asyncio.to_thread(workspace.root.rmdir)
        except Exception:
            log.warning("workspace_purge_failed", extra={"event": "workspace_purge_failed"})
        project.status = "deleted"
    ctx.user.is_active = False
    ctx.user.deleted_at = now
    ctx.user.email = f"deleted-{ctx.user.id}@invalid.local"
    ctx.user.display_name = None
    await AuthService(db, settings).revoke_all(request, ctx.user, "account_deleted")
    await write_audit(db, request, "account_delete", ctx.user.id, "user", str(ctx.user.id))
    await db.commit()
    return MessageResponse(message="Account deleted")
