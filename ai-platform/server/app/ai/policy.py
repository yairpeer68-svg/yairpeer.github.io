from app.core.config import Settings
from app.core.errors import AppError


class PromptPolicy:
    def __init__(self, settings: Settings):
        self.settings = settings

    def validate(self, messages: list[dict[str, str]], max_tokens: int) -> None:
        total_chars = sum(len(m.get("content", "")) for m in messages)
        if total_chars > self.settings.AI_MAX_PROMPT_CHARS:
            raise AppError("PROMPT_TOO_LARGE", "Prompt exceeds configured size limit", 413)
        if max_tokens > self.settings.AI_GLOBAL_MAX_RESPONSE_TOKENS:
            raise AppError("TOKEN_LIMIT_EXCEEDED", "Requested output token limit is too high", 422)
        system_count = sum(1 for m in messages if m.get("role") == "system")
        if system_count > 1:
            raise AppError("INVALID_MESSAGES", "At most one system message is allowed", 422)
