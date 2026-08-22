#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; cd "$ROOT"
if [ "${EUID}" -eq 0 ]; then SUDO=""; else SUDO="sudo"; sudo -v; fi
[ -f .env ] || { echo "ERROR: create .env from .env.example and set strong secrets before installation." >&2; exit 1; }
if ! command -v docker >/dev/null 2>&1; then
  echo "Installing Docker Engine and Compose plugin..."
  $SUDO apt-get update
  $SUDO apt-get install -y ca-certificates curl gnupg
  $SUDO install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | $SUDO gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  $SUDO chmod a+r /etc/apt/keyrings/docker.gpg
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $VERSION_CODENAME stable" | $SUDO tee /etc/apt/sources.list.d/docker.list >/dev/null
  $SUDO apt-get update
  $SUDO apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  $SUDO systemctl enable --now docker
fi
docker compose version >/dev/null
mkdir -p backups; chmod 700 backups
chmod 600 .env
mkdir -p /var/www/certbot 2>/dev/null || true
docker compose config >/dev/null
docker compose up -d postgres redis
for i in $(seq 1 60); do
  if docker compose exec -T postgres pg_isready -U "$(grep '^POSTGRES_USER=' .env | cut -d= -f2-)" >/dev/null 2>&1 && docker compose exec -T redis redis-cli ping | grep -q PONG; then break; fi
  [ "$i" -eq 60 ] && { echo "Database or Redis did not become healthy" >&2; exit 1; }; sleep 2
done
docker compose build api worker runner nginx
docker compose run --rm api alembic upgrade head
docker compose up -d
for i in $(seq 1 60); do curl -fsS http://127.0.0.1:8080/health/ready >/dev/null 2>&1 && break; [ "$i" -eq 60 ] && { docker compose ps; exit 1; }; sleep 2; done
echo "AI Platform installed. Local gateway: http://127.0.0.1:8080"
echo "For production TLS, configure SERVER_NAME and use docker-compose.prod.yml after obtaining Let's Encrypt certificates."
