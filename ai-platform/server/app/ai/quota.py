import uuid
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.errors import AppError
from app.models.entities import AIQuota, AIUsageDaily
from app.monitoring.metrics import RATE_LIMIT_HITS
from app.services.redis_service import RedisService


class QuotaService:
    def __init__(self, db: AsyncSession, redis: RedisService, settings: Settings):
        self.db = db
        self.redis = redis
        self.settings = settings
        # The key a reservation was charged against. Recomputing it from "today" meant a
        # request that crossed UTC midnight refunded a different day's counter.
        self._reservation_key: str | None = None

    def _token_key(self, user_id: uuid.UUID) -> str:
        return self._reservation_key or f"quota:ai:tokens:{user_id}:{datetime.now(UTC).date().isoformat()}"

    async def get_quota(self, user_id: uuid.UUID) -> AIQuota:
        quota = await self.db.get(AIQuota, user_id)
        if quota is None:
            quota = AIQuota(
                user_id=user_id,
                requests_per_minute=self.settings.AI_RATE_LIMIT_PER_MINUTE,
                requests_per_day=200,
                tokens_per_day=100000,
                max_output_tokens=self.settings.AI_MAX_RESPONSE_TOKENS,
            )
            self.db.add(quota)
            await self.db.flush()
        return quota

    async def _release(self, key: str, amount: int) -> None:
        if amount <= 0:
            return
        try:
            await self.redis.adjust(key, -amount, 90000)
        except Exception:
            # A failed release is deliberately non-fatal here; Redis TTL bounds the stale reservation.
            return

    async def check(
        self,
        user_id: uuid.UUID,
        requested_output_tokens: int,
        estimated_total_tokens: int,
    ) -> tuple[AIQuota, int]:
        quota = await self.get_quota(user_id)
        if requested_output_tokens > quota.max_output_tokens:
            raise AppError("QUOTA_OUTPUT_LIMIT", "Requested output exceeds your quota", 429)

        today = datetime.now(UTC).date()
        try:
            await self.redis.rate_limit(f"quota:ai:minute:{user_id}", quota.requests_per_minute, 60)
            await self.redis.rate_limit(
                f"quota:ai:day:{user_id}:{today.isoformat()}", quota.requests_per_day, 90000
            )
        except AppError as exc:
            if exc.status_code == 429:
                RATE_LIMIT_HITS.labels("ai").inc()
            raise
        except Exception as exc:
            raise AppError(
                "QUOTA_BACKEND_UNAVAILABLE",
                "AI quota service is temporarily unavailable",
                503,
            ) from exc

        reserve = max(1, estimated_total_tokens)
        token_key = f"quota:ai:tokens:{user_id}:{today.isoformat()}"
        try:
            allowed = await self.redis.reserve(token_key, reserve, quota.tokens_per_day, 90000)
        except Exception as exc:
            raise AppError(
                "QUOTA_BACKEND_UNAVAILABLE",
                "AI quota service is temporarily unavailable",
                503,
            ) from exc
        if not allowed:
            raise AppError("DAILY_TOKEN_QUOTA", "Daily AI token quota reached", 429)
        self._reservation_key = token_key

        try:
            usage = await self.db.scalar(
                select(AIUsageDaily).where(
                    AIUsageDaily.user_id == user_id,
                    AIUsageDaily.usage_date == today,
                )
            )
        except Exception as exc:
            await self._release(token_key, reserve)
            raise AppError(
                "QUOTA_STATE_UNAVAILABLE",
                "AI quota state is temporarily unavailable",
                503,
            ) from exc

        if usage and usage.requests >= quota.requests_per_day:
            await self._release(token_key, reserve)
            raise AppError("DAILY_REQUEST_QUOTA", "Daily AI request quota reached", 429)
        if usage and usage.total_tokens >= quota.tokens_per_day:
            await self._release(token_key, reserve)
            raise AppError("DAILY_TOKEN_QUOTA", "Daily AI token quota reached", 429)
        return quota, reserve

    async def record(
        self,
        user_id: uuid.UUID,
        prompt_tokens: int,
        completion_tokens: int,
        reserved_tokens: int = 0,
    ) -> None:
        total = prompt_tokens + completion_tokens
        token_key = self._token_key(user_id)
        if reserved_tokens != total:
            await self.redis.adjust(token_key, total - reserved_tokens, 90000)
        stmt = insert(AIUsageDaily).values(
            user_id=user_id,
            usage_date=datetime.now(UTC).date(),
            requests=1,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            total_tokens=total,
        )
        stmt = stmt.on_conflict_do_update(
            index_elements=[AIUsageDaily.user_id, AIUsageDaily.usage_date],
            set_={
                "requests": AIUsageDaily.requests + 1,
                "prompt_tokens": AIUsageDaily.prompt_tokens + prompt_tokens,
                "completion_tokens": AIUsageDaily.completion_tokens + completion_tokens,
                "total_tokens": AIUsageDaily.total_tokens + total,
            },
        )
        await self.db.execute(stmt)

    async def release_reservation(self, user_id: uuid.UUID, reserved_tokens: int) -> None:
        if reserved_tokens > 0:
            await self._release(self._token_key(user_id), reserved_tokens)
