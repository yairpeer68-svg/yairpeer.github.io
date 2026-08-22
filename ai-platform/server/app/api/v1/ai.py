import uuid
from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.factory import build_provider
from app.ai.gateway import AIGateway
from app.api.dependencies.auth import current_user
from app.core.config import Settings, get_settings
from app.core.errors import AppError
from app.db.session import get_session
from app.models.entities import Device, User
from app.schemas.ai import AIChatRequest, AIChatResponse, AIUsage
from app.services.redis_service import get_redis_service

router = APIRouter()


@router.post("/chat", response_model=AIChatResponse)
async def chat(payload: AIChatRequest, request: Request, user: User = Depends(current_user),
               db: AsyncSession = Depends(get_session), settings: Settings = Depends(get_settings)):
    model = payload.model or settings.DEEPSEEK_MODEL
    messages = [{"role": m.role, "content": m.content} for m in payload.messages]
    gateway = AIGateway(db, get_redis_service(settings), settings, build_provider(settings))
    device_header = request.headers.get("X-Device-ID")
    device_id = None
    if device_header:
        try:
            device_id = uuid.UUID(device_header)
        except ValueError as exc:
            raise AppError("INVALID_DEVICE_ID", "X-Device-ID must be a valid UUID", 422) from exc
        device = await db.get(Device, device_id)
        if device is None or device.user_id != user.id or device.revoked_at is not None:
            raise AppError("INVALID_DEVICE", "Device is not registered or has been revoked", 403)
    result = await gateway.chat(request.state.request_id, user.id, device_id, messages, model,
                                payload.temperature, payload.max_tokens, payload.cache)
    return AIChatResponse(request_id=request.state.request_id, model=result["model"], content=result["content"],
                          usage=AIUsage(**result["usage"]), cache_hit=result["cache_hit"])


@router.get("/history")
async def history(limit: int = 50, user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    from sqlalchemy import select
    from app.models.entities import AIRequest
    limit = min(max(limit, 1), 200)
    items = list((await db.scalars(select(AIRequest).where(AIRequest.user_id == user.id)
                                   .order_by(AIRequest.created_at.desc()).limit(limit))).all())
    return [{"request_id": x.request_id, "model": x.model, "status": x.status,
             "total_tokens": x.total_tokens, "latency_ms": x.latency_ms,
             "cache_hit": x.cache_hit, "created_at": x.created_at} for x in items]
