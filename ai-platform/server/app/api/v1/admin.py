import uuid
from datetime import UTC, datetime
from fastapi import APIRouter, Depends, Query, Request
from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies.auth import require_admin
from app.api.dependencies.rate_limit import admin_rate_limit
from app.core.config import Settings, get_settings
from app.core.errors import AppError
from app.db.session import get_session
from app.models.entities import (AIQuota, AIRequest, AuditLog, Device, FeatureFlag, SecurityEvent, User,
                                 UserFeatureFlag, AppVersion, Subscription, Payment, Notification, Session, RefreshToken,
                                 EngineeringProject, EngineeringRun, EngineeringApproval)
from app.repositories.audit import write_admin_action, write_audit
from app.repositories.users import UserRepository
from app.schemas.admin import (AdminApprovalDecision, FeatureFlagUpsert, MaintenanceRequest, QuotaPatch,
                               UserAdminPatch, UserFlagOverride, AppVersionUpsert)
from app.schemas.auth import UserOut
from app.schemas.common import MessageResponse
from app.services.redis_service import get_redis_service

router = APIRouter(dependencies=[Depends(admin_rate_limit)])


@router.get("/users")
async def users(page: int = Query(1, ge=1), page_size: int = Query(50, ge=1, le=200), q: str | None = None,
                sort_by: str = Query("created_at", pattern="^(created_at|updated_at|email)$"),
                order: str = Query("desc", pattern="^(asc|desc)$"),
                admin: User = Depends(require_admin), db: AsyncSession = Depends(get_session)):
    items, total = await UserRepository(db).list(page, page_size, q, sort_by, order)
    return {"items": [UserOut.model_validate(x).model_dump() for x in items], "page": page, "page_size": page_size, "total": total}


@router.get("/users/{user_id}", response_model=UserOut)
async def user_detail(user_id: uuid.UUID, admin: User = Depends(require_admin), db: AsyncSession = Depends(get_session)):
    item = await db.get(User, user_id)
    if not item or item.deleted_at is not None:
        raise AppError("USER_NOT_FOUND", "User not found", 404)
    return item


@router.patch("/users/{user_id}", response_model=UserOut)
async def user_patch(user_id: uuid.UUID, payload: UserAdminPatch, request: Request,
                     admin: User = Depends(require_admin), db: AsyncSession = Depends(get_session)):
    item = await db.get(User, user_id)
    if not item or item.deleted_at is not None:
        raise AppError("USER_NOT_FOUND", "User not found", 404)
    values = payload.model_dump(exclude_unset=True)
    if item.id == admin.id and values.get("is_admin") is False:
        raise AppError("SELF_DEMOTION_BLOCKED", "You cannot remove your own admin role", 409)
    for k, v in values.items(): setattr(item, k, v)
    await write_audit(db, request, "admin_user_patch", admin.id, "user", str(item.id), {"fields": sorted(values)})
    await write_admin_action(db, admin.id, "admin_user_patch", "user", str(item.id), {"fields": sorted(values)})
    await db.commit(); await db.refresh(item); return item


@router.get("/devices")
async def devices(page: int = Query(1, ge=1), page_size: int = Query(50, ge=1, le=200),
                  platform: str | None = None, revoked: bool | None = None,
                  sort_by: str = Query("created_at", pattern="^(created_at|last_seen)$"), order: str = Query("desc", pattern="^(asc|desc)$"),
                  admin: User = Depends(require_admin), db: AsyncSession = Depends(get_session)):
    stmt=select(Device); count_stmt=select(func.count(Device.id))
    if platform: stmt=stmt.where(Device.platform==platform); count_stmt=count_stmt.where(Device.platform==platform)
    if revoked is True: stmt=stmt.where(Device.revoked_at.is_not(None)); count_stmt=count_stmt.where(Device.revoked_at.is_not(None))
    elif revoked is False: stmt=stmt.where(Device.revoked_at.is_(None)); count_stmt=count_stmt.where(Device.revoked_at.is_(None))
    col={"created_at":Device.created_at,"last_seen":Device.last_seen}[sort_by]; ordering=col.asc() if order=="asc" else col.desc()
    total = int(await db.scalar(count_stmt) or 0)
    items = list((await db.scalars(stmt.order_by(ordering).offset((page-1)*page_size).limit(page_size))).all())
    return {"items": [{"id": str(x.id), "user_id": str(x.user_id), "platform": x.platform,
                       "device_name": x.device_name, "revoked_at": x.revoked_at} for x in items],
            "page": page, "page_size": page_size, "total": total}


