<div align="center">

# Pulseboard

**A multi-tenant service-health and anomaly-analytics platform: ingest metrics, detect anomalies automatically, watch them arrive live.**

English | [Türkçe](README.tr.md)

[![api-core](https://github.com/ayberkaarda/pulseboard/actions/workflows/api-core.yml/badge.svg)](https://github.com/ayberkaarda/pulseboard/actions/workflows/api-core.yml)
[![analytics](https://github.com/ayberkaarda/pulseboard/actions/workflows/analytics.yml/badge.svg)](https://github.com/ayberkaarda/pulseboard/actions/workflows/analytics.yml)
[![web](https://github.com/ayberkaarda/pulseboard/actions/workflows/web.yml/badge.svg)](https://github.com/ayberkaarda/pulseboard/actions/workflows/web.yml)
[![e2e](https://github.com/ayberkaarda/pulseboard/actions/workflows/e2e.yml/badge.svg)](https://github.com/ayberkaarda/pulseboard/actions/workflows/e2e.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

<img src="docs/images/overview-light.png" alt="Pulseboard overview: one tile per service with Healthy, Degraded, Down, Stale and No data states" width="900">

</div>

## Features

- **Multi-tenant by design.** Every request is scoped to a tenant taken from the JWT or the API key; repositories for tenant-owned data cannot be called without a tenant id (enforced by an ArchUnit rule).
- **Metric ingestion.** Agents send batches of up to 1,000 points to `POST /api/v1/ingest` with an API key. Writes are idempotent bulk upserts and are rate limited per tenant.
- **Downsampled metric queries.** Time-series are served as buckets chosen to stay under a fixed point budget, with partitioned storage and a statement timeout per query.
- **Anomaly detection.** A scheduled service scores every active metric with two detectors: a rolling z-score and an Isolation Forest. Each anomaly carries a detector, a score and a severity (LOW, MEDIUM, HIGH).
- **Live feed over SSE.** New anomalies are pushed to the dashboard through Server-Sent Events. Browsers connect with a one-time, short-lived ticket instead of a long-lived token in the URL.
- **Acknowledgement.** Anomalies can be acknowledged from the feed or the metric page, with optimistic updates and rollback.
- **Health tiles.** The overview shows each service as Healthy, Degraded, Down, Stale (old data, nothing open) or No data. State is never conveyed by colour alone: every state has its own glyph.
- **Security posture.** argon2id, refresh-token rotation with reuse detection, hashed API keys, rate limits, HMAC-signed internal endpoints, least-privilege database roles and a strict Content-Security-Policy. See [Security highlights](#security-highlights).
- **Accessible light and dark themes** that follow the system preference, a responsive layout from phone to desktop, and an axe-core audit of every page.

## Architecture

```mermaid
flowchart LR
    Browser["Browser<br/>Angular SPA"] -->|"HTTP"| Nginx["web<br/>nginx"]
    Agent["Metric agents"] -->|"POST /api/v1/ingest<br/>X-Api-Key"| Nginx
    Nginx -->|"/api/"| API["api-core<br/>Spring Boot"]
    API --> PG[("PostgreSQL 18")]
    API <--> Redis[("Redis 8")]
    AN["analytics<br/>FastAPI"] -->|"analytics_reader (read)<br/>analytics_writer (write)"| PG
    AN -->|"POST /internal/v1/anomalies/notify<br/>HMAC-SHA256"| API
    AN -.->|"job lock"| Redis
    API -->|"PUBLISH anomalies:tenantId"| Redis
    API -->|"SSE stream"| Nginx
```

1. Agents ingest metric points through nginx into **api-core**, which authenticates the API key, rate limits the tenant and upserts into a monthly-partitioned PostgreSQL table.
2. Every 60 seconds the **analytics** service scores the last 24 hours of each active metric and writes new anomalies with a restricted database role.
3. It then tells api-core about the rows it really inserted through an HMAC-signed internal call. api-core re-reads them from the database and publishes them to a per-tenant Redis channel.
4. Every api-core instance holding an SSE stream for that tenant forwards the event to the browser.

Details, sequence diagrams, the domain model and the detector specifications are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Screenshots

Everything below was captured from the running Docker Compose stack with demo data.

### Overview

| Light | Dark |
|---|---|
| ![Overview, light](docs/images/overview-light.png) | ![Overview, dark](docs/images/overview-dark.png) |

### Anomalies

Severity is shown as a glyph and a word, newest first, with filters and one-click acknowledgement.

| Light | Dark |
|---|---|
| ![Anomalies, light](docs/images/anomalies-light.png) | ![Anomalies, dark](docs/images/anomalies-dark.png) |

### Metric chart with anomaly markers

Markers differ by shape (circle, diamond, triangle) as well as colour; the page repeats the numbers as text and as a table.

| Light | Dark |
|---|---|
| ![Metric chart, light](docs/images/metric-light.png) | ![Metric chart, dark](docs/images/metric-dark.png) |

### Mobile

| Overview | Anomalies | Metric |
|---|---|---|
| <img src="docs/images/mobile-overview-light.png" alt="Mobile overview, light" width="260"> | <img src="docs/images/mobile-anomalies-dark.png" alt="Mobile anomalies, dark" width="260"> | <img src="docs/images/mobile-metric-light.png" alt="Mobile metric, light" width="260"> |

### Sign in

| Light | Dark |
|---|---|
| ![Sign in, light](docs/images/login-light.png) | ![Sign in, dark](docs/images/login-dark.png) |

## Tech stack

| Component | Path | Stack |
|---|---|---|
| api-core | [`apps/api-core`](apps/api-core) | Java 25, Spring Boot 4.1, Spring Security (JWT), Spring Data JPA, Flyway, Gradle 9.9, Testcontainers, ArchUnit |
| analytics | [`services/analytics`](services/analytics) | Python 3.14, FastAPI, SQLAlchemy 2, APScheduler, NumPy, pandas, scikit-learn, uv |
| web | [`apps/web`](apps/web) | Angular 22 (standalone, signals, zoneless), ECharts, Angular Material, nginx |
| contracts | [`contracts`](contracts) | OpenAPI 3.1 for both services, JSON Schema for events |
| infra | [`infra`](infra) | Docker Compose, PostgreSQL 18, Redis 8 |
| CI | [`.github/workflows`](.github/workflows) | GitHub Actions: one workflow per component plus a nightly end-to-end run |

## Quick start

Pulseboard is a local portfolio project and runs with Docker Compose only; there are no deployment instructions here.

**Requirements:** Docker with Compose v2. For the optional end-to-end check also Python 3. GNU Make is convenient but not required.

**1. Create `.env`.** Copy the template and set every empty value (Compose refuses to start with an empty secret).

```sh
cp .env.example .env              # Windows PowerShell: Copy-Item .env.example .env
```

Generate each secret with `openssl rand -hex 24` (use `openssl rand -hex 32` for `INTERNAL_SHARED_SECRET` and `JWT_SECRET`). Without OpenSSL, for example on Windows:

```sh
py -c "import secrets; print(secrets.token_hex(32))"      # Linux/macOS: python3 -c ...
```

**2. Start the stack** and wait until every container is healthy.

```sh
make up
# without make (Windows without GNU Make, for example):
docker compose --env-file .env -f infra/docker-compose.yml up -d --build --wait
```

**3. Open the app** at <http://localhost:8081> (or `WEB_PORT` from `.env`) and choose "Create a workspace".

**4. Stop it.**

```sh
make down
# or: docker compose --env-file .env -f infra/docker-compose.yml down        (add -v to delete the data volumes)
```

`make up DEV=1` adds `infra/docker-compose.dev.yml` (hot reload; Postgres, Redis and api-core published on `127.0.0.1` only).
In the default stack only the web container is published: api-core trusts `X-Forwarded-For` from private Docker addresses, so a host-published port would let clients spoof their address for rate limiting. Everything below therefore goes through nginx on port 8081.

### Send test data

The registration, service, metric and API key calls below use the request shapes of [contracts/api-core.openapi.yaml](contracts/api-core.openapi.yaml) and the same sequence as [scripts/e2e/run.py](scripts/e2e/run.py). They need a POSIX shell with `curl` and `jq` (Linux, macOS, WSL or Git Bash).

```sh
BASE=http://localhost:8081

# 1. Register a workspace (the password needs at least 12 characters) and keep the access token.
TOKEN=$(curl -s -X POST $BASE/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"demo@example.com","password":"change-me-please-123","tenantName":"Acme Demo","tenantSlug":"acme-demo"}' \
  | jq -r .accessToken)

# 2. Create a service and a metric.
SERVICE=$(curl -s -X POST $BASE/api/v1/services -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"checkout","environment":"PROD"}' | jq -r .id)
METRIC=$(curl -s -X POST $BASE/api/v1/services/$SERVICE/metrics -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"latency.p95","unit":"ms","aggregation":"AVG"}' | jq -r .id)

# 3. Create an API key. The plain key is returned once.
KEY=$(curl -s -X POST $BASE/api/v1/api-keys -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"demo agent"}' | jq -r .key)

# 4. Ingest points with the API key.
curl -s -X POST $BASE/api/v1/ingest -H "X-Api-Key: $KEY" -H 'Content-Type: application/json' \
  -d "{\"source\":\"curl\",\"points\":[{\"metricId\":\"$METRIC\",\"ts\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"value\":101.3}]}"
# {"accepted":1,"rejected":[]}
```

Later sign-ins use `POST /api/v1/auth/login` with `email`, `password` and `tenantSlug`.

The detectors need history before they flag anything (the z-score at least 31 points, the Isolation Forest at least 60), and the scheduler runs once a minute. To see real anomalies, ingest a few hundred points with a spike; [scripts/e2e/run.py](scripts/e2e/run.py) shows a complete example (300 points around 100 with a short spike).

### End-to-end check

`make e2e` starts a throwaway Compose project (`pulseboard-e2e`) with its own secrets, ingests a noisy series with a spike, waits for the scheduler and asserts the anomaly over REST and SSE, tenant isolation and the internal HMAC behaviour. It never touches your regular stack or `.env`, and always removes what it created.

```sh
make e2e
# Windows: the Makefile defaults PYTHON to "py", so this also works:  PYTHON=py make e2e
# Without make:  py scripts/e2e/run.py        (Linux/macOS: python3 scripts/e2e/run.py)
# After an interrupted run:  make e2e-down
```

## Testing and quality gates

Each component has hundreds of automated tests (at the time of writing: api-core 374, analytics 326, web 374).

| Component | Command (from the repository root) | Notes |
|---|---|---|
| all | `make test` | api-core, analytics and web in turn |
| all | `make lint` | checkstyle, ruff + mypy, ESLint |
| api-core | `cd apps/api-core && ./gradlew checkstyleMain checkstyleTest test verifyOpenApiDocs` | Needs Docker (Testcontainers); `verifyOpenApiDocs` fails when the code and the checked-in contract differ |
| analytics | `cd services/analytics && uv sync --locked && uv run ruff check . && uv run ruff format --check . && uv run mypy . && uv run pytest` | Needs Docker (PostgreSQL and Redis containers) |
| web | `cd apps/web && npm run api:check && npm run lint && npm run format:check && npm run typecheck && npm test && npm run build` | Jest; `api:check` compares the generated API types with the contract |
| web (browser) | `cd apps/web && BASE=http://localhost:8081 node scripts/browser-audit.mjs` | axe-core scan of every page in both themes against a running stack; needs Chrome, not part of CI |
| contracts | `make openapi-gen` | Regenerates both OpenAPI documents; CI fails on drift |
| compose | `make check-compose` | Verifies that Compose passes the tunable settings to the services |

On Windows without GNU Make, run the commands from the "Command" column directly.

## Security highlights

- **Passwords:** argon2id.
- **Sessions:** short-lived JWT access tokens; refresh tokens rotate on every use, reuse of a rotated token revokes the whole family, a family ends at an absolute lifetime (30 days by default), and logout revokes the family server-side.
- **API keys:** stored only as hashes, cached in Redis for a minute, with revocation markers that take effect immediately.
- **Rate limits:** per client address and per account on authentication, per tenant on ingestion and metric queries, per address on API-key requests and on failed key attempts. Redis being down fails closed.
- **Internal endpoints:** `X-Internal-Signature` (HMAC-SHA256 over the raw body, constant-time comparison, body size limit before hashing), on their own security chain; `/internal/` is blocked by the public proxy.
- **Database roles:** analytics uses a read-only role and a write role limited to `anomaly` and `detector_state`; it verifies at startup that the roles hold exactly the expected privileges and refuses to start otherwise.
- **Live stream:** one-time tickets (32 random bytes, 30 seconds, single use, stored only as a hash); `ticket=` and `access_token=` are masked in logs and the proxy does not log query strings.
- **Browser:** strict Content-Security-Policy with a per-response nonce and no inline script, the access token kept in memory only, safe `returnUrl` handling.

Accepted trade-offs are listed in [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).

## Project status

| Phase | Scope | Status |
|---|---|---|
| P0 | Foundation: monorepo, pinned toolchains, Compose, CI, Makefile | Done |
| P1 | Core domain and auth: schema, tenancy, register/login/refresh, API keys | Done |
| P2 | Ingestion and queries: batch ingest, rate limits, downsampled metric queries, OpenAPI | Done |
| P3 | Analytics service: detectors, scheduler, internal notify, SSE publish path | Done |
| P4 | Dashboard: auth flows, overview, metric charts, live anomaly feed, acknowledgement | Done |
| P5 | Alerting: rules, evaluation, webhook delivery, history | Planned |
| P6 | Hardening: observability, load test, security checklist, final docs and ADRs | Planned |

Alerting is not built yet. `docs/ARCHITECTURE.md` describes its intended design; the current API and UI have no alert rules.

## Project structure

```text
pulseboard/
├── apps/
│   ├── api-core/          Spring Boot service: identity, tenancy, ingestion, queries, anomalies, SSE
│   └── web/               Angular 22 dashboard served by nginx
├── services/
│   └── analytics/         FastAPI service: detectors, scheduler, anomaly writes
├── contracts/             OpenAPI documents and JSON Schemas of the events
├── infra/                 Docker Compose files and PostgreSQL role setup
├── scripts/               End-to-end check and Compose settings check
├── docs/                  Architecture, known limitations, screenshots
├── .github/workflows/     CI: api-core, analytics, web, e2e
├── Makefile
└── .env.example
```

## Documentation

| Document | Contents |
|---|---|
| [docs/README.md](docs/README.md) | Index of the documentation |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Components, domain model, key flows, detector specifications, internal contracts |
| [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) | Accepted trade-offs |
| `docs/ADR` | Architecture decision records (the folder is empty so far) |
| [contracts](contracts) | `api-core.openapi.yaml`, `analytics.openapi.yaml`, event JSON Schemas |
| [apps/api-core/README.md](apps/api-core/README.md) | Backend overview |
| [services/analytics/README.md](services/analytics/README.md) | Detectors, job flow, settings |
| [apps/web/README.md](apps/web/README.md) | Frontend overview; design system in [apps/web/DESIGN.md](apps/web/DESIGN.md) |

## Configuration

Set in `.env` (see [.env.example](.env.example)); every secret is required and must be non-empty.

| Variable | Purpose |
|---|---|
| `POSTGRES_PASSWORD`, `API_CORE_DB_PASSWORD`, `ANALYTICS_READER_DB_PASSWORD`, `ANALYTICS_WRITER_DB_PASSWORD` | Database superuser and per-role passwords |
| `REDIS_PASSWORD` | Redis `requirepass`, used by api-core and analytics (URL-safe characters only) |
| `INTERNAL_SHARED_SECRET` | HMAC key for api-core / analytics internal calls, at least 32 characters |
| `JWT_SECRET` | JWT signing key, at least 32 bytes |
| `WEB_PORT` | Host port of the web entry point (default 8081) |

Tunable limits and scheduler settings are documented in `.env.example` and in the component READMEs.

## Contributing

This is a personal portfolio project and is not open for outside contributions at the moment. Issues and suggestions are welcome. Changes follow conventional commits (`feat`, `fix`, `docs`, `test`, `build`), and CI fails when a checked-in OpenAPI contract differs from what the code produces, so regenerate it with `make openapi-gen`.

## License

Released under the [MIT License](LICENSE). Copyright (c) 2026 Ayberk Arda.
