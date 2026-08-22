import hashlib
import json
import logging
import time
import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.circuit import ProviderCircuitBreaker
from app.ai.policy import PromptPolicy
from app.ai.provider import AIProvider
from app.ai.quota import QuotaService
from app.core.config import Settings
from app.core.errors import AppError
from app.models.entities import AIRequest
from app.monitoring.metrics import AI_FAILURES, AI_LATENCY, AI_REQUESTS, CACHE_HITS
from app.security.privacy import encrypt_prompt_if_enabled
from app.services.redis_service import RedisService

log = logging.getLogger("ai.gateway")


class AIGateway:
    def __init__(self, db: AsyncSession, redis: RedisService, settings: Settings, provider: AIProvider):
        self.db = db
        self.redis = redis
        self.settings = settings
        self.provider = provider
        self.policy = PromptPolicy(settings)
        self.quota = QuotaService(db, redis, settings)
        self.circuit = ProviderCircuitBreaker(redis)

    @staticmethod
    def _normalized(messages: list[dict[str, str]]) -> str:
        normalized = [
            {"role": message["role"], "content": " ".join(message["content"].split())}
            for message in messages
        ]
        return json.dumps(normalized, sort_keys=True, separators=(",", ":"), ensure_ascii=False)

    async def _safe_rollback(self) -> None:
        try:
            await self.db.rollback()
        except Exception as exc:
            log.error("database_rollback_failed", extra={"event": type(exc).__name__})

    async def _record_provider_error(
        self,
        request_id: str,
        user_id: uuid.UUID,
        device_id: uuid.UUID | None,
        model: str,
        prompt_hash: str,
        started: float,
        code: str,
    ) -> None:
        self.db.add(
            AIRequest(
                request_id=request_id,
                user_id=user_id,
                device_id=device_id,
                model=model,
                prompt_hash=prompt_hash,
                latency_ms=int((time.perf_counter() - started) * 1000),
                status="error",
                error_type=code,
                cache_hit=False,
                response_size=0,
            )
        )
        try:
            await self.db.commit()
        except Exception as exc:
            await self._safe_rollback()
            log.error("ai_error_audit_write_failed", extra={"event": type(exc).__name__})

    async def chat(
        self,
        request_id: str,
        user_id: uuid.UUID,
        device_id: uuid.UUID | None,
        messages: list[dict[str, str]],
        model: str,
        temperature: float,
        max_tokens: int,
        use_cache: bool = True,
    ) -> dict:
        self.policy.validate(messages, max_tokens)
        if model not in self.settings.allowed_models:
            raise AppError("MODEL_NOT_ALLOWED", "Requested AI model is not allowed", 422)

        estimated_input = max(1, sum(len(message.get("content", "")) for message in messages) // 3)
        _, reserved_tokens = await self.quota.check(
            user_id,
            max_tokens,
            estimated_input + max_tokens,
        )
        normalized = self._normalized(messages)
        prompt_encrypted = encrypt_prompt_if_enabled(self.settings, normalized)
        prompt_hash = hashlib.sha256(normalized.encode()).hexdigest()
        cache_material = f"{model}|{temperature:.4f}|{max_tokens}|{prompt_hash}"
        cache_key = "ai:cache:" + hashlib.sha256(cache_material.encode()).hexdigest()
        started = time.perf_counter()

        if use_cache:
            try:
                cached = await self.redis.get_json(cache_key)
            except Exception as exc:
                cached = None
                log.warning("ai_cache_read_failed", extra={"event": type(exc).__name__})
            if cached is not None:
                latency = int((time.perf_counter() - started) * 1000)
                usage = cached.get("usage", {})
                self.db.add(
                    AIRequest(
                        request_id=request_id,
                        user_id=user_id,
                        device_id=device_id,
                        model=model,
                        prompt_hash=prompt_hash,
                        latency_ms=latency,
                        prompt_tokens=int(usage.get("prompt_tokens", 0)),
                        completion_tokens=int(usage.get("completion_tokens", 0)),
                        total_tokens=int(usage.get("total_tokens", 0)),
                        status="success",
                        cache_hit=True,
                        response_size=len(cached.get("content", "").encode()),
                        prompt_encrypted=prompt_encrypted,
                    )
                )
                try:
                    # A cache hit consumes a request quota but no provider tokens.
                    await self.quota.record(user_id, 0, 0, reserved_tokens)
                    await self.db.commit()
                except Exception as exc:
                    await self._safe_rollback()
                    log.error("ai_cache_accounting_failed", extra={"event": type(exc).__name__})
                    raise AppError(
                        "AI_ACCOUNTING_UNAVAILABLE",
                        "AI usage accounting is temporarily unavailable",
                        503,
                    ) from exc
                AI_REQUESTS.labels("success", "true").inc()
                CACHE_HITS.inc()
                AI_LATENCY.observe(time.perf_counter() - started)
                return {**cached, "cache_hit": True}

        try:
            try:
                await self.circuit.before_call()
            except AppError:
                raise
            except Exception as exc:
                log.warning("ai_circuit_state_unavailable", extra={"event": type(exc).__name__})

            result = await self.provider.chat(messages, model, temperature, max_tokens)
            try:
                await self.circuit.success()
            except Exception as exc:
                log.warning("ai_circuit_reset_failed", extra={"event": type(exc).__name__})

            latency = int((time.perf_counter() - started) * 1000)
            payload = {
                "model": result.model,
                "content": result.content,
                "usage": {
                    "prompt_tokens": result.usage.prompt_tokens,
                    "completion_tokens": result.usage.completion_tokens,
                    "total_tokens": result.usage.total_tokens,
                },
            }
            self.db.add(
                AIRequest(
                    request_id=request_id,
                    user_id=user_id,
                    device_id=device_id,
                    model=result.model,
                    prompt_hash=prompt_hash,
                    latency_ms=latency,
                    prompt_tokens=result.usage.prompt_tokens,
                    completion_tokens=result.usage.completion_tokens,
                    total_tokens=result.usage.total_tokens,
                    status="success",
                    cache_hit=False,
                    response_size=len(result.content.encode()),
                    prompt_encrypted=prompt_encrypted,
                )
            )
            try:
                await self.quota.record(
                    user_id,
                    result.usage.prompt_tokens,
                    result.usage.completion_tokens,
                    reserved_tokens,
                )
                await self.db.commit()
            except Exception as exc:
                # Provider cost has already occurred, so the Redis token accounting is not released.
                await self._safe_rollback()
                log.error("ai_provider_accounting_failed", extra={"event": type(exc).__name__})
                raise AppError(
                    "AI_ACCOUNTING_UNAVAILABLE",
                    "AI response was generated but usage accounting failed",
                    503,
                ) from exc

            if use_cache and self.settings.AI_CACHE_TTL_SECONDS > 0:
                try:
                    await self.redis.set_json(cache_key, payload, self.settings.AI_CACHE_TTL_SECONDS)
                except Exception as exc:
                    log.warning("ai_cache_write_failed", extra={"event": type(exc).__name__})

            AI_REQUESTS.labels("success", "false").inc()
            AI_LATENCY.observe(time.perf_counter() - started)
            return {**payload, "cache_hit": False}
        except AppError as exc:
            if exc.code != "AI_ACCOUNTING_UNAVAILABLE":
                await self.quota.release_reservation(user_id, reserved_tokens)
            if exc.code.startswith("AI_PROVIDER_"):
                try:
                    await self.circuit.failure()
                except Exception as circuit_exc:
                    log.warning(
                        "circuit_failure_record_failed",
                        extra={"event": type(circuit_exc).__name__},
                    )
            AI_REQUESTS.labels("error", "false").inc()
            AI_FAILURES.labels(exc.code).inc()
            AI_LATENCY.observe(time.perf_counter() - started)
            await self._record_provider_error(
                request_id,
                user_id,
                device_id,
                model,
                prompt_hash,
                started,
                exc.code,
            )
            raise
        except Exception as exc:
            await self.quota.release_reservation(user_id, reserved_tokens)
            try:
                await self.circuit.failure()
            except Exception as circuit_exc:
                log.warning(
                    "circuit_failure_record_failed",
                    extra={"event": type(circuit_exc).__name__},
                )
            code = "AI_PROVIDER_INTERNAL_ERROR"
            AI_REQUESTS.labels("error", "false").inc()
            AI_FAILURES.labels(code).inc()
            AI_LATENCY.observe(time.perf_counter() - started)
            await self._record_provider_error(
                request_id,
                user_id,
                device_id,
                model,
                prompt_hash,
                started,
                code,
            )
            raise AppError(
                code,
                "AI provider request failed safely",
                503,
            ) from exc