@router.post("/devices/{device_id}/revoke", response_model=MessageResponse)
async def revoke_device(device_id: uuid.UUID, request: Request, admin: User = Depends(require_admin), db: AsyncSession = Depends(get_session)):
    item = await db.get(Device, device_id)
    if not item: raise AppError("DEVICE_NOT_FOUND", "Device not found", 404)
    now=datetime.now(UTC); item.revoked_at = item.revoked_at or now
    await db.execute(update(Session).where(Session.device_id==item.id,Session.revoked_at.is_(None)).values(revoked_at=now))
    await db.execute(update(RefreshToken).where(RefreshToken.device_id==item.id,RefreshToken.revoked_at.is_(None)).values(revoked_at=now,revoke_reason="admin_device_revoked"))
    await write_audit(db, request, "admin_device_revoke", admin.id, "device", str(item.id))
    await write_admin_action(db, admin.id, "admin_device_revoke", "device", str(item.id))
    await db.commit(); return MessageResponse(message="Device revoked")


@router.get("/ai/usage")
async def ai_usage(days: int = Query(7, ge=1, le=90), admin: User = Depends(require_admin), db: AsyncSession = Depends(get_session)):
    from datetime import timedelta
    since = datetime.now(UTC) - timedelta(days=days)
    rows = (await db.execute(select(func.date(AIRequest.created_at), func.count(AIRequest.id), func.sum(AIRequest.total_tokens))
                             .where(AIRequest.created_at >= since).group_by(func.date(AIRequest.created_at))
                             .order_by(func.date(AIRequest.created_at)))).all()
    return [{"date": str(r[0]), "requests": int(r[1] or 0), "tokens": int(r[2] or 0)} for r in rows]


@router.put("/users/{user_id}/quota")
async def set_quota(user_id: uuid.UUID, payload: QuotaPatch, request: Request,
                    admin: User = Depends(require_admin), db: AsyncSession = Depends(get_session)):
    if not await db.get(User, user_id): raise AppError("USER_NOT_FOUND", "User not found", 404)
    item = await db.get(AIQuota, user_id)
    if item is None:
        item = AIQuota(user_id=user_id, **payload.model_dump()); db.add(item)
    else:
        for k,v in payload.model_dump().items(): setattr(item,k,v)
    await write_audit(db, request, "quota_change", admin.id, "user", str(user_id), payload.model_dump())
    await write_admin_action(db, admin.id, "quota_change", "user", str(user_id), payload.model_dump())
    await db.commit(); return {"user_id": str(user_id), **payload.model_dump()}


@router.get("/security-events")
async def security_events(page: int = Query(1, ge=1), page_size: int = Query(50, ge=1, le=200),
                          severity: str | None = None, admin: User = Depends(require_admin), db: AsyncSession = Depends(get_session)):
    stmt = select(SecurityEvent); count = select(func.count(SecurityEvent.id))
    if severity: stmt=stmt.where(SecurityEvent.severity==severity); count=count.where(SecurityEvent.severity==severity)
    total=int(await db.scalar(count) or 0); items=list((await db.scalars(stmt.order_by(SecurityEvent.created_at.desc()).offset((page-1)*page_size).limit(page_size))).all())
    return {"items":[{"id":str(x.id),"event_type":x.event_type,"severity":x.severity,"created_at":x.created_at} for x in items],"page":page,"page_size":page_size,"total":total}


