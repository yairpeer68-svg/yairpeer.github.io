"""AI integration: DeepSeek client, embeddings, prompts, citations, context."""

from app.services.ai.citations import (
    Citation,
    CitationOutcome,
    StreamingCitationGuard,
    validate_and_collect,
)
from app.services.ai.context import ContextBuilder, count_tokens, truncate_to_tokens
from app.services.ai.deepseek import (
    ChatMessage,
    CompletionResult,
    DeepSeekClient,
    parse_json_response,
)
from app.services.ai.embeddings import (
    EmbeddingProvider,
    build_embedding_provider,
    cosine_similarity,
)
from app.services.ai.prompts import DISCLAIMER_HE, SourceBlock

__all__ = [
    "DISCLAIMER_HE",
    "ChatMessage",
    "Citation",
    "CitationOutcome",
    "CompletionResult",
    "ContextBuilder",
    "DeepSeekClient",
    "EmbeddingProvider",
    "SourceBlock",
    "StreamingCitationGuard",
    "build_embedding_provider",
    "cosine_similarity",
    "count_tokens",
    "parse_json_response",
    "truncate_to_tokens",
    "validate_and_collect",
]
