#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(dirname "$ROOT")"
NAME="$(basename "$ROOT")"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

cp -a "$ROOT" "$STAGE/$NAME"
find "$STAGE/$NAME" -type d \( \
  -name __pycache__ -o -name node_modules -o -name build -o -name dist -o \
  -name .dart_tool -o -name .pytest_cache -o -name .mypy_cache -o \
  -name .ruff_cache -o -name .git \
\) -prune -exec rm -rf {} + 2>/dev/null || true
find "$STAGE/$NAME" -type f \( -name '*.pyc' -o -name '.env' \) -delete

# Normalize timestamps so identical source trees produce stable archives.
find "$STAGE/$NAME" -exec touch -h -t 202608200000.00 {} +
rm -f "$OUT/ai-platform-server.zip" "$OUT/ai-platform-android.zip" \
      "$OUT/ai-platform-complete.zip" "$OUT/SHA256SUMS.txt"

cd "$STAGE"
server_paths=(
  "$NAME/server" "$NAME/runner" "$NAME/admin" "$NAME/deploy" "$NAME/scripts" "$NAME/docs"
  "$NAME/.github" "$NAME/docker-compose.yml" "$NAME/docker-compose.prod.yml"
  "$NAME/docker-compose.test.yml" "$NAME/.env.example" "$NAME/Makefile"
  "$NAME/README.md" "$NAME/SECURITY.md" "$NAME/CHANGELOG.md" "$NAME/LICENSE"
  "$NAME/VERSION" "$NAME/INSTALL_ALL.sh" "$NAME/UPGRADE.sh" "$NAME/VERIFY_INSTALL.sh"
  "$NAME/INSTALL-VPS-HE.md"
)
find "${server_paths[@]}" -type f -print | LC_ALL=C sort | zip -X -q "$OUT/ai-platform-server.zip" -@
find "$NAME/android" "$NAME/docs/ANDROID.md" "$NAME/.env.example" "$NAME/README.md" "$NAME/VERSION" \
  -type f -print | LC_ALL=C sort | zip -X -q "$OUT/ai-platform-android.zip" -@
find "$NAME" -type f -print | LC_ALL=C sort | zip -X -q "$OUT/ai-platform-complete.zip" -@

unzip -tq "$OUT/ai-platform-server.zip" >/dev/null
unzip -tq "$OUT/ai-platform-android.zip" >/dev/null
unzip -tq "$OUT/ai-platform-complete.zip" >/dev/null
cd "$OUT"
sha256sum ai-platform-server.zip ai-platform-android.zip ai-platform-complete.zip > SHA256SUMS.txt
cat SHA256SUMS.txt