@router.get("/audit-logs")
async def audit_logs(page: int = Query(1, ge=1), page_size: int = Query(50, ge=1, le=200),
                     action: str | None = None, admin: User = Depends(require_admin), db: AsyncSession = Depends(get_session)):
    stmt=select(AuditLog); count=select(func.count(AuditLog.id))
    if action: stmt=stmt.where(AuditLog.action==action); count=count.where(AuditLog.action==action)
    total=int(await db.scalar(count) or 0); items=list((await db.scalars(stmt.order_by(AuditLog.created_at.desc()).offset((page-1)*page_size).limit(page_size))).all())
    return {"items":[{"id":str(x.id),"action":x.action,"target_type":x.target_type,"target_id":x.target_id,"request_id":x.request_id,"created_at":x.created_at} for x in items],"page":page,"page_size":page_size,"total":total}


@router.get("/system")
async def system(admin: User = Depends(require_admin), settings: Settings = Depends(get_settings)):
    redis = get_redis_service(settings)
    return {"environment": settings.APP_ENV, "version": settings.APP_VERSION, "redis": "ok" if await redis.ping() else "unavailable",
            "deepseek": "configured" if settings.DEEPSEEK_API_KEY else "not configured"}


@router.post("/feature-flags")
async def upsert_flag(payload: FeatureFlagUpsert, request: Request, admin: User = Depends(require_admin), db: AsyncSession = Depends(get_session)):
    item=await db.get(FeatureFlag,payload.key)
    if item is None: item=FeatureFlag(key=payload.key); db.add(item)
    item.enabled=payload.enabled; item.rollout_percentage=payload.rollout_percentage; item.description=payload.description
    await write_audit(db,request,"feature_flag_change",admin.id,"feature_flag",payload.key,payload.model_dump())
    await write_admin_action(db,admin.id,"feature_flag_change","feature_flag",payload.key,payload.model_dump())
    await db.commit(); return payload.model_dump()


@router.post("/maintenance", response_model=MessageResponse)
async def maintenance(payload: MaintenanceRequest, request: Request, admin: User = Depends(require_admin),
                      settings: Settings = Depends(get_settings), db: AsyncSession = Depends(get_session)):
    redis=get_redis_service(settings)
    await redis.set_value("system:maintenance:enabled","1" if payload.enabled else "0")
    await redis.set_value("system:maintenance:message",payload.message)
    await write_audit(db,request,"maintenance_change",admin.id,"system","maintenance",payload.model_dump())
    await write_admin_action(db,admin.id,"maintenance_change","system","maintenance",payload.model_dump())
    await db.commit(); return MessageResponse(message="Maintenance mode updated")


@router.put("/feature-flags/{key}/users/{user_id}")
async def set_user_flag(key: str, user_id: uuid.UUID, payload: UserFlagOverride, request: Request,
                        admin: User = Depends(require_admin), db: AsyncSession = Depends(get_session)):
    if not await db.get(FeatureFlag, key): raise AppError("FLAG_NOT_FOUND", "Feature flag not found", 404)
    if not await db.get(User, user_id): raise AppError("USER_NOT_FOUND", "User not found", 404)
    item = await db.scalar(select(UserFeatureFlag).where(UserFeatureFlag.user_id==user_id, UserFeatureFlag.flag_key==key))
    if item is None: item=UserFeatureFlag(user_id=user_id, flag_key=key, enabled=payload.enabled); db.add(item)
    else: item.enabled=payload.enabled
    await write_audit(db, request, "feature_flag_user_override", admin.id, "user", str(user_id), {"key":key,"enabled":payload.enabled})
    await db.commit(); return {"key":key,"user_id":str(user_id),"enabled":payload.enabled}


