from pydantic import BaseModel, Field, model_validator


class AIMessage(BaseModel):
    role: str = Field(pattern="^(system|user|assistant)$")
    content: str = Field(min_length=1, max_length=20000)


class AIChatRequest(BaseModel):
    messages: list[AIMessage] = Field(min_length=1, max_length=50)
    model: str | None = Field(default=None, max_length=128)
    temperature: float = Field(default=0.7, ge=0, le=2)
    max_tokens: int = Field(default=1024, ge=1, le=8192)
    cache: bool = True

    @model_validator(mode="after")
    def total_size(self):
        if sum(len(m.content) for m in self.messages) > 20000:
            raise ValueError("Combined message length exceeds limit")
        return self


class AIUsage(BaseModel):
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0


class AIChatResponse(BaseModel):
    request_id: str
    model: str
    content: str
    usage: AIUsage
    cache_hit: bool = False
