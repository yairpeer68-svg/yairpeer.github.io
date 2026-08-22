# DeepSeek Integration

DeepSeek is accessed exclusively through `server/app/ai/`. Clients never receive provider credentials.

The provider adapter uses HTTPX timeouts, bounded connection pools, response schema checks and retry/backoff for network failures, HTTP 429 and HTTP 5xx. Stable application errors are returned when the provider is unavailable, times out, rejects input, is not configured or returns malformed content.

The gateway adds an allowed-model list, prompt/output limits, Redis cache, user quotas, daily token reservations, request metadata, latency, cache status and a Redis-backed circuit breaker. Cache keys include model, normalized prompt hash, temperature and output-token limit.

By default only a SHA-256 prompt hash is persisted. To retain prompt material, set `PROMPT_LOGGING_ENABLED=true` and provide a valid Fernet key in `PROMPT_RETENTION_ENCRYPTION_KEY`; otherwise the API refuses retention rather than storing plaintext. Generate a Fernet key using a controlled Python environment with `cryptography.fernet.Fernet.generate_key()`.

Tests use either HTTPX mock transports or `AI_PROVIDER_MODE=mock` with `APP_ENV=test`. Production configuration validation rejects the mock provider.
