#!/usr/bin/env bash
set -euo pipefail
[ $# -ge 1 ] || { echo "Usage: $0 BACKUP.dump [WORKSPACES.tar.zst]" >&2; exit 2; }
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$ROOT"
[ -f .env ] || { echo "ERROR: .env is required" >&2; exit 1; }
set -a; source .env; set +a
backup="$(realpath "$1")"; [ -f "$backup" ] || { echo "Backup not found" >&2; exit 1; }
workspaces="${2:-}"
if [ -n "$workspaces" ]; then
  workspaces="$(realpath "$workspaces")"; [ -f "$workspaces" ] || { echo "Workspace archive not found" >&2; exit 1; }
fi
if [ -f "$backup.sha256" ]; then (cd "$(dirname "$backup")" && sha256sum -c "$(basename "$backup").sha256"); fi
if [ -n "$workspaces" ] && [ -f "$workspaces.sha256" ]; then (cd "$(dirname "$workspaces")" && sha256sum -c "$(basename "$workspaces").sha256"); fi

read -r -p "This replaces database '$POSTGRES_DB'. Type RESTORE to continue: " answer
[ "$answer" = RESTORE ] || { echo "Cancelled"; exit 1; }

# Writers must be stopped first. pg_terminate_backend alone does not help: the API and
# worker pools reconnect immediately and dropdb then fails with "database is being accessed".
echo "Stopping API, worker and scheduler..."
docker compose stop api worker scheduler >/dev/null 2>&1 || true
restart_services(){ docker compose up -d api worker scheduler >/dev/null 2>&1 || true; }
trap restart_services EXIT

docker compose exec -T postgres psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='${POSTGRES_DB}' AND pid <> pg_backend_pid();" >/dev/null
docker compose exec -T postgres dropdb -U "$POSTGRES_USER" --if-exists "$POSTGRES_DB"
docker compose exec -T postgres createdb -U "$POSTGRES_USER" "$POSTGRES_DB"
docker compose exec -T postgres pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --no-privileges --exit-on-error < "$backup"

if [ -n "$workspaces" ]; then
  echo "Restoring engineering workspaces..."
  docker compose stop runner >/dev/null 2>&1 || true
  docker run --rm \
    -v ai-platform_engineering_workspaces:/workspaces \
    -v "$(dirname "$workspaces")":/backup:ro \
    alpine:3.20 sh -c "apk add --no-cache zstd tar >/dev/null && rm -rf /workspaces/* && zstd -dc /backup/$(basename "$workspaces") | tar -C /workspaces -xf - && chown -R 10001:10001 /workspaces"
  docker compose up -d runner >/dev/null 2>&1 || true
else
  echo "WARNING: no workspace archive supplied. Engineering projects will have database rows" >&2
  echo "         but no files on disk, and checkpoint rollback will fail." >&2
fi

trap - EXIT
restart_services
for _ in $(seq 1 30); do curl -fsS http://127.0.0.1:8080/health/ready >/dev/null 2>&1 && break; sleep 2; done
echo "Restore completed"