@router.get("/quotas")
async def list_quotas(page:int=Query(1,ge=1),page_size:int=Query(50,ge=1,le=200),
                      admin:User=Depends(require_admin),db:AsyncSession=Depends(get_session)):
    total=int(await db.scalar(select(func.count(AIQuota.user_id))) or 0)
    items=list((await db.scalars(select(AIQuota).order_by(AIQuota.updated_at.desc()).offset((page-1)*page_size).limit(page_size))).all())
    return {"items":[{"user_id":str(x.user_id),"requests_per_minute":x.requests_per_minute,"requests_per_day":x.requests_per_day,"tokens_per_day":x.tokens_per_day,"max_output_tokens":x.max_output_tokens} for x in items],"page":page,"page_size":page_size,"total":total}


@router.get("/app-versions")
async def app_versions(page:int=Query(1,ge=1),page_size:int=Query(50,ge=1,le=200),admin:User=Depends(require_admin),db:AsyncSession=Depends(get_session)):
    total=int(await db.scalar(select(func.count(AppVersion.id))) or 0); items=list((await db.scalars(select(AppVersion).order_by(AppVersion.created_at.desc()).offset((page-1)*page_size).limit(page_size))).all())
    return {"items":[{"id":str(x.id),"platform":x.platform,"minimum_supported_version":x.minimum_supported_version,"latest_version":x.latest_version,"force_update":x.force_update,"release_notes":x.release_notes,"download_url":x.download_url,"store_url":x.store_url,"created_at":x.created_at} for x in items],"page":page,"page_size":page_size,"total":total}


@router.post("/app-versions")
async def create_app_version(payload:AppVersionUpsert,request:Request,admin:User=Depends(require_admin),db:AsyncSession=Depends(get_session)):
    item=AppVersion(**payload.model_dump());db.add(item);await write_audit(db,request,"app_version_create",admin.id,"app_version",payload.platform,payload.model_dump());await db.commit();await db.refresh(item);return {"id":str(item.id),**payload.model_dump()}


async def _paged_model(db, model, page:int, page_size:int, order_col):
    total=int(await db.scalar(select(func.count(model.id))) or 0); items=list((await db.scalars(select(model).order_by(order_col.desc()).offset((page-1)*page_size).limit(page_size))).all()); return items,total


@router.get("/subscriptions")
async def subscriptions(page:int=Query(1,ge=1),page_size:int=Query(50,ge=1,le=200),admin:User=Depends(require_admin),db:AsyncSession=Depends(get_session)):
    items,total=await _paged_model(db,Subscription,page,page_size,Subscription.created_at);return {"items":[{"id":str(x.id),"user_id":str(x.user_id),"plan":x.plan,"status":x.status,"provider":x.provider,"current_period_end":x.current_period_end} for x in items],"page":page,"page_size":page_size,"total":total}


@router.get("/payments")
async def payments(page:int=Query(1,ge=1),page_size:int=Query(50,ge=1,le=200),admin:User=Depends(require_admin),db:AsyncSession=Depends(get_session)):
    items,total=await _paged_model(db,Payment,page,page_size,Payment.created_at);return {"items":[{"id":str(x.id),"user_id":str(x.user_id),"provider":x.provider,"amount_minor":x.amount_minor,"currency":x.currency,"status":x.status,"created_at":x.created_at} for x in items],"page":page,"page_size":page_size,"total":total}


@router.get("/notifications")
async def admin_notifications(page:int=Query(1,ge=1),page_size:int=Query(50,ge=1,le=200),admin:User=Depends(require_admin),db:AsyncSession=Depends(get_session)):
    items,total=await _paged_model(db,Notification,page,page_size,Notification.created_at);return {"items":[{"id":str(x.id),"user_id":str(x.user_id),"title":x.title,"kind":x.kind,"read_at":x.read_at,"created_at":x.created_at} for x in items],"page":page,"page_size":page_size,"total":total}


