import time
from app.core.errors import AppError
from app.services.redis_service import RedisService


class ProviderCircuitBreaker:
    def __init__(self, redis: RedisService, provider: str = "deepseek", threshold: int = 5, open_seconds: int = 30):
        self.redis=redis; self.provider=provider; self.threshold=threshold; self.open_seconds=open_seconds
        self.fail_key=f"ai:circuit:{provider}:failures"; self.open_key=f"ai:circuit:{provider}:open_until"

    async def before_call(self):
        raw=await self.redis.get_value(self.open_key)
        if raw:
            try: until=float(raw)
            except ValueError: until=0
            if until > time.time():
                raise AppError("AI_CIRCUIT_OPEN", "AI provider is temporarily unavailable", 503, {"Retry-After": str(max(1, int(until-time.time())))})
            await self.redis.delete(self.open_key)

    async def success(self):
        await self.redis.delete(self.fail_key); await self.redis.delete(self.open_key)

    async def failure(self):
        count=await self.redis.increment(self.fail_key, 60)
        if count >= self.threshold:
            await self.redis.set_value(self.open_key, str(time.time()+self.open_seconds), ttl=self.open_seconds)
