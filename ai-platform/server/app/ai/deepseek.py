import asyncio
import secrets

import httpx

from app.ai.provider import AIProvider
from app.ai.types import ProviderResponse, ProviderUsage
from app.core.config import Settings
from app.core.errors import AppError


class DeepSeekProvider(AIProvider):
    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None):
        self.settings = settings
        self._client = client

    async def chat(self, messages: list[dict[str, str]], model: str, temperature: float, max_tokens: int) -> ProviderResponse:
        if not self.settings.DEEPSEEK_API_KEY:
            raise AppError("AI_NOT_CONFIGURED", "DeepSeek API is not configured", 503)
        owned_client = self._client is None
        client = self._client or httpx.AsyncClient(
            base_url=self.settings.DEEPSEEK_BASE_URL.rstrip("/"),
            timeout=httpx.Timeout(self.settings.DEEPSEEK_TIMEOUT_SECONDS),
            limits=httpx.Limits(max_connections=100, max_keepalive_connections=20),
        )
        try:
            for attempt in range(self.settings.DEEPSEEK_MAX_RETRIES + 1):
                try:
                    response = await client.post(
                        "/chat/completions",
                        headers={"Authorization": f"Bearer {self.settings.DEEPSEEK_API_KEY}", "Content-Type": "application/json"},
                        json={"model": model, "messages": messages, "temperature": temperature, "max_tokens": max_tokens,
                              "stream": False},
                    )
                except (httpx.ConnectError, httpx.ConnectTimeout) as exc:
                    # Connection-phase failures are safe to retry: the request never
                    # reached the provider, so no completion can have been billed.
                    if attempt >= self.settings.DEEPSEEK_MAX_RETRIES:
                        raise AppError("AI_PROVIDER_TIMEOUT", "AI provider did not respond in time", 504) from exc
                    await asyncio.sleep(min(8.0, (2 ** attempt) + (secrets.randbelow(1000) / 1000)))
                    continue
                except (httpx.TimeoutException, httpx.NetworkError) as exc:
                    # A read/write timeout means the request was already accepted. Retrying
                    # can bill a second completion the caller never sees, so it is not retried.
                    raise AppError("AI_PROVIDER_TIMEOUT", "AI provider did not respond in time", 504) from exc
                if response.status_code == 429:
                    if attempt >= self.settings.DEEPSEEK_MAX_RETRIES:
                        raise AppError("AI_PROVIDER_RATE_LIMIT", "AI provider rate limit reached", 503,
                                       {"Retry-After": response.headers.get("Retry-After", "5")})
                    await asyncio.sleep(min(8.0, (2 ** attempt) + (secrets.randbelow(1000) / 1000)))
                    continue
                if response.status_code >= 500:
                    if attempt >= self.settings.DEEPSEEK_MAX_RETRIES:
                        raise AppError("AI_PROVIDER_UNAVAILABLE", "AI provider is temporarily unavailable", 503)
                    await asyncio.sleep(min(8.0, (2 ** attempt) + (secrets.randbelow(1000) / 1000)))
                    continue
                if response.status_code >= 400:
                    raise AppError("AI_PROVIDER_REJECTED", "AI provider rejected the request", 502)
                try:
                    data = response.json()
                    choice = data["choices"][0]
                    content = choice["message"]["content"]
                    usage = data.get("usage", {})
                    if not isinstance(content, str) or not content.strip():
                        raise ValueError("empty content")
                    return ProviderResponse(
                        model=str(data.get("model") or model),
                        content=content,
                        usage=ProviderUsage(
                            prompt_tokens=int(usage.get("prompt_tokens", 0)),
                            completion_tokens=int(usage.get("completion_tokens", 0)),
                            total_tokens=int(usage.get("total_tokens", 0)),
                        ),
                    )
                except (KeyError, IndexError, TypeError, ValueError) as exc:
                    raise AppError("AI_PROVIDER_INVALID_RESPONSE", "AI provider returned an invalid response", 502) from exc
            raise AppError("AI_PROVIDER_UNAVAILABLE", "AI provider is temporarily unavailable", 503)
        finally:
            if owned_client:
                await client.aclose()
