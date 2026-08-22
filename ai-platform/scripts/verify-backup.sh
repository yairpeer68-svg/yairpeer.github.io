#!/usr/bin/env bash
set -euo pipefail
[ $# -ge 1 ] || { echo "Usage: $0 BACKUP.dump [WORKSPACES.tar.zst]" >&2; exit 2; }
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; cd "$ROOT"
backup="$(realpath "$1")"; [ -f "$backup" ] || { echo "Backup not found" >&2; exit 1; }
if [ -f "$backup.sha256" ]; then (cd "$(dirname "$backup")" && sha256sum -c "$(basename "$backup").sha256"); fi
docker compose exec -T postgres pg_restore --list < "$backup" >/dev/null
echo "Database archive is readable and its checksum is valid"
if [ $# -ge 2 ]; then
  workspaces="$(realpath "$2")"; [ -f "$workspaces" ] || { echo "Workspace archive not found" >&2; exit 1; }
  if [ -f "$workspaces.sha256" ]; then (cd "$(dirname "$workspaces")" && sha256sum -c "$(basename "$workspaces").sha256"); fi
  docker run --rm -v "$(dirname "$workspaces")":/backup:ro alpine:3.20 \
    sh -c "apk add --no-cache zstd tar >/dev/null && zstd -dc /backup/$(basename "$workspaces") | tar -tf - >/dev/null"
  echo "Workspace archive is readable and its checksum is valid"
else
  echo "NOTE: no workspace archive checked. A database-only backup is not a complete recovery point." >&2
fi
