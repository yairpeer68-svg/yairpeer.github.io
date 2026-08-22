from abc import ABC, abstractmethod

from app.ai.types import ProviderResponse


class AIProvider(ABC):
    @abstractmethod
    async def chat(self, messages: list[dict[str, str]], model: str, temperature: float, max_tokens: int) -> ProviderResponse:
        raise NotImplementedError
