import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, Request
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies.auth import AuthContext, get_auth_context, current_user
from app.core.config import Settings, get_settings
from app.core.errors import AppError
from app.db.session import get_session
from app.models.entities import Device, User, Session, RefreshToken
from app.repositories.audit import write_audit
from app.schemas.common import MessageResponse
from app.schemas.devices import DeviceOut, DeviceRegister
from app.security.play_integrity import PlayIntegrityVerifier

router = APIRouter()


@router.post("/register", response_model=DeviceOut, status_code=201)
async def register_device(payload: DeviceRegister, request: Request, ctx: AuthContext = Depends(get_auth_context),
                          db: AsyncSession = Depends(get_session), settings: Settings = Depends(get_settings)):
    user = ctx.user
    device = await db.scalar(select(Device).where(Device.user_id == user.id,
                                                  Device.installation_id == payload.installation_id))
    attestation = await PlayIntegrityVerifier(settings).verify(payload.attestation_token or "")
    if device is None:
        device = Device(user_id=user.id, device_id=payload.device_id, installation_id=payload.installation_id,
                        platform=payload.platform, device_name=payload.device_name, app_version=payload.app_version,
                        os_version=payload.os_version, push_token=payload.push_token, trusted=attestation.valid)
        db.add(device)
    else:
        if device.revoked_at is not None:
            raise AppError("DEVICE_REVOKED", "This device installation has been revoked", 403)
        device.device_id = payload.device_id
        device.device_name = payload.device_name
        device.app_version = payload.app_version
        device.os_version = payload.os_version
        device.push_token = payload.push_token
        device.last_seen = datetime.now(UTC)
        device.trusted = attestation.valid
    await db.flush()
    session = await db.get(Session, ctx.session_id)
    if session is not None:
        session.device_id = device.id
        await db.execute(update(RefreshToken).where(RefreshToken.session_id == ctx.session_id, RefreshToken.revoked_at.is_(None)).values(device_id=device.id))
    await write_audit(db, request, "device_registration", user.id, "device", str(device.id),
                      {"attestation_status": attestation.status})
    await db.commit(); await db.refresh(device)
    return device


@router.get("", response_model=list[DeviceOut])
async def list_devices(user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    return list((await db.scalars(select(Device).where(Device.user_id == user.id).order_by(Device.created_at.desc()))).all())


async def _revoke(device_id: uuid.UUID, request: Request, user: User, db: AsyncSession):
    device = await db.get(Device, device_id)
    if not device or device.user_id != user.id:
        raise AppError("DEVICE_NOT_FOUND", "Device not found", 404)
    if device.revoked_at is None:
        device.revoked_at = datetime.now(UTC)
    now = datetime.now(UTC)
    await db.execute(update(Session).where(Session.device_id == device.id, Session.revoked_at.is_(None)).values(revoked_at=now))
    await db.execute(update(RefreshToken).where(RefreshToken.device_id == device.id, RefreshToken.revoked_at.is_(None)).values(revoked_at=now, revoke_reason="device_revoked"))
    await write_audit(db, request, "device_revocation", user.id, "device", str(device.id))
    await db.commit()


@router.delete("/{device_id}", response_model=MessageResponse)
async def delete_device(device_id: uuid.UUID, request: Request, user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    await _revoke(device_id, request, user, db); return MessageResponse(message="Device revoked")


@router.post("/{device_id}/revoke", response_model=MessageResponse)
async def revoke_device(device_id: uuid.UUID, request: Request, user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    await _revoke(device_id, request, user, db); return MessageResponse(message="Device revoked")
