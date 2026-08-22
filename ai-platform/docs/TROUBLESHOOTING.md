# Troubleshooting

`/health` proves the process is alive; `/health/ready` additionally verifies PostgreSQL and Redis. DeepSeek configuration is reported separately and readiness does not spend provider credits.

If readiness says database unavailable, inspect `docker compose logs postgres api`, verify `DATABASE_URL` uses the Docker service name `postgres`, then run `docker compose exec postgres pg_isready`. For Redis, use `docker compose exec redis redis-cli ping`.

For migration failures run `docker compose run --rm api alembic current` and `alembic history`. Do not call `create_all()` as a production repair.

For 429 responses honor `Retry-After`. Authentication limits are per source IP and AI quotas are per user. For `REFRESH_TOKEN_REUSE`, sign in again; the token family was deliberately revoked.

For AI errors, check `/api/v1/system/integrations`, allowed model configuration and provider connectivity. `AI_NOT_CONFIGURED` means no server key; `AI_CIRCUIT_OPEN` means repeated provider failures temporarily opened the circuit.

If Android debug cannot reach `10.0.2.2`, confirm the backend is on the development host and the debug manifest is active. Production release traffic must use HTTPS.
