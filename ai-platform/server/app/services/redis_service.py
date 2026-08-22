import importlib
import json
from typing import Any

from app.core.config import Settings
from app.core.errors import AppError

_RESERVE_LUA = r"""
local current = tonumber(redis.call('GET', KEYS[1]) or '0')
local amount = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
if current + amount > limit then return {0, current} end
local value = redis.call('INCRBY', KEYS[1], amount)
if current == 0 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end
return {1, value}
"""

_RATE_LUA = r"""
local current = redis.call('INCR', KEYS[1])
if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
local ttl = redis.call('TTL', KEYS[1])
return {current, ttl}
"""


class RedisService:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._client = None

    async def client(self):
        if self._client is None:
            try:
                redis_asyncio = importlib.import_module("redis.asyncio")
            except ModuleNotFoundError as exc:
                raise RuntimeError("redis Python package is not installed") from exc
            self._client = redis_asyncio.from_url(self.settings.REDIS_URL, encoding="utf-8", decode_responses=True,
                                                  socket_connect_timeout=2, socket_timeout=2, health_check_interval=30)
        return self._client

    async def ping(self) -> bool:
        try:
            c = await self.client()
            return bool(await c.ping())
        except Exception:
            return False

    async def close(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    async def rate_limit(self, key: str, limit: int, window_seconds: int = 60) -> int:
        c = await self.client()
        current, ttl = await c.eval(_RATE_LUA, 1, key, window_seconds)
        if int(current) > limit:
            retry = max(1, int(ttl))
            raise AppError("RATE_LIMITED", "Too many requests", 429, {"Retry-After": str(retry)})
        return max(0, int(ttl))

    async def get_json(self, key: str) -> Any | None:
        c = await self.client()
        raw = await c.get(key)
        if raw is None:
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            await c.delete(key)
            return None

    async def set_json(self, key: str, value: Any, ttl: int) -> None:
        c = await self.client()
        await c.set(key, json.dumps(value, separators=(",", ":"), ensure_ascii=False), ex=ttl)

    async def set_value(self, key: str, value: str, ttl: int | None = None) -> None:
        c = await self.client()
        await c.set(key, value, ex=ttl)

    async def get_value(self, key: str) -> str | None:
        c = await self.client()
        return await c.get(key)

    async def delete(self, key: str) -> None:
        c = await self.client()
        await c.delete(key)

    async def increment(self, key: str, ttl: int) -> int:
        c = await self.client()
        current, _ = await c.eval(_RATE_LUA, 1, key, ttl)
        return int(current)

    async def reserve(self, key: str, amount: int, limit: int, ttl: int) -> bool:
        c = await self.client()
        ok, _ = await c.eval(_RESERVE_LUA, 1, key, amount, limit, ttl)
        return bool(int(ok))

    async def adjust(self, key: str, delta: int, ttl: int) -> None:
        c = await self.client()
        pipe = c.pipeline(transaction=True)
        # NX keeps the original expiry: refreshing it on every adjustment slid the daily
        # quota window forward for any continuously active account.
        pipe.incrby(key, delta); pipe.expire(key, ttl, nx=True)
        result = await pipe.execute()
        if int(result[0]) < 0:
            await c.set(key, 0, keepttl=True)


_redis: RedisService | None = None


def get_redis_service(settings: Settings) -> RedisService:
    global _redis
    if _redis is None:
        _redis = RedisService(settings)
    return _redis
