COMPOSE_BASE := docker compose --env-file .env -f infra/docker-compose.yml
COMPOSE_DEV  := $(COMPOSE_BASE) -f infra/docker-compose.dev.yml
COMPOSE_OBS  := $(COMPOSE_BASE) -f infra/docker-compose.observability.yml --profile observability

.PHONY: up down up-observability down-observability loadtest test test-api test-analytics test-web lint openapi-gen check-env check-compose e2e e2e-down

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

# Full stack plus Prometheus and Grafana (profile `observability`; Grafana on 127.0.0.1:$GRAFANA_PORT, default 3000).
# Needs GRAFANA_ADMIN_PASSWORD in .env.
up-observability: check-env
	$(COMPOSE_OBS) up -d --build --wait

# Stops everything started by up-observability, including Prometheus and Grafana (volumes are kept).
down-observability: check-env
	$(COMPOSE_OBS) down

# k6 load test against the running stack through the web entry point; see docs/LOAD_TEST.md.
loadtest:
	docker run --rm -i -v "$(CURDIR)/scripts/loadtest:/loadtest:ro" -e BASE_URL=$${BASE_URL:-http://host.docker.internal:8081} grafana/k6:1.3.0 run /loadtest/pulseboard.js

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

# Checks that docker compose passes the tunable settings (defaults and overrides) to the services.
check-compose:
	$(PYTHON) scripts/check_compose_env.py

# End-to-end check of ingest -> analytics -> anomalies (REST + SSE) in a throwaway compose project
# (pulseboard-e2e, own secrets). It does not touch the regular stack or .env. Needs Docker and Python 3.
ifeq ($(OS),Windows_NT)
PYTHON ?= py
else
PYTHON ?= python3
endif
e2e:
	$(PYTHON) scripts/e2e/run.py

# Removes the e2e project (containers, networks, volumes) if a run was interrupted.
e2e-down:
	docker compose -p pulseboard-e2e down -v --remove-orphans
