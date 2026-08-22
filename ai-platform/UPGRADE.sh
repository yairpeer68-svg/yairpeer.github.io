#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$ROOT"
[ -f .env ] || { echo "ERROR: .env required" >&2; exit 1; }
backup="$(./scripts/backup.sh | tail -n1)"
echo "Pre-upgrade backup: $backup"
if [ -d .git ]; then git pull --ff-only; else echo "No .git directory; upgrading from current extracted release files."; fi
if ! docker compose config >/dev/null; then echo "Compose validation failed; no services changed." >&2; exit 1; fi
if ! docker compose build api worker runner nginx; then echo "Image build failed; existing containers remain running. Backup: $backup" >&2; exit 1; fi
if ! docker compose run --rm api alembic upgrade head; then echo "Migration failed. STOPPED. Existing DB may have partial transactional changes; backup: $backup" >&2; exit 1; fi
docker compose up -d
if ! ./VERIFY_INSTALL.sh; then echo "Upgrade health verification failed. STOPPED. Backup available at $backup. Inspect logs before any DB restore." >&2; exit 1; fi
echo "Upgrade completed"
