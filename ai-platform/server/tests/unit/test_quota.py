import uuid
from datetime import date

import pytest

from app.ai.quota import QuotaService
from app.core.config import Settings
from app.core.errors import AppError
from app.models.entities import AIQuota, AIUsageDaily


class FakeDb:
    def __init__(self, quota, usage=None):
        self.quota = quota
        self.usage = usage

    async def get(self, model, key):
        if model is AIQuota:
            return self.quota
        return None

    async def scalar(self, statement):
        return self.usage


class FakeRedis:
    def __init__(self):
        self.adjustments = []
        self.fail_rate = False

    async def rate_limit(self, key, limit, window_seconds):
        if self.fail_rate:
            raise RuntimeError("redis down")
        return 30

    async def reserve(self, key, amount, limit, ttl):
        return amount <= limit

    async def adjust(self, key, delta, ttl):
        self.adjustments.append((key, delta, ttl))


def settings():
    return Settings(APP_ENV="test", JWT_SECRET="x" * 80)


@pytest.mark.asyncio
async def test_daily_request_quota_releases_token_reservation():
    user_id = uuid.uuid4()
    quota = AIQuota(
        user_id=user_id,
        requests_per_minute=20,
        requests_per_day=1,
        tokens_per_day=1000,
        max_output_tokens=100,
    )
    usage = AIUsageDaily(
        user_id=user_id,
        usage_date=date.today(),
        requests=1,
        prompt_tokens=1,
        completion_tokens=1,
        total_tokens=2,
    )
    redis = FakeRedis()
    service = QuotaService(FakeDb(quota, usage), redis, settings())

    with pytest.raises(AppError) as exc:
        await service.check(user_id, 20, 50)

    assert exc.value.code == "DAILY_REQUEST_QUOTA"
    assert redis.adjustments and redis.adjustments[-1][1] == -50


@pytest.mark.asyncio
async def test_quota_redis_failure_is_explicit_503():
    user_id = uuid.uuid4()
    quota = AIQuota(
        user_id=user_id,
        requests_per_minute=20,
        requests_per_day=20,
        tokens_per_day=1000,
        max_output_tokens=100,
    )
    redis = FakeRedis()
    redis.fail_rate = True
    service = QuotaService(FakeDb(quota), redis, settings())

    with pytest.raises(AppError) as exc:
        await service.check(user_id, 20, 50)

    assert exc.value.code == "QUOTA_BACKEND_UNAVAILABLE"
    assert exc.value.status_code == 503
