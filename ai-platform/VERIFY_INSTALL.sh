#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$ROOT"
fail=0
check(){ printf '%-30s' "$1"; shift; if "$@" >/dev/null 2>&1; then echo OK; else echo FAIL; fail=1; fi; }
check "Docker" docker info
check "Compose config" docker compose config
check "PostgreSQL" docker compose exec -T postgres pg_isready
check "Redis" docker compose exec -T redis redis-cli ping
check "Isolated runner" docker compose exec -T runner python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/health',timeout=3).read()"
check "API liveness" curl -fsS http://127.0.0.1:8080/health/live
check "API readiness" curl -fsS http://127.0.0.1:8080/health/ready
check "Nginx/local gateway" curl -fsS http://127.0.0.1:8080/health
check "Admin console assets" sh -c 'curl -fsS http://127.0.0.1:8080/admin/ | grep -q "/admin/assets/"'
# Retention only runs while this container is up; before 2.1.1 nothing enqueued the sweep.
check "Scheduler running" sh -c 'docker compose ps scheduler | grep -qi " up\| running"'

printf '%-30s %s
' "Disk" "$(df -h "$ROOT" | tail -1 | awk '{print $4" free of "$2}')"
printf '%-30s %s
' "Memory" "$(free -h | awk '/Mem:/ {print $7" available of "$2}')"
if [ -f .env ] && grep -q '^SERVER_NAME=.' .env; then
  set -a; source .env; set +a
  check "TLS ${SERVER_NAME}" curl -fsS "https://${SERVER_NAME}/health"
else echo "TLS                           SKIP (SERVER_NAME not configured)"; fi
docker compose ps
exit "$fail"
