"""WebSocket chat channel.

An alternative to the SSE endpoint for clients that prefer a duplex socket: it
allows cancelling an in-flight answer, which SSE cannot do without tearing down
the connection.

Protocol (JSON frames both ways)::

    → {"type": "auth",   "token": "<access token>"}
    ← {"type": "ready"}
    → {"type": "message","content": "...", "conversation_id": "..."}
    ← {"type": "start"|"delta"|"done"|"error", ...}
    → {"type": "cancel"}
    → {"type": "ping"}                        ← {"type": "pong"}
"""

from __future__ import annotations

import asyncio
import json
from typing import Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect, status

from app.core.config import get_settings
from app.core.errors import AppError, AuthenticationError
from app.core.logging import get_logger
from app.core.security import TokenService
from app.services.ai.deepseek import DeepSeekClient
from app.services.auth import AuthService
from app.services.chat import ChatService
from app.services.rag.pipeline import RagPipeline

logger = get_logger(__name__)
router = APIRouter(tags=["chat"])

# A client that never authenticates must not hold a socket open indefinitely.
_AUTH_TIMEOUT_SECONDS = 15.0
_MAX_MESSAGE_CHARS = 16_000


@router.websocket("/ws/chat")
async def chat_socket(websocket: WebSocket) -> None:
    """Duplex chat channel with cancellation support."""
    await websocket.accept()
    settings = get_settings()

    try:
        user_id = await asyncio.wait_for(
            _authenticate(websocket, settings), timeout=_AUTH_TIMEOUT_SECONDS
        )
    except TimeoutError:
        await _send(websocket, {"type": "error", "code": "auth_timeout"})
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return
    except AuthenticationError as exc:
        await _send(websocket, {"type": "error", "code": exc.code, "message": exc.message})
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return
    except WebSocketDisconnect:
        return

    await _send(websocket, {"type": "ready"})
    logger.info("ws_connected", user_id=user_id)

    generation: asyncio.Task[None] | None = None
    try:
        while True:
            frame = await _receive(websocket)
            if frame is None:
                continue
            kind = frame.get("type")

            if kind == "ping":
                await _send(websocket, {"type": "pong"})
            elif kind == "cancel":
                if generation is not None and not generation.done():
                    generation.cancel()
                    await _send(websocket, {"type": "cancelled"})
            elif kind == "message":
                if generation is not None and not generation.done():
                    await _send(
                        websocket,
                        {"type": "error", "code": "busy", "message": "תשובה כבר בהפקה"},
                    )
                    continue
                generation = asyncio.create_task(
                    _generate(websocket, frame, user_id=user_id)
                )
            else:
                await _send(websocket, {"type": "error", "code": "unknown_frame"})
    except WebSocketDisconnect:
        logger.info("ws_disconnected", user_id=user_id)
    finally:
        if generation is not None and not generation.done():
            generation.cancel()


async def _authenticate(websocket: WebSocket, settings: Any) -> str:
    """Consume the first frame and resolve it to a user id."""
    frame = await _receive(websocket)
    if frame is None or frame.get("type") != "auth" or not frame.get("token"):
        raise AuthenticationError("נדרש אימות")

    claims = TokenService(settings).decode(str(frame["token"]), "access")
    async with websocket.app.state.database.session() as session:
        user = await AuthService(session, settings).get_by_id(claims.subject)
        if user is None or not user.is_active:
            raise AuthenticationError("החשבון אינו פעיל")
        return str(user.id)


async def _generate(websocket: WebSocket, frame: dict[str, Any], *, user_id: str) -> None:
    """Stream one answer over the socket."""
    content = str(frame.get("content", "")).strip()
    if not content:
        await _send(websocket, {"type": "error", "code": "empty_message"})
        return
    if len(content) > _MAX_MESSAGE_CHARS:
        await _send(websocket, {"type": "error", "code": "message_too_long"})
        return

    settings = get_settings()
    state = websocket.app.state
    try:
        async with state.database.session() as session:
            service = ChatService(
                session,
                state.deepseek,
                RagPipeline(session, state.embeddings, settings),
                settings,
            )
            async for sse_frame in service.stream(
                content,
                user_id=user_id,
                conversation_id=frame.get("conversation_id"),
            ):
                await websocket.send_text(_sse_to_json(sse_frame))
    except asyncio.CancelledError:
        logger.info("ws_generation_cancelled", user_id=user_id)
        raise
    except AppError as exc:
        await _send(websocket, {"type": "error", "code": exc.code, "message": exc.message})
    except Exception as exc:  # noqa: BLE001
        logger.exception("ws_generation_failed", error=str(exc))
        await _send(websocket, {"type": "error", "code": "internal_error"})


def _sse_to_json(frame: str) -> str:
    """Convert an SSE frame from :class:`ChatService` into a WS JSON frame."""
    event = "message"
    data: dict[str, Any] = {}
    for line in frame.strip().splitlines():
        if line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:"):
            try:
                data = json.loads(line[5:].strip())
            except json.JSONDecodeError:
                data = {}
    return json.dumps({"type": event, **data}, ensure_ascii=False)


async def _receive(websocket: WebSocket) -> dict[str, Any] | None:
    """Read one JSON frame, returning ``None`` for malformed input."""
    raw = await websocket.receive_text()
    try:
        frame = json.loads(raw)
    except json.JSONDecodeError:
        await _send(websocket, {"type": "error", "code": "invalid_json"})
        return None
    return frame if isinstance(frame, dict) else None


async def _send(websocket: WebSocket, payload: dict[str, Any]) -> None:
    await websocket.send_text(json.dumps(payload, ensure_ascii=False))
