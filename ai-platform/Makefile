SHELL := /bin/bash
COMPOSE=docker compose
.PHONY: dev up down logs test test-backend test-integration test-admin test-android lint security migrate backup restore verify package

dev: up
up:
	$(COMPOSE) up -d --build
down:
	$(COMPOSE) down
logs:
	$(COMPOSE) logs -f --tail=200
migrate:
	$(COMPOSE) run --rm api alembic upgrade head
test: test-backend test-admin test-android
test-backend:
	cd server && pytest -q tests/unit
test-integration:
	docker compose -f docker-compose.test.yml up --build --abort-on-container-exit --exit-code-from tests; code=$$?; docker compose -f docker-compose.test.yml down -v; exit $$code
test-admin:
	cd admin && npm install && npm run lint && npm run test && npm run build
test-android:
	cd android && flutter pub get && flutter analyze && flutter test
lint:
	cd server && ruff check app tests && mypy app
security:
	cd server && bandit -r app -q && pip-audit -r requirements.txt
	cd admin && npm audit --audit-level=high
backup:
	./scripts/backup.sh
restore:
	@test -n "$(FILE)" || (echo 'Usage: make restore FILE=/path/backup.dump' && exit 2)
	./scripts/restore.sh "$(FILE)"
verify:
	./VERIFY_INSTALL.sh
package:
	./scripts/package.sh
