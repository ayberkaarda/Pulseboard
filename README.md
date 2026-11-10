# Pulseboard

Multi-tenant service-health and anomaly-analytics platform.

| Component | Path | Stack |
|---|---|---|
| api-core | `apps/api-core` | Java 25, Spring Boot 4.1, Gradle 9.9 |
| analytics | `services/analytics` | Python 3.14, FastAPI, uv |
| web | `apps/web` | Angular 22, Node 26 |
| contracts | `contracts` | OpenAPI 3.1, JSON Schema |
| infra | `infra` | Docker Compose, PostgreSQL 18, Redis 8 |

Architecture notes live in `docs/ARCHITECTURE.md`; decisions in `docs/ADR/`.

## Prerequisites

Java 25, Node 26, Python 3.14 with [uv](https://docs.astral.sh/uv/), Docker with Compose v2, GNU Make.
Versions are pinned in `.tool-versions`.

## Quick start

```sh
cp .env.example .env   # then set every empty value
make up                # build and start the stack, wait until healthy
make down              # stop the stack
```

`make up DEV=1` applies `infra/docker-compose.dev.yml` (hot reload, Postgres/Redis and api-core published on 127.0.0.1 only).
The `analytics` service is reachable only inside the Docker network.

## Make targets

`up`, `down`, `test`, `test-api`, `test-analytics`, `test-web`, `lint`, `openapi-gen`.

## Running locally

Open the app through the web container (`http://localhost:${WEB_PORT}`, default 8081); nginx proxies `/api/` to api-core over the Docker network.
The API is not published to the host in the default stack, because api-core trusts `X-Forwarded-For` from private Docker addresses and a host-published port would let clients spoof their IP for rate limiting.
Port 8081 on the host is the web entry point; api-core's own management port 8081 (`/actuator/*`) exists only inside the Docker network.
`TRUSTED_PROXIES` (api-core env, regex of trusted proxy addresses) defaults to the private Docker network ranges.
`make up DEV=1` binds api-core to `127.0.0.1:${API_CORE_HOST_PORT:-8080}` for local development only.

## OpenAPI contracts

`make openapi-gen` regenerates `contracts/api-core.openapi.yaml` and `contracts/analytics.openapi.yaml` from the code.
CI fails when a checked-in contract differs from what the code produces, so commit the regenerated files with the change that caused them.

## Configuration

Set in `.env` (see `.env.example`); every secret below is required and must be non-empty.

| Variable | Purpose |
|---|---|
| `POSTGRES_PASSWORD`, `API_CORE_DB_PASSWORD`, `ANALYTICS_READER_DB_PASSWORD`, `ANALYTICS_WRITER_DB_PASSWORD` | Database superuser and per-role passwords |
| `REDIS_PASSWORD` | Redis `requirepass`, used by api-core and analytics (URL-safe characters only, e.g. `openssl rand -hex 24`) |
| `INTERNAL_SHARED_SECRET` | HMAC key for api-core / analytics internal calls |
| `JWT_SECRET` | JWT signing key, at least 32 bytes |
| `WEB_PORT` | Host port of the web entry point |
