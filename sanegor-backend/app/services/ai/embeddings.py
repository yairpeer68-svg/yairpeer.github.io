"""Embedding providers.

DeepSeek does not expose an embeddings endpoint, so the vector side of RAG is
pluggable:

``openai_compatible``
    Any server speaking ``POST {base_url}/embeddings`` — Text Embeddings
    Inference, Ollama, vLLM, a LiteLLM proxy, or a hosted provider.  Use a
    multilingual model; Hebrew retrieval collapses with English-only ones.

``hashing``
    A deterministic local stub for development and tests.  It has no semantic
    understanding whatsoever and is rejected by the production config guard —
    it exists so the stack boots without external dependencies.
"""

from __future__ import annotations

import hashlib
import math
import re
from abc import ABC, abstractmethod
from collections.abc import Sequence

import httpx

from app.core.config import Settings
from app.core.errors import UpstreamError
from app.core.logging import get_logger

logger = get_logger(__name__)

Vector = list[float]


def cosine_similarity(left: Sequence[float], right: Sequence[float]) -> float:
    """Cosine similarity, returning 0.0 for degenerate input."""
    if not left or not right or len(left) != len(right):
        return 0.0
    dot = sum(a * b for a, b in zip(left, right, strict=True))
    norm_left = math.sqrt(sum(a * a for a in left))
    norm_right = math.sqrt(sum(b * b for b in right))
    if norm_left == 0.0 or norm_right == 0.0:
        return 0.0
    return dot / (norm_left * norm_right)


def l2_normalise(vector: Sequence[float]) -> Vector:
    """Scale ``vector`` to unit length so cosine reduces to a dot product."""
    norm = math.sqrt(sum(v * v for v in vector))
    if norm == 0.0:
        return list(vector)
    return [v / norm for v in vector]


class EmbeddingProvider(ABC):
    """Strategy interface for turning text into vectors."""

    @property
    @abstractmethod
    def dimensions(self) -> int:
        """Width of the vectors this provider emits."""

    @property
    @abstractmethod
    def name(self) -> str:
        """Identifier recorded alongside stored vectors."""

    @abstractmethod
    async def embed(self, texts: Sequence[str]) -> list[Vector]:
        """Embed a batch of texts, preserving order."""

    async def embed_one(self, text: str) -> Vector:
        """Convenience wrapper for a single string."""
        vectors = await self.embed([text])
        return vectors[0]

    async def aclose(self) -> None:
        """Release any held resources.

        Deliberately concrete and empty: a provider with nothing to close
        (the hashing stub) should not be forced to implement this.
        """
        return None


class OpenAICompatibleEmbeddings(EmbeddingProvider):
    """Talks to any OpenAI-shaped ``/embeddings`` endpoint."""

    _BATCH_SIZE = 32

    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        self._settings = settings
        self._owns_client = client is None
        headers = {"Content-Type": "application/json"}
        if settings.embedding_api_key:
            headers["Authorization"] = f"Bearer {settings.embedding_api_key}"
        self._client = client or httpx.AsyncClient(
            base_url=settings.embedding_base_url.rstrip("/"),
            timeout=httpx.Timeout(60.0, connect=10.0),
            headers=headers,
        )

    @property
    def dimensions(self) -> int:
        return self._settings.embedding_dimensions

    @property
    def name(self) -> str:
        return self._settings.embedding_model

    async def embed(self, texts: Sequence[str]) -> list[Vector]:
        if not texts:
            return []
        vectors: list[Vector] = []
        for start in range(0, len(texts), self._BATCH_SIZE):
            batch = list(texts[start : start + self._BATCH_SIZE])
            vectors.extend(await self._embed_batch(batch))
        return vectors

    async def _embed_batch(self, batch: list[str]) -> list[Vector]:
        try:
            response = await self._client.post(
                "/embeddings", json={"model": self.name, "input": batch}
            )
        except httpx.HTTPError as exc:
            logger.error("embedding_transport_error", error=str(exc))
            raise UpstreamError("שירות ה-Embeddings אינו זמין") from exc

        if response.status_code != 200:
            logger.error("embedding_failed", status=response.status_code)
            raise UpstreamError("שירות ה-Embeddings החזיר שגיאה")

        body = response.json()
        # Order is not guaranteed by the spec; sort by index defensively.
        items = sorted(body.get("data", []), key=lambda item: item.get("index", 0))
        vectors = [l2_normalise(item["embedding"]) for item in items]
        if len(vectors) != len(batch):
            raise UpstreamError("שירות ה-Embeddings החזיר מספר וקטורים שגוי")
        for vector in vectors:
            if len(vector) != self.dimensions:
                raise UpstreamError(
                    "מימד ה-Embedding אינו תואם להגדרות",
                    details={"expected": self.dimensions, "received": len(vector)},
                )
        return vectors

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()


class HashingEmbeddings(EmbeddingProvider):
    """Deterministic bag-of-words hashing vectoriser (development only).

    Tokens are hashed into buckets with sub-linear term weighting, which gives
    lexical-overlap retrieval — enough for tests and local demos, and nothing
    like real semantic search.
    """

    _TOKEN_RE = re.compile(r"[\w֐-׿]+", re.UNICODE)

    def __init__(self, dimensions: int = 1024) -> None:
        self._dimensions = dimensions

    @property
    def dimensions(self) -> int:
        return self._dimensions

    @property
    def name(self) -> str:
        return "hashing-dev"

    async def embed(self, texts: Sequence[str]) -> list[Vector]:
        return [self._embed_sync(text) for text in texts]

    def _embed_sync(self, text: str) -> Vector:
        vector = [0.0] * self._dimensions
        tokens = self._TOKEN_RE.findall(text.lower())
        if not tokens:
            return vector
        counts: dict[str, int] = {}
        for token in tokens:
            counts[token] = counts.get(token, 0) + 1
        for token, count in counts.items():
            digest = hashlib.blake2b(token.encode(), digest_size=8).digest()
            bucket = int.from_bytes(digest[:4], "big") % self._dimensions
            sign = 1.0 if digest[4] % 2 == 0 else -1.0
            vector[bucket] += sign * (1.0 + math.log(count))
        return l2_normalise(vector)


def build_embedding_provider(settings: Settings) -> EmbeddingProvider:
    """Factory selecting the provider named in configuration."""
    if settings.embedding_provider == "openai_compatible":
        logger.info(
            "embeddings_provider",
            provider="openai_compatible",
            model=settings.embedding_model,
        )
        return OpenAICompatibleEmbeddings(settings)
    logger.warning(
        "embeddings_provider_stub",
        detail="EMBEDDING_PROVIDER=hashing has no semantic understanding; development only",
    )
    return HashingEmbeddings(settings.embedding_dimensions)
