"""Async DeepSeek client.

Wraps the OpenAI-compatible chat-completions endpoint that DeepSeek exposes,
adding: bounded retries with exponential back-off on transient failures,
server-sent-event streaming, and translation of upstream faults into the
application's error type so routes never have to know about HTTP status codes
from a third party.
"""

from __future__ import annotations

import asyncio
import json
import random
from collections.abc import AsyncIterator, Sequence
from dataclasses import dataclass, field
from typing import Any

import httpx

from app.core.config import Settings
from app.core.errors import FeatureDisabledError, UpstreamError
from app.core.logging import get_logger

logger = get_logger(__name__)

_RETRYABLE_STATUS = frozenset({408, 409, 425, 429, 500, 502, 503, 504})
_MAX_ATTEMPTS = 3


@dataclass(slots=True)
class ChatMessage:
    """One message in the DeepSeek request payload."""

    role: str
    content: str

    def to_dict(self) -> dict[str, str]:
        return {"role": self.role, "content": self.content}


@dataclass(slots=True)
class CompletionResult:
    """A non-streaming completion."""

    content: str
    model: str
    prompt_tokens: int = 0
    completion_tokens: int = 0
    finish_reason: str | None = None
    raw: dict[str, Any] = field(default_factory=dict)

    @property
    def total_tokens(self) -> int:
        return self.prompt_tokens + self.completion_tokens


