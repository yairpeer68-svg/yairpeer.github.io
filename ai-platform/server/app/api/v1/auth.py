from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies.auth import AuthContext, current_user, get_auth_context
from app.api.dependencies.rate_limit import auth_rate_limit
from app.core.config import Settings, get_settings
from app.db.session import get_session
from app.models.entities import User
from app.repositories.users import UserRepository
from app.schemas.auth import (ChangePasswordRequest, ForgotPasswordRequest, LoginRequest, LogoutRequest,
                              RefreshRequest, RegisterRequest, ResetPasswordRequest, TokenPair, UserOut,
                              VerifyEmailRequest)
from app.schemas.common import MessageResponse
from app.services.auth_service import AuthService
from app.services.token_service import AccountTokenService

router = APIRouter()


def token_pair(settings: Settings, access: str, refresh: str) -> TokenPair:
    return TokenPair(access_token=access, refresh_token=refresh, expires_in=settings.ACCESS_TOKEN_MINUTES * 60)


@router.post("/register", response_model=UserOut, status_code=201, dependencies=[Depends(auth_rate_limit)])
async def register(payload: RegisterRequest, request: Request, db: AsyncSession = Depends(get_session),
                   settings: Settings = Depends(get_settings)):
    user = await AuthService(db, settings).register(request, payload.email, payload.password, payload.display_name)
    if settings.email_configured:
        raw = await AccountTokenService(db).create_email_verification(user)
        from app.workers.worker import send_transactional_email
        link = f"{settings.APP_BASE_URL.rstrip('/')}/verify-email?token={raw}"
        send_transactional_email.send(user.email, "Verify your AI Platform email", f"Verify your email: {link}")
    return user


@router.post("/login", response_model=TokenPair, dependencies=[Depends(auth_rate_limit)])
async def login(payload: LoginRequest, request: Request, db: AsyncSession = Depends(get_session),
                settings: Settings = Depends(get_settings)):
    _, access, refresh = await AuthService(db, settings).login(request, payload.email, payload.password, payload.device_id)
    return token_pair(settings, access, refresh)


@router.post("/refresh", response_model=TokenPair, dependencies=[Depends(auth_rate_limit)])
async def refresh(payload: RefreshRequest, request: Request, db: AsyncSession = Depends(get_session),
                  settings: Settings = Depends(get_settings)):
    access, new_refresh = await AuthService(db, settings).rotate_refresh(request, payload.refresh_token)
    return token_pair(settings, access, new_refresh)


@router.post("/logout", response_model=MessageResponse)
async def logout(payload: LogoutRequest, request: Request, ctx: AuthContext = Depends(get_auth_context),
                 db: AsyncSession = Depends(get_session), settings: Settings = Depends(get_settings)):
    await AuthService(db, settings).logout(request, ctx.user, ctx.session_id, payload.refresh_token)
    return MessageResponse(message="Logged out")


@router.post("/revoke-all", response_model=MessageResponse)
async def revoke_all(request: Request, ctx: AuthContext = Depends(get_auth_context), db: AsyncSession = Depends(get_session),
                     settings: Settings = Depends(get_settings)):
    await AuthService(db, settings).revoke_all(request, ctx.user)
    return MessageResponse(message="All sessions revoked")


@router.post("/change-password", response_model=MessageResponse)
async def change_password(payload: ChangePasswordRequest, request: Request, user: User = Depends(current_user),
                          db: AsyncSession = Depends(get_session), settings: Settings = Depends(get_settings)):
    await AuthService(db, settings).change_password(request, user, payload.current_password, payload.new_password)
    return MessageResponse(message="Password changed; all sessions revoked")


@router.post("/forgot-password", response_model=MessageResponse, status_code=202, dependencies=[Depends(auth_rate_limit)])
async def forgot_password(payload: ForgotPasswordRequest, db: AsyncSession = Depends(get_session),
                          settings: Settings = Depends(get_settings)):
    user = await UserRepository(db).by_email(payload.email.strip().lower())
    if user and user.is_active and settings.email_configured:
        raw = await AccountTokenService(db).create_password_reset(user)
        from app.workers.worker import send_transactional_email
        link = f"{settings.APP_BASE_URL.rstrip('/')}/reset-password?token={raw}"
        send_transactional_email.send(user.email, "Reset your AI Platform password", f"Reset your password: {link}")
    return MessageResponse(message="If the account exists, reset instructions will be sent")


@router.post("/reset-password", response_model=MessageResponse)
async def reset_password(payload: ResetPasswordRequest, request: Request, db: AsyncSession = Depends(get_session)):
    await AccountTokenService(db).reset_password(request, payload.token, payload.new_password)
    return MessageResponse(message="Password reset completed")


@router.post("/verify-email", response_model=MessageResponse)
async def verify_email(payload: VerifyEmailRequest, request: Request, db: AsyncSession = Depends(get_session)):
    await AccountTokenService(db).verify_email(request, payload.token)
    return MessageResponse(message="Email verified")
