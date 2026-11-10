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

`make up DEV=1` applies `infra/docker-compose.dev.yml` (hot reload, exposed Postgres/Redis ports).
The `analytics` service is reachable only inside the Docker network.

## Make targets

`up`, `down`, `test`, `test-api`, `test-analytics`, `test-web`, `lint`, `openapi-gen`.
