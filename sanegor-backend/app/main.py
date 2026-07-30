"""FastAPI application factory and lifespan wiring."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.middleware.trustedhost import TrustedHostMiddleware
from starlette.middleware.httpsredirect import HTTPSRedirectMiddleware

from app.api.v1.router import api_router
from app.api.v1.routes import auth as auth_routes
from app.api.v1.routes import health as health_routes
from app.core.config import Settings, get_settings
from app.core.errors import register_exception_handlers
from app.core.logging import configure_logging, get_logger
from app.core.middleware import (
    BodySizeLimitMiddleware,
    RequestContextMiddleware,
    SecurityHeadersMiddleware,
)
from app.core.rate_limit import RateLimiter
from app.core.security import TextCipher
from app.db.session import Database
from app.services.ai.deepseek import DeepSeekClient
from app.services.ai.embeddings import build_embedding_provider
from app.services.cache import CacheService
from app.services.documents.extractor import DocumentExtractor
from app.services.documents.ocr import OcrService
from app.services.storage import FileStorage

logger = get_logger(__name__)

VERSION = "1.0.0"

DESCRIPTION = """
**סנגור** — ממשק ה-API של עוזר משפטי דיגיטלי לדין הישראלי.

המערכת משלבת אחזור מבוסס מקורות (RAG) עם מודל שפה, כך שתשובות מגובות
בקטעי חקיקה ופסיקה שנטענו למאגר.

### עקרונות
* **אסמכתאות בלבד מהמאגר** — ציטוט מוחזר רק כאשר הוא מתאים לרשומה קיימת
  בטבלת המקורות. סימוני מקור שהמודל המציא מוסרים מהתשובה.
* **אין ייעוץ משפטי** — כל תשובה מלווה בהסתייגות; המידע כללי בלבד.
* **פרטיות** — טקסט מסמכים מוצפן במנוחה, וקבצים נמחקים בפועל בעת מחיקה.

### תגובות שגיאה
כל שגיאה מוחזרת במעטפת אחידה:
```json
{"error": {"code": "not_found", "message": "...", "details": {}, "request_id": "..."}}
```
"""

TAGS_METADATA = [
    {"name": "auth", "description": "הרשמה, התחברות, אסימונים וניהול פרופיל"},
    {"name": "chat", "description": "צ'אט משפטי עם Streaming ואסמכתאות"},
    {"name": "history", "description": "היסטוריית שיחות, נעיצה וחיפוש"},
    {"name": "documents", "description": "העלאת מסמכים, OCR וחילוץ טקסט"},
    {"name": "analysis", "description": "ניתוח מסמכים, ניתוח חוזים וסיכום פסיקה"},
    {"name": "contracts", "description": "תבניות ויצירת חוזים"},
    {"name": "letters", "description": "תבניות ויצירת מכתבים משפטיים"},
    {"name": "generated", "description": "מסמכים שנוצרו על ידי המערכת"},
    {"name": "search", "description": "חיפוש בחקיקה ובפסיקה"},
    {"name": "export", "description": "ייצוא ל-PDF, DOCX ו-Markdown"},
    {"name": "admin", "description": "ניהול מאגר המקורות והרשאות"},
    {"name": "health", "description": "בדיקות בריאות וזמינות"},
]


async def _build_redis(settings: Settings) -> object | None:
    """Connect to Redis, returning ``None`` when it is unavailable.

    Redis is optional: without it the cache is a no-op and rate limiting fails
    open. That is a deliberate availability trade-off, logged loudly.
    """
    try:
        from redis.asyncio import Redis

        client = Redis.from_url(
            settings.redis_url,
            encoding="utf-8",
            decode_responses=True,
            socket_connect_timeout=3,
            socket_timeout=3,
            health_check_interval=30,
        )
        await client.ping()
        logger.info("redis_connected")
        return client
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "redis_unavailable",
            error=str(exc),
            impact="caching disabled, rate limiting fails open",
        )
        return None


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Create shared resources on start-up and release them on shutdown."""
    settings: Settings = app.state.settings

    app.state.database = Database(settings)
    app.state.redis = await _build_redis(settings)
    app.state.cache = CacheService(
        app.state.redis, default_ttl=settings.cache_ttl_seconds
    )
    app.state.rate_limiter = RateLimiter(app.state.redis)
    app.state.deepseek = DeepSeekClient(settings)
    app.state.embeddings = build_embedding_provider(settings)
    app.state.cipher = TextCipher(settings.encryption_key)
    app.state.storage = FileStorage(settings.storage_dir)
    app.state.ocr = OcrService(
        enabled=settings.ocr_enabled,
        languages=settings.ocr_languages,
        tesseract_cmd=settings.tesseract_cmd,
    )
    app.state.extractor = DocumentExtractor(app.state.ocr)

    if not settings.deepseek_api_key:
        logger.warning(
            "deepseek_not_configured",
            impact="AI endpoints will return 501 until DEEPSEEK_API_KEY is set",
        )
    if not settings.encryption_key:
        logger.warning(
            "encryption_key_not_set",
            impact="extracted document text is stored unencrypted",
        )

    await _bootstrap_admin(app, settings)

    logger.info(
        "application_started",
        environment=settings.environment,
        version=VERSION,
        rag_enabled=settings.rag_enabled,
    )
    try:
        yield
    finally:
        await app.state.deepseek.aclose()
        await app.state.embeddings.aclose()
        if app.state.redis is not None:
            await app.state.redis.aclose()
        await app.state.database.dispose()
        logger.info("application_stopped")


