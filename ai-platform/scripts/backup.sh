#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
[ -f .env ] || { echo "ERROR: .env is required" >&2; exit 1; }
set -a; source .env; set +a
BACKUP_DIR="${BACKUP_DIR:-$ROOT/backups}"
mkdir -p "$BACKUP_DIR"; chmod 700 "$BACKUP_DIR"
ts="$(date -u +%Y%m%dT%H%M%SZ)"
db_out="$BACKUP_DIR/${POSTGRES_DB:-ai_platform}-$ts.dump"
ws_out="$BACKUP_DIR/workspaces-$ts.tar.zst"

echo "Creating PostgreSQL backup: $db_out"
docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -Z9 > "$db_out.tmp"
mv "$db_out.tmp" "$db_out"; chmod 600 "$db_out"
sha256sum "$db_out" > "$db_out.sha256"

# The engineering workspace volume holds every user's imported source and the Git history
# that checkpoint rollback depends on. A database-only backup restores rows that point at
# commits which no longer exist, so the volume is captured in the same run.
if [ "${BACKUP_WORKSPACES:-1}" = "1" ]; then
  echo "Creating engineering workspace backup: $ws_out"
  docker run --rm \
    -v ai-platform_engineering_workspaces:/workspaces:ro \
    -v "$BACKUP_DIR":/backup \
    alpine:3.20 sh -c "apk add --no-cache zstd tar >/dev/null && tar -C /workspaces -cf - . | zstd -q -3 -o /backup/$(basename "$ws_out").tmp" \
    && mv "$ws_out.tmp" "$ws_out" && chmod 600 "$ws_out"
  sha256sum "$ws_out" > "$ws_out.sha256"
else
  echo "BACKUP_WORKSPACES=0: skipping workspace volume (database-only backup)" >&2
fi

find "$BACKUP_DIR" -type f \( -name '*.dump' -o -name '*.tar.zst' -o -name '*.sha256' \) -mtime +"${BACKUP_RETENTION_DAYS:-14}" -delete
if [ -n "${BACKUP_UPLOAD_DIR:-}" ]; then
  mkdir -p "$BACKUP_UPLOAD_DIR"
  cp --preserve=mode,timestamps "$db_out" "$db_out.sha256" "$BACKUP_UPLOAD_DIR/"
  [ -f "$ws_out" ] && cp --preserve=mode,timestamps "$ws_out" "$ws_out.sha256" "$BACKUP_UPLOAD_DIR/"
  echo "Copied backup to configured mounted destination: $BACKUP_UPLOAD_DIR"
fi
echo "$db_out"