@router.get("/engineering/summary")
async def engineering_summary(admin:User=Depends(require_admin),db:AsyncSession=Depends(get_session)):
    projects=int(await db.scalar(select(func.count(EngineeringProject.id))) or 0)
    active=int(await db.scalar(select(func.count(EngineeringRun.id)).where(EngineeringRun.status.in_(["queued","running","waiting_approval"]))) or 0)
    failed=int(await db.scalar(select(func.count(EngineeringRun.id)).where(EngineeringRun.status=="failed")) or 0)
    pending=int(await db.scalar(select(func.count(EngineeringApproval.id)).where(EngineeringApproval.status=="pending")) or 0)
    recent=list((await db.scalars(select(EngineeringRun).order_by(EngineeringRun.created_at.desc()).limit(20))).all())
    return {"projects":projects,"active_runs":active,"failed_runs":failed,"pending_approvals":pending,"recent_runs":[{"id":str(x.id),"project_id":str(x.project_id),"status":x.status,"stage":x.stage,"progress":x.progress,"goal":x.goal[:240],"created_at":x.created_at} for x in recent]}


@router.get("/engineering/approvals")
async def engineering_approvals(page:int=Query(1,ge=1),page_size:int=Query(50,ge=1,le=200),admin:User=Depends(require_admin),db:AsyncSession=Depends(get_session)):
    total=int(await db.scalar(select(func.count(EngineeringApproval.id)).where(EngineeringApproval.status=="pending")) or 0)
    rows=list((await db.scalars(select(EngineeringApproval).where(EngineeringApproval.status=="pending").order_by(EngineeringApproval.created_at.desc()).offset((page-1)*page_size).limit(page_size))).all())
    return {"items":[{"id":str(x.id),"run_id":str(x.run_id),"task_id":str(x.task_id) if x.task_id else None,"kind":x.kind,"reason":x.reason,"requested_by_agent":x.requested_by_agent,"status":x.status,"created_at":x.created_at} for x in rows],"page":page,"page_size":page_size,"total":total}


@router.post("/engineering/approvals/{approval_id}/decision")
async def decide_engineering_approval(approval_id: uuid.UUID, payload: AdminApprovalDecision, request: Request,
                                      admin: User = Depends(require_admin), db: AsyncSession = Depends(get_session)):
    """Decide a command approval for any user's run.

    The user-scoped endpoint only reaches the run owner. Operators need this to unblock a
    paused run without impersonating the owner; the decision is recorded against the admin.
    """
    approval = await db.scalar(select(EngineeringApproval).where(EngineeringApproval.id == approval_id).with_for_update())
    if not approval:
        raise AppError("APPROVAL_NOT_FOUND", "Approval not found", 404)
    if approval.status != "pending":
        raise AppError("APPROVAL_DECIDED", "Approval was already decided", 409)
    run = await db.get(EngineeringRun, approval.run_id)
    if not run:
        raise AppError("RUN_NOT_FOUND", "Run not found", 404)
    approval.status = payload.decision
    approval.decision_by_user_id = admin.id
    approval.decision_note = payload.note or f"decided by administrator {admin.email}"
    approval.decided_at = datetime.now(UTC)
    await write_audit(db, request, "engineering_approval_decision", admin.id, "engineering_approval", str(approval.id),
                      {"decision": payload.decision, "run_id": str(run.id)})
    await write_admin_action(db, admin.id, "engineering_approval_decision", "engineering_approval", str(approval.id),
                             {"decision": payload.decision, "run_id": str(run.id)})
    await db.commit()
    await db.refresh(approval)
    pending = int(await db.scalar(select(func.count(EngineeringApproval.id)).where(
        EngineeringApproval.run_id == run.id, EngineeringApproval.status == "pending")) or 0)
    if pending == 0 and run.status == "waiting_approval":
        run.stage = "resume_queued"
        await db.commit()
        from app.workers.worker import engineering_run_job
        try:
            engineering_run_job.send(str(run.id))
        except Exception:
            run.stage = "approval"
            await db.commit()
            raise
    return {"id": str(approval.id), "run_id": str(approval.run_id), "status": approval.status,
            "decided_at": approval.decided_at, "pending_remaining": pending}
