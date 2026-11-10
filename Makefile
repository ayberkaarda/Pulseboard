COMPOSE_BASE := docker compose --env-file .env -f infra/docker-compose.yml
COMPOSE_DEV  := $(COMPOSE_BASE) -f infra/docker-compose.dev.yml

.PHONY: up down test test-api test-analytics test-web lint openapi-gen check-env

check-env:
	@test -f .env || (echo "Missing .env: copy .env.example to .env and set every value." && exit 1)

# Start the full stack and wait for healthy containers. Use DEV=1 for development overrides.
up: check-env
ifeq ($(DEV),1)
	$(COMPOSE_DEV) up -d --build --wait
else
	$(COMPOSE_BASE) up -d --build --wait
endif

down: check-env
	$(COMPOSE_BASE) down

test: test-api test-analytics test-web

test-api:
	cd apps/api-core && ./gradlew test

test-analytics:
	cd services/analytics && uv run pytest

test-web:
	cd apps/web && npm test

lint:
	cd apps/api-core && ./gradlew checkstyleMain checkstyleTest
	cd services/analytics && uv run ruff check . && uv run ruff format --check . && uv run mypy .
	cd apps/web && npm run lint

# Regenerates contracts/*.openapi.yaml from the running code.
# Both documents are checked in; CI fails when they drift from the code.
openapi-gen:
	cd apps/api-core && ./gradlew generateOpenApiDocs --no-daemon
	cd services/analytics && uv run python -m pulseboard_analytics.openapi_export ../../contracts/analytics.openapi.yaml