async def _bootstrap_admin(app: FastAPI, settings: Settings) -> None:
    """Create or promote the configured bootstrap administrator.

    Without this a fresh deployment has no way to reach the admin endpoints
    that load the legal corpus.
    """
    if not (settings.bootstrap_admin_email and settings.bootstrap_admin_password):
        return

    from app.db.models.user import UserRole
    from app.services.auth import AuthService

    try:
        async with app.state.database.session() as session:
            service = AuthService(session, settings)
            user = await service.get_by_email(settings.bootstrap_admin_email)
            if user is None:
                result = await service.register(
                    email=settings.bootstrap_admin_email,
                    password=settings.bootstrap_admin_password,
                    full_name="מנהל מערכת",
                )
                user = result.user
            user.role = UserRole.ADMIN
            user.is_email_verified = True
            logger.info("bootstrap_admin_ready", user_id=str(user.id))
    except Exception as exc:  # noqa: BLE001 - must not block start-up
        logger.error("bootstrap_admin_failed", error=str(exc))


def create_app(settings: Settings | None = None) -> FastAPI:
    """Build the ASGI application.

    Middleware is added outermost-first: Starlette applies them in reverse, so
    the request-context middleware listed first is the one that actually runs
    first and every later log line carries a request id.
    """
    settings = settings or get_settings()
    configure_logging(settings.log_level, settings.log_json)

    app = FastAPI(
        title=settings.app_name,
        description=DESCRIPTION,
        version=VERSION,
        openapi_tags=TAGS_METADATA,
        docs_url="/docs" if not settings.is_production else None,
        redoc_url="/redoc" if not settings.is_production else None,
        openapi_url="/openapi.json",
        lifespan=lifespan,
        swagger_ui_parameters={"defaultModelsExpandDepth": 0, "persistAuthorization": True},
    )
    app.state.settings = settings

    app.add_middleware(RequestContextMiddleware)
    app.add_middleware(SecurityHeadersMiddleware, hsts=settings.force_https)
    app.add_middleware(
        BodySizeLimitMiddleware,
        # Multipart framing adds overhead on top of the raw file bytes.
        max_bytes=settings.max_upload_bytes + 2 * 1024 * 1024,
    )
    # SSE responses must not be buffered by the compressor.
    app.add_middleware(GZipMiddleware, minimum_size=1024, compresslevel=6)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=True,
        allow_methods=["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type", "X-Request-ID"],
        expose_headers=["X-Request-ID", "X-Response-Time-ms", "Retry-After"],
        max_age=600,
    )
    if settings.allowed_hosts != ["*"]:
        app.add_middleware(TrustedHostMiddleware, allowed_hosts=settings.allowed_hosts)
    if settings.force_https:
        app.add_middleware(HTTPSRedirectMiddleware)

    register_exception_handlers(app)

    app.include_router(health_routes.router)
    app.include_router(auth_routes.router, prefix=settings.api_v1_prefix)
    app.include_router(api_router, prefix=settings.api_v1_prefix)

    @app.get("/", include_in_schema=False)
    async def root() -> dict[str, str]:
        return {
            "service": settings.app_name,
            "version": VERSION,
            "docs": "/docs" if not settings.is_production else "disabled",
            "health": "/health",
            "notice": "המידע במערכת הוא מידע משפטי כללי ואינו מהווה ייעוץ משפטי.",
        }

    return app


app = create_app()
