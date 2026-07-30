"""FastAPI dependencies: sessions, authentication, RBAC and rate limiting.

Long-lived objects (engine, HTTP clients, storage) are created once during
start-up and hung off ``app.state``; the helpers here read them from the
request rather than constructing per-request singletons.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings, get_settings
from app.core.errors import AuthenticationError, PermissionDeniedError
from app.core.logging import user_id_ctx
from app.core.rate_limit import RateLimiter
from app.core.security import TextCipher, TokenService
from app.db.models.user import User, UserRole
from app.services.ai.deepseek import DeepSeekClient
from app.services.ai.embeddings import EmbeddingProvider
from app.services.audit import AuditService
from app.services.auth import AuthService
from app.services.cache import CacheService
from app.services.chat import ChatService
from app.services.documents.extractor import DocumentExtractor
from app.services.documents.service import DocumentService
from app.services.legal.analysis import AnalysisService
from app.services.legal.drafting import DraftingService
from app.services.rag.pipeline import RagPipeline
from app.services.storage import FileStorage

# ``auto_error=False`` so a missing header produces our Hebrew 401 envelope
# rather than FastAPI's default English one.
bearer_scheme = HTTPBearer(auto_error=False, description="JWT access token")


# ------------------------------------------------------------------ singletons
def get_app_settings() -> Settings:
    return get_settings()


async def get_session(request: Request) -> AsyncIterator[AsyncSession]:
    """Per-request transactional session."""
    async with request.app.state.database.session() as session:
        yield session


def get_deepseek(request: Request) -> DeepSeekClient:
    return request.app.state.deepseek


def get_embeddings(request: Request) -> EmbeddingProvider:
    return request.app.state.embeddings


def get_storage(request: Request) -> FileStorage:
    return request.app.state.storage


def get_extractor(request: Request) -> DocumentExtractor:
    return request.app.state.extractor


def get_cipher(request: Request) -> TextCipher:
    return request.app.state.cipher


def get_cache(request: Request) -> CacheService:
    return request.app.state.cache


def get_rate_limiter(request: Request) -> RateLimiter:
    return request.app.state.rate_limiter


# -------------------------------------------------------------------- services
SessionDep = Annotated[AsyncSession, Depends(get_session)]
SettingsDep = Annotated[Settings, Depends(get_app_settings)]


def get_auth_service(session: SessionDep, settings: SettingsDep) -> AuthService:
    return AuthService(session, settings)


def get_audit_service(session: SessionDep) -> AuditService:
    return AuditService(session)


def get_rag_pipeline(
    session: SessionDep,
    settings: SettingsDep,
    embeddings: Annotated[EmbeddingProvider, Depends(get_embeddings)],
) -> RagPipeline:
    return RagPipeline(session, embeddings, settings)


def get_chat_service(
    session: SessionDep,
    settings: SettingsDep,
    deepseek: Annotated[DeepSeekClient, Depends(get_deepseek)],
    rag: Annotated[RagPipeline, Depends(get_rag_pipeline)],
) -> ChatService:
    return ChatService(session, deepseek, rag, settings)


def get_document_service(
    session: SessionDep,
    settings: SettingsDep,
    storage: Annotated[FileStorage, Depends(get_storage)],
    extractor: Annotated[DocumentExtractor, Depends(get_extractor)],
    cipher: Annotated[TextCipher, Depends(get_cipher)],
) -> DocumentService:
    return DocumentService(session, storage, extractor, cipher, settings)


def get_drafting_service(
    session: SessionDep,
    settings: SettingsDep,
    deepseek: Annotated[DeepSeekClient, Depends(get_deepseek)],
    rag: Annotated[RagPipeline, Depends(get_rag_pipeline)],
) -> DraftingService:
    return DraftingService(session, deepseek, rag, settings)


def get_analysis_service(
    session: SessionDep,
    settings: SettingsDep,
    deepseek: Annotated[DeepSeekClient, Depends(get_deepseek)],
    rag: Annotated[RagPipeline, Depends(get_rag_pipeline)],
    cipher: Annotated[TextCipher, Depends(get_cipher)],
) -> AnalysisService:
    return AnalysisService(session, deepseek, rag, cipher, settings)


# -------------------------------------------------------------- authentication
async def get_current_user(
    request: Request,
    session: SessionDep,
    settings: SettingsDep,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)] = None,
) -> User:
    """Resolve the bearer token to an active user."""
    if credentials is None or not credentials.credentials:
        raise AuthenticationError()

    claims = TokenService(settings).decode(credentials.credentials, "access")
    user = await AuthService(session, settings).get_by_id(claims.subject)

    if user is None or not user.is_active:
        raise AuthenticationError("החשבון אינו פעיל")

    # Role changes take effect on the next request rather than at token expiry.
    if user.role.value != claims.role:
        raise AuthenticationError("ההרשאות השתנו. יש להתחבר מחדש")

    user_id_ctx.set(str(user.id))
    request.state.user = user
    return user


async def get_optional_user(
    request: Request,
    session: SessionDep,
    settings: SettingsDep,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)] = None,
) -> User | None:
    """Like :func:`get_current_user` but tolerates an anonymous caller."""
    if credentials is None or not credentials.credentials:
        return None
    try:
        return await get_current_user(request, session, settings, credentials)
    except AuthenticationError:
        return None


CurrentUser = Annotated[User, Depends(get_current_user)]
OptionalUser = Annotated[User | None, Depends(get_optional_user)]


def RequireRole(minimum: UserRole):
    """Build a dependency that enforces a minimum role.

    Returns a closure rather than a callable class: FastAPI resolves a
    dependency's annotations through ``call.__globals__``, which a class
    *instance* does not have, so ``from __future__ import annotations`` would
    leave its parameters unresolved and FastAPI would treat them as body
    fields.

    Usage::

        @router.post("/x", dependencies=[Depends(require_admin)])
    """

    async def dependency(user: CurrentUser) -> User:
        if not user.role.satisfies(minimum):
            raise PermissionDeniedError(
                "אין לך הרשאה לבצע פעולה זו",
                details={"required_role": minimum.value},
            )
        return user

    return dependency


require_lawyer = RequireRole(UserRole.LAWYER)
require_admin = RequireRole(UserRole.ADMIN)


# --------------------------------------------------------------- rate limiting
def _rate_limit_identity(request: Request) -> str:
    """Per-user when authenticated, per-client-IP otherwise.

    ``X-Forwarded-For`` is preferred so a reverse proxy does not collapse every
    caller into a single bucket.
    """
    user = getattr(request.state, "user", None)
    if user is not None:
        return f"user:{user.id}"
    forwarded = request.headers.get("x-forwarded-for", "")
    address = (
        forwarded.split(",")[0].strip()
        if forwarded
        else (request.client.host if request.client else "unknown")
    )
    return f"ip:{address}"


def RateLimit(bucket: str):
    """Build a dependency applying the named rate-limit bucket.

    A closure for the same reason as :func:`RequireRole`.
    """

    async def dependency(
        request: Request,
        settings: SettingsDep,
        limiter: Annotated[RateLimiter, Depends(get_rate_limiter)],
    ) -> None:
        await limiter.check(_rate_limit_identity(request), bucket, settings.rate_limit(bucket))

    return dependency


rate_limit_default = RateLimit("default")
rate_limit_auth = RateLimit("auth")
rate_limit_ai = RateLimit("ai")
rate_limit_upload = RateLimit("upload")


def client_ip(request: Request) -> str:
    """Best-effort client IP for audit records."""
    forwarded = request.headers.get("x-forwarded-for", "")
    if forwarded:
        return forwarded.split(",")[0].strip()[:64]
    return (request.client.host if request.client else "unknown")[:64]


def user_agent(request: Request) -> str:
    return request.headers.get("user-agent", "")[:255]
