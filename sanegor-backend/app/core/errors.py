"""Application error hierarchy and the handlers that render it.

Every failure the API can produce is expressed as an :class:`AppError`
subclass.  Handlers translate those (plus framework/unknown errors) into one
stable JSON envelope so the Flutter client only has to parse a single shape::

    {"error": {"code": "not_found", "message": "...", "details": {...},
               "request_id": "..."}}
"""

from __future__ import annotations

from typing import Any

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.logging import get_logger, request_id_ctx

logger = get_logger(__name__)


class AppError(Exception):
    """Base class for every expected, client-visible failure."""

    status_code: int = status.HTTP_400_BAD_REQUEST
    code: str = "error"
    message: str = "אירעה שגיאה"

    def __init__(
        self,
        message: str | None = None,
        *,
        details: dict[str, Any] | None = None,
        code: str | None = None,
    ) -> None:
        self.message = message or self.message
        self.details = details or {}
        if code:
            self.code = code
        super().__init__(self.message)

    def to_payload(self) -> dict[str, Any]:
        return {
            "error": {
                "code": self.code,
                "message": self.message,
                "details": self.details,
                "request_id": request_id_ctx.get(),
            }
        }


class ValidationError(AppError):
    status_code = status.HTTP_422_UNPROCESSABLE_ENTITY
    code = "validation_error"
    message = "הנתונים שנשלחו אינם תקינים"


class AuthenticationError(AppError):
    status_code = status.HTTP_401_UNAUTHORIZED
    code = "unauthenticated"
    message = "נדרשת התחברות"


class PermissionDeniedError(AppError):
    status_code = status.HTTP_403_FORBIDDEN
    code = "forbidden"
    message = "אין לך הרשאה לבצע פעולה זו"


class NotFoundError(AppError):
    status_code = status.HTTP_404_NOT_FOUND
    code = "not_found"
    message = "הפריט המבוקש לא נמצא"


class ConflictError(AppError):
    status_code = status.HTTP_409_CONFLICT
    code = "conflict"
    message = "הפעולה מתנגשת עם מצב קיים"


class PayloadTooLargeError(AppError):
    status_code = status.HTTP_413_REQUEST_ENTITY_TOO_LARGE
    code = "payload_too_large"
    message = "הקובץ גדול מדי"


class UnsupportedMediaTypeError(AppError):
    status_code = status.HTTP_415_UNSUPPORTED_MEDIA_TYPE
    code = "unsupported_media_type"
    message = "סוג הקובץ אינו נתמך"


class RateLimitedError(AppError):
    status_code = status.HTTP_429_TOO_MANY_REQUESTS
    code = "rate_limited"
    message = "יותר מדי בקשות. נסה שוב בעוד רגע"

    def __init__(self, retry_after: int, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.retry_after = retry_after
        self.details.setdefault("retry_after_seconds", retry_after)


class UpstreamError(AppError):
    status_code = status.HTTP_502_BAD_GATEWAY
    code = "upstream_error"
    message = "שירות ה-AI אינו זמין כעת"


class ServiceUnavailableError(AppError):
    status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    code = "service_unavailable"
    message = "השירות אינו זמין כעת"


class FeatureDisabledError(AppError):
    status_code = status.HTTP_501_NOT_IMPLEMENTED
    code = "feature_disabled"
    message = "היכולת המבוקשת אינה מופעלת בשרת זה"


def register_exception_handlers(app: FastAPI) -> None:
    """Attach the JSON error envelope handlers to ``app``."""

    @app.exception_handler(AppError)
    async def _app_error(_request: Request, exc: AppError) -> JSONResponse:
        headers: dict[str, str] = {}
        if isinstance(exc, RateLimitedError):
            headers["Retry-After"] = str(exc.retry_after)
        if exc.status_code >= 500:
            logger.error("app_error", code=exc.code, message=exc.message, details=exc.details)
        else:
            logger.info("app_error", code=exc.code, message=exc.message)
        return JSONResponse(exc.to_payload(), status_code=exc.status_code, headers=headers)

    @app.exception_handler(RequestValidationError)
    async def _request_validation(
        _request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        fields = [
            {
                "field": ".".join(str(part) for part in err.get("loc", ())[1:]),
                "message": err.get("msg", ""),
            }
            for err in exc.errors()
        ]
        error = ValidationError(details={"fields": fields})
        return JSONResponse(error.to_payload(), status_code=error.status_code)

    @app.exception_handler(StarletteHTTPException)
    async def _http_exception(
        _request: Request, exc: StarletteHTTPException
    ) -> JSONResponse:
        error = AppError(
            str(exc.detail),
            code={401: "unauthenticated", 403: "forbidden", 404: "not_found"}.get(
                exc.status_code, "http_error"
            ),
        )
        error.status_code = exc.status_code
        return JSONResponse(
            error.to_payload(), status_code=exc.status_code, headers=exc.headers
        )

    @app.exception_handler(Exception)
    async def _unhandled(_request: Request, exc: Exception) -> JSONResponse:
        # Never leak internals to the client; the request_id ties the opaque
        # response back to this fully-detailed log line.
        logger.exception("unhandled_exception", error=str(exc))
        error = AppError("אירעה שגיאה בלתי צפויה", code="internal_error")
        error.status_code = status.HTTP_500_INTERNAL_SERVER_ERROR
        return JSONResponse(error.to_payload(), status_code=error.status_code)
