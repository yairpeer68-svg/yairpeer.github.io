"""Version 1 API router assembly."""

from __future__ import annotations

from fastapi import APIRouter

from app.api.v1.routes import (
    admin,
    analysis,
    chat,
    documents,
    drafting,
    export,
    history,
    search,
    ws,
)

api_router = APIRouter()

api_router.include_router(chat.router)
api_router.include_router(history.router)
api_router.include_router(documents.router)
api_router.include_router(analysis.router)
api_router.include_router(drafting.contracts_router)
api_router.include_router(drafting.letters_router)
api_router.include_router(drafting.generated_router)
api_router.include_router(search.router)
api_router.include_router(export.router)
api_router.include_router(admin.router)
api_router.include_router(ws.router)

__all__ = ["api_router"]
