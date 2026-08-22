import logging
from dataclasses import dataclass

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException
from fastapi.responses import JSONResponse


@dataclass
class AppError(Exception):
    code: str
    message: str
    status_code: int = 400
    headers: dict[str, str] | None = None


def error_payload(request: Request, code: str, message: str) -> dict:
    return {"error": {"code": code, "message": message, "request_id": getattr(request.state, "request_id", None)}}


async def app_error_handler(request: Request, exc: AppError) -> JSONResponse:
    return JSONResponse(error_payload(request, exc.code, exc.message), status_code=exc.status_code, headers=exc.headers)


async def unhandled_error_handler(request: Request, exc: Exception) -> JSONResponse:
    logging.getLogger("errors").exception("unhandled_error", extra={"request_id": getattr(request.state, "request_id", None), "event": type(exc).__name__})
    return JSONResponse(error_payload(request, "INTERNAL_ERROR", "An internal error occurred"), status_code=500)


async def validation_error_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    return JSONResponse(error_payload(request, "VALIDATION_ERROR", "Request validation failed"), status_code=422)


async def http_error_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
    message = exc.detail if isinstance(exc.detail, str) else "HTTP request failed"
    return JSONResponse(error_payload(request, f"HTTP_{exc.status_code}", message), status_code=exc.status_code, headers=exc.headers)
