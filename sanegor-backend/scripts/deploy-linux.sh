#!/usr/bin/env bash
# ============================================================================
# Sanegor backend — production deployment onto a single Linux host.
# Assumes Docker Engine + the compose plugin are installed.
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

info()  { printf '\033[1;34m==>\033[0m %s\n' "$1"; }
fatal() { printf '\033[1;31mxx\033[0m %s\n' "$1" >&2; exit 1; }

[[ -f .env ]] || fatal ".env not found. Copy .env.example and fill it in first."

# ------------------------------------------------------- pre-flight checks
info "Validating production configuration"
# shellcheck disable=SC1091
set -a; source .env; set +a

[[ "${ENVIRONMENT:-}" == "production" ]] || fatal "ENVIRONMENT must be 'production'"
[[ ${#SECRET_KEY} -ge 32 ]]              || fatal "SECRET_KEY must be at least 32 characters"
[[ -n "${ENCRYPTION_KEY:-}" ]]           || fatal "ENCRYPTION_KEY must be set (encryption at rest)"
[[ "${DEBUG:-false}" == "false" ]]       || fatal "DEBUG must be false"
[[ "${FORCE_HTTPS:-false}" == "true" ]]  || fatal "FORCE_HTTPS must be true"
[[ "${CORS_ORIGINS:-}" != *"*"*  ]]      || fatal "CORS_ORIGINS must not contain '*'"
[[ -n "${DEEPSEEK_API_KEY:-}" ]]         || fatal "DEEPSEEK_API_KEY must be set"
[[ "${EMBEDDING_PROVIDER:-}" != "hashing" ]] \
    || fatal "EMBEDDING_PROVIDER=hashing is a development stub — configure a real provider"
[[ "${POSTGRES_PASSWORD:-}" != "change-me-in-production" ]] \
    || fatal "POSTGRES_PASSWORD is still the example value"

command -v docker >/dev/null || fatal "docker not found"
docker compose version >/dev/null 2>&1 || fatal "docker compose plugin not found"

# --------------------------------------------------------------- deploy
info "Building images"
docker compose build --pull

info "Starting datastores"
docker compose up -d postgres redis

info "Waiting for PostgreSQL"
for _ in $(seq 1 60); do
    if docker compose exec -T postgres pg_isready -U "${POSTGRES_USER}" >/dev/null 2>&1; then
        break
    fi
    sleep 2
done
docker compose exec -T postgres pg_isready -U "${POSTGRES_USER}" >/dev/null \
    || fatal "PostgreSQL did not become ready"

info "Applying database migrations"
docker compose run --rm migrate

info "Starting the API"
docker compose up -d api

info "Waiting for the API to become healthy"
for _ in $(seq 1 45); do
    if curl -fsS "http://localhost:${API_PORT:-8000}/health/ready" >/dev/null 2>&1; then
        info "API is ready"
        break
    fi
    sleep 2
done

curl -fsS "http://localhost:${API_PORT:-8000}/health/ready" >/dev/null \
    || fatal "API failed its readiness check — inspect: docker compose logs api"

# ------------------------------------------------------------- corpus check
info "Checking the legal corpus"
docker compose exec -T api python scripts/seed_corpus.py --check || true

cat <<'EOF'

Deployment complete.

Remaining steps for a real production install:
  * Terminate TLS in front of the API (nginx / Caddy / a managed load balancer).
    The container speaks plain HTTP; FORCE_HTTPS makes it require the
    X-Forwarded-Proto header your proxy sets.
  * Load a legal corpus — until then the assistant answers without citations:
        docker compose exec api python scripts/seed_corpus.py corpus/*.json
  * Schedule database backups:
        docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" \
            | gzip > "backup-$(date +%F).sql.gz"
  * Ship logs somewhere durable (LOG_JSON=true emits JSON lines).
  * Monitor /health/ready from your uptime checker.
EOF
