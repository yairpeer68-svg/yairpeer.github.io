"""Distributed rate limiting.

Implements a fixed-window counter in Redis via a single atomic Lua script
(``INCR`` + conditional ``EXPIRE``), which keeps the check to one round trip.
When Redis is unavailable the limiter fails **open** — an outage of the cache
tier should degrade throttling, not take the API down — but it logs loudly.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.core.config import RateLimitRule
from app.core.errors import RateLimitedError
from app.core.logging import get_logger

if TYPE_CHECKING:
    from redis.asyncio import Redis

logger = get_logger(__name__)

# KEYS[1] = counter key, ARGV[1] = window seconds
# Returns {current_count, ttl_seconds}
_SCRIPT = """
local current = redis.call('INCR', KEYS[1])
if current == 1 then
  redis.call('EXPIRE', KEYS[1], ARGV[1])
end
local ttl = redis.call('TTL', KEYS[1])
if ttl < 0 then
  redis.call('EXPIRE', KEYS[1], ARGV[1])
  ttl = tonumber(ARGV[1])
end
return {current, ttl}
"""


class RateLimiter:
    """Fixed-window rate limiter backed by Redis."""

    def __init__(self, redis: Redis | None) -> None:
        self._redis = redis
        self._script = redis.register_script(_SCRIPT) if redis is not None else None

    async def check(self, identity: str, bucket: str, rule: RateLimitRule) -> None:
        """Consume one token for ``identity`` in ``bucket``.

        Raises:
            RateLimitedError: when the window allowance is exhausted.
        """
        if self._script is None:
            return
        key = f"ratelimit:{bucket}:{identity}"
        try:
            current, ttl = await self._script(keys=[key], args=[rule.window])
        except Exception as exc:
            logger.warning("rate_limit_unavailable", error=str(exc), bucket=bucket)
            return

        if int(current) > rule.limit:
            logger.info("rate_limited", bucket=bucket, identity=identity, count=int(current))
            raise RateLimitedError(retry_after=max(int(ttl), 1))

    async def reset(self, identity: str, bucket: str) -> None:
        """Clear the counter — used after a successful login, for example."""
        if self._redis is None:
            return
        try:
            await self._redis.delete(f"ratelimit:{bucket}:{identity}")
        except Exception as exc:
            logger.warning("rate_limit_reset_failed", error=str(exc))
