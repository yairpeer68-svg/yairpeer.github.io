from dataclasses import dataclass


@dataclass(frozen=True)
class ProviderUsage:
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int


@dataclass(frozen=True)
class ProviderResponse:
    model: str
    content: str
    usage: ProviderUsage
