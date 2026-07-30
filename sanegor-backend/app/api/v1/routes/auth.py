"""Authentication endpoints."""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, Request, status

from app.api.deps import (
    CurrentUser,
    SessionDep,
    SettingsDep,
    client_ip,
    get_audit_service,
    get_auth_service,
    rate_limit_auth,
    user_agent,
)
from app.core.logging import get_logger
from app.db.models.audit import AuditAction
from app.schemas.auth import (
    AuthResponse,
    ChangePasswordRequest,
    ForgotPasswordRequest,
    LoginRequest,
    LogoutRequest,
    RefreshRequest,
    RegisterRequest,
    ResetPasswordRequest,
    TokenResponse,
    UpdateProfileRequest,
    UserOut,
    VerifyEmailRequest,
)
from app.schemas.common import MessageResponse
from app.services.audit import AuditService
from app.services.auth import AuthResult, AuthService

logger = get_logger(__name__)

router = APIRouter(prefix="/auth", tags=["auth"], dependencies=[Depends(rate_limit_auth)])

AuthDep = Annotated[AuthService, Depends(get_auth_service)]
AuditDep = Annotated[AuditService, Depends(get_audit_service)]


def _auth_response(result: AuthResult) -> AuthResponse:
    return AuthResponse(
        user=UserOut.model_validate(result.user),
        tokens=TokenResponse(
            access_token=result.tokens.access_token,
            refresh_token=result.tokens.refresh_token,
            expires_in=result.tokens.expires_in,
        ),
    )


@router.post(
    "/register",
    response_model=AuthResponse,
    status_code=status.HTTP_201_CREATED,
    summary="הרשמה למערכת",
)
async def register(
    payload: RegisterRequest,
    request: Request,
    service: AuthDep,
    audit: AuditDep,
) -> AuthResponse:
    """Create an account and return a token pair."""
    result = await service.register(
        email=payload.email,
        password=payload.password,
        full_name=payload.full_name,
        phone=payload.phone,
    )
    await audit.record(
        AuditAction.REGISTER,
        user_id=str(result.user.id),
        ip_address=client_ip(request),
        user_agent=user_agent(request),
    )
    # An e-mail delivery integration would send this token; it is logged in
    # development so the flow is testable without an SMTP provider.
    logger.info(
        "email_verification_token_created",
        user_id=str(result.user.id),
        token=service.create_verification_token(result.user),
    )
    return _auth_response(result)


@router.post("/login", response_model=AuthResponse, summary="התחברות")
async def login(
    payload: LoginRequest,
    request: Request,
    service: AuthDep,
    audit: AuditDep,
) -> AuthResponse:
    """Exchange credentials for a token pair."""
    try:
        result = await service.login(
            email=payload.email,
            password=payload.password,
            user_agent=user_agent(request),
            ip_address=client_ip(request),
        )
    except Exception:
        await audit.record(
            AuditAction.LOGIN_FAILED,
            outcome="failure",
            ip_address=client_ip(request),
            user_agent=user_agent(request),
            metadata={"email_domain": payload.email.split("@")[-1]},
        )
        raise

    await audit.record(
        AuditAction.LOGIN_SUCCESS,
        user_id=str(result.user.id),
        ip_address=client_ip(request),
        user_agent=user_agent(request),
    )
    return _auth_response(result)


@router.post("/refresh", response_model=AuthResponse, summary="חידוש אסימון גישה")
async def refresh(payload: RefreshRequest, service: AuthDep, audit: AuditDep) -> AuthResponse:
    """Rotate a refresh token for a new pair."""
    result = await service.refresh(payload.refresh_token)
    await audit.record(AuditAction.TOKEN_REFRESH, user_id=str(result.user.id))
    return _auth_response(result)


@router.post("/logout", response_model=MessageResponse, summary="התנתקות")
async def logout(
    payload: LogoutRequest,
    user: CurrentUser,
    service: AuthDep,
    audit: AuditDep,
) -> MessageResponse:
    """Revoke one session, or every session when ``all_devices`` is set."""
    if payload.all_devices:
        count = await service.revoke_all_tokens(str(user.id))
        await audit.record(
            AuditAction.LOGOUT, user_id=str(user.id), metadata={"revoked": count}
        )
        return MessageResponse(message="התנתקת מכל המכשירים")

    await service.logout(payload.refresh_token, user_id=str(user.id))
    await audit.record(AuditAction.LOGOUT, user_id=str(user.id))
    return MessageResponse(message="התנתקת בהצלחה")


@router.post(
    "/forgot-password", response_model=MessageResponse, summary="בקשת איפוס סיסמה"
)
async def forgot_password(
    payload: ForgotPasswordRequest, service: AuthDep, audit: AuditDep
) -> MessageResponse:
    """Start a password reset.

    The response is identical whether or not the address is registered, so the
    endpoint cannot be used to discover which e-mail addresses have accounts.
    """
    user = await service.get_by_email(payload.email)
    if user is not None:
        logger.info(
            "password_reset_token_created",
            user_id=str(user.id),
            token=service.create_reset_token(user),
        )
        await audit.record(AuditAction.PASSWORD_RESET_REQUEST, user_id=str(user.id))
    return MessageResponse(
        message="אם הכתובת רשומה במערכת, נשלח אליה קישור לאיפוס סיסמה"
    )


@router.post("/reset-password", response_model=MessageResponse, summary="איפוס סיסמה")
async def reset_password(
    payload: ResetPasswordRequest, service: AuthDep, audit: AuditDep
) -> MessageResponse:
    """Set a new password from a reset token and log out every session."""
    await service.reset_password(token=payload.token, new_password=payload.new_password)
    await audit.record(AuditAction.PASSWORD_RESET_COMPLETE)
    return MessageResponse(message="הסיסמה עודכנה. יש להתחבר מחדש")


@router.post(
    "/change-password", response_model=MessageResponse, summary="שינוי סיסמה"
)
async def change_password(
    payload: ChangePasswordRequest,
    user: CurrentUser,
    service: AuthDep,
) -> MessageResponse:
    """Change the password of the signed-in account."""
    await service.change_password(
        user_id=str(user.id),
        current_password=payload.current_password,
        new_password=payload.new_password,
    )
    return MessageResponse(message="הסיסמה שונתה. יש להתחבר מחדש")


@router.post("/verify-email", response_model=UserOut, summary="אימות כתובת דוא״ל")
async def verify_email(
    payload: VerifyEmailRequest, service: AuthDep, audit: AuditDep
) -> UserOut:
    """Mark an address verified from an e-mail token."""
    user = await service.verify_email(payload.token)
    await audit.record(AuditAction.EMAIL_VERIFIED, user_id=str(user.id))
    return UserOut.model_validate(user)


@router.get("/me", response_model=UserOut, summary="פרטי המשתמש הנוכחי")
async def me(user: CurrentUser) -> UserOut:
    """Return the signed-in account."""
    return UserOut.model_validate(user)


@router.patch("/me", response_model=UserOut, summary="עדכון פרופיל")
async def update_profile(
    payload: UpdateProfileRequest,
    user: CurrentUser,
    session: SessionDep,
    _settings: SettingsDep,
) -> UserOut:
    """Update the signed-in account's profile fields."""
    if payload.full_name is not None:
        user.full_name = payload.full_name.strip()[:120]
    if payload.phone is not None:
        user.phone = payload.phone.strip()[:32] or None
    if payload.preferences is not None:
        # Merge rather than replace so a client that knows about one setting
        # cannot wipe settings written by a newer client version.
        user.preferences = {**(user.preferences or {}), **payload.preferences}
    await session.flush()
    return UserOut.model_validate(user)