class DeepSeekClient:
    """Thin, typed async wrapper over the DeepSeek chat API."""

    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        self._settings = settings
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            base_url=settings.deepseek_base_url.rstrip("/"),
            timeout=httpx.Timeout(settings.deepseek_timeout_seconds, connect=10.0),
            headers={"Content-Type": "application/json"},
            limits=httpx.Limits(max_connections=50, max_keepalive_connections=10),
        )

    @property
    def enabled(self) -> bool:
        return bool(self._settings.deepseek_api_key)

    @property
    def model(self) -> str:
        return self._settings.deepseek_chat_model

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    # ------------------------------------------------------------------ internals
    def _require_key(self) -> None:
        if not self.enabled:
            raise FeatureDisabledError(
                "מפתח DeepSeek אינו מוגדר בשרת. יש להגדיר DEEPSEEK_API_KEY"
            )

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._settings.deepseek_api_key}"}

    def _payload(
        self,
        messages: Sequence[ChatMessage],
        *,
        stream: bool,
        temperature: float | None,
        max_tokens: int | None,
        json_mode: bool,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": [m.to_dict() for m in messages],
            "temperature": (
                self._settings.deepseek_temperature if temperature is None else temperature
            ),
            "max_tokens": max_tokens or self._settings.deepseek_max_tokens,
            "stream": stream,
        }
        if json_mode:
            payload["response_format"] = {"type": "json_object"}
        if stream:
            payload["stream_options"] = {"include_usage": True}
        return payload

    @staticmethod
    async def _backoff(attempt: int) -> None:
        """Exponential back-off with jitter: ~0.5s, 1s, 2s."""
        delay = (2**attempt) * 0.5
        await asyncio.sleep(delay + random.uniform(0, 0.25))  # noqa: S311 - jitter only

    # -------------------------------------------------------------------- public
    async def complete(
        self,
        messages: Sequence[ChatMessage],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
        json_mode: bool = False,
    ) -> CompletionResult:
        """Run a blocking completion, retrying transient upstream failures."""
        self._require_key()
        payload = self._payload(
            messages,
            stream=False,
            temperature=temperature,
            max_tokens=max_tokens,
            json_mode=json_mode,
        )

        last_error: str = "unknown"
        for attempt in range(_MAX_ATTEMPTS):
            try:
                response = await self._client.post(
                    "/chat/completions", json=payload, headers=self._headers()
                )
            except httpx.TimeoutException as exc:
                last_error = f"timeout: {exc}"
            except httpx.HTTPError as exc:
                last_error = f"transport: {exc}"
            else:
                if response.status_code == 200:
                    return self._parse_completion(response.json())
                last_error = f"http {response.status_code}: {response.text[:300]}"
                if response.status_code not in _RETRYABLE_STATUS:
                    logger.error("deepseek_failed", status=response.status_code)
                    raise UpstreamError(self._message_for_status(response.status_code))

            logger.warning("deepseek_retry", attempt=attempt + 1, error=last_error)
            if attempt < _MAX_ATTEMPTS - 1:
                await self._backoff(attempt)

        raise UpstreamError(details={"reason": last_error})

    async def stream(
        self,
        messages: Sequence[ChatMessage],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> AsyncIterator[str]:
        """Yield answer deltas as they arrive.

        Streaming is not retried: once the first token has reached the client a
        silent restart would corrupt the visible answer.
        """
        self._require_key()
        payload = self._payload(
            messages,
            stream=True,
            temperature=temperature,
            max_tokens=max_tokens,
            json_mode=False,
        )

        try:
            async with self._client.stream(
                "POST", "/chat/completions", json=payload, headers=self._headers()
            ) as response:
                if response.status_code != 200:
                    body = (await response.aread()).decode(errors="replace")[:300]
                    logger.error("deepseek_stream_failed", status=response.status_code, body=body)
                    raise UpstreamError(self._message_for_status(response.status_code))

                async for line in response.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if not data or data == "[DONE]":
                        continue
                    delta = self._parse_stream_chunk(data)
                    if delta:
                        yield delta
        except httpx.TimeoutException as exc:
            raise UpstreamError("פסק זמן בתשובת ה-AI") from exc
        except httpx.HTTPError as exc:
            raise UpstreamError(details={"reason": str(exc)}) from exc

    # ------------------------------------------------------------------ parsing
    @staticmethod
    def _parse_stream_chunk(data: str) -> str:
        try:
            chunk = json.loads(data)
        except json.JSONDecodeError:
            logger.debug("deepseek_bad_chunk", data=data[:120])
            return ""
        choices = chunk.get("choices") or []
        if not choices:
            return ""
        return str(choices[0].get("delta", {}).get("content") or "")

    @staticmethod
    def _parse_completion(body: dict[str, Any]) -> CompletionResult:
        choices = body.get("choices") or []
        if not choices:
            raise UpstreamError("שירות ה-AI החזיר תשובה ריקה")
        usage = body.get("usage") or {}
        return CompletionResult(
            content=str(choices[0].get("message", {}).get("content") or ""),
            model=str(body.get("model", "")),
            prompt_tokens=int(usage.get("prompt_tokens", 0)),
            completion_tokens=int(usage.get("completion_tokens", 0)),
            finish_reason=choices[0].get("finish_reason"),
            raw=body,
        )

    @staticmethod
    def _message_for_status(status: int) -> str:
        return {
            401: "מפתח ה-API של DeepSeek אינו תקף",
            402: "אין יתרה מספקת בחשבון ה-AI",
            429: "עומס על שירות ה-AI. נסה שוב בעוד רגע",
        }.get(status, "שירות ה-AI אינו זמין כעת")


async def parse_json_response(result: CompletionResult) -> dict[str, Any]:
    """Parse a JSON-mode completion, tolerating markdown code fences.

    Models occasionally wrap JSON in ```json fences even in JSON mode; rather
    than fail the whole request we strip them before parsing.
    """
    text = result.content.strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[-1] if "\n" in text else text
        text = text.removesuffix("```").strip()
        if text.startswith("json"):
            text = text[4:].strip()
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError as exc:
        logger.error("ai_json_parse_failed", preview=text[:200])
        raise UpstreamError("תשובת ה-AI לא הייתה בפורמט הצפוי") from exc
    if not isinstance(parsed, dict):
        raise UpstreamError("תשובת ה-AI לא הייתה בפורמט הצפוי")
    return parsed
