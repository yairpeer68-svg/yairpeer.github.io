from app.ai.deepseek import DeepSeekProvider
from app.ai.provider import AIProvider
from app.ai.types import ProviderResponse, ProviderUsage
from app.core.config import Settings
from app.core.errors import AppError


class DeterministicTestProvider(AIProvider):
    async def chat(self, messages, model, temperature, max_tokens):
        text = messages[-1]["content"] if messages else ""
        content = f"[mock] {text[:max(1, min(len(text), 512))]}"
        prompt_tokens = max(1, sum(len(m["content"]) for m in messages) // 4)
        completion_tokens = max(1, len(content) // 4)
        return ProviderResponse(model, content, ProviderUsage(prompt_tokens, completion_tokens, prompt_tokens + completion_tokens))


def build_provider(settings: Settings) -> AIProvider:
    if settings.AI_PROVIDER_MODE == "mock":
        if settings.APP_ENV != "test":
            raise AppError("MOCK_PROVIDER_FORBIDDEN", "Mock AI provider is allowed only in test environment", 503)
        return DeterministicTestProvider()
    return DeepSeekProvider(settings)
