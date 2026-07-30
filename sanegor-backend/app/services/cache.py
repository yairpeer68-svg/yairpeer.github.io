"""Redis-backed cache with a graceful no-op fallback.

Every method tolerates Redis being down: a cache miss is always a valid answer,
so the API keeps serving (slower) rather than failing when the cache tier is
unavailable.
"""

from __future__ import annotations

import json
from typing import TYPE_CHECKING, Any

from app.core.logging import get_logger

if TYPE_CHECKING:
    from redis.asyncio import Redis

logger = get_logger(__name__)


class CacheService:
    """Namespaced JSON cache."""

    def __init__(
        self,
        redis: Redis | None,
        *,
        default_ttl: int = 900,
        prefix: str = "sanegor",
    ) -> None:
        self._redis = redis
        self._default_ttl = default_ttl
        self._prefix = prefix

    @property
    def available(self) -> bool:
        return self._redis is not None

    def _key(self, namespace: str, key: str) -> str:
        return f"{self._prefix}:{namespace}:{key}"

    async def get(self, namespace: str, key: str) -> Any | None:
        if self._redis is None:
            return None
        try:
            raw = await self._redis.get(self._key(namespace, key))
        except Exception as exc:
            logger.warning("cache_get_failed", error=str(exc))
            return None
        if raw is None:
            return None
        try:
            return json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            return None

    async def set(self, namespace: str, key: str, value: Any, *, ttl: int | None = None) -> None:
        if self._redis is None:
            return
        try:
            await self._redis.set(
                self._key(namespace, key),
                json.dumps(value, ensure_ascii=False, default=str),
                ex=ttl or self._default_ttl,
            )
        except Exception as exc:
            logger.warning("cache_set_failed", error=str(exc))

    async def delete(self, namespace: str, key: str) -> None:
        if self._redis is None:
            return
        try:
            await self._redis.delete(self._key(namespace, key))
        except Exception as exc:
            logger.warning("cache_delete_failed", error=str(exc))

    async def clear_namespace(self, namespace: str) -> int:
        """Delete every key in a namespace.

        Uses ``SCAN`` rather than ``KEYS`` so a large keyspace does not block
        the Redis event loop.
        """
        if self._redis is None:
            return 0
        removed = 0
        try:
            pattern = f"{self._prefix}:{namespace}:*"
            async for key in self._redis.scan_iter(match=pattern, count=200):
                await self._redis.delete(key)
                removed += 1
        except Exception as exc:
            logger.warning("cache_clear_failed", error=str(exc))
        return removed

    async def ping(self) -> bool:
        if self._redis is None:
            return False
        try:
            return bool(await self._redis.ping())
        except Exception:
            return False
