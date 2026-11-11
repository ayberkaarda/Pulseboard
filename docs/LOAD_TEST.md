# Load test and observability

This document covers the k6 load test in `scripts/loadtest/` and the optional Prometheus and Grafana stack. Both run locally against the Docker Compose stack; nothing here is a deployment.

## Load test

`scripts/loadtest/pulseboard.js` is a [k6](https://k6.io) script. k6 runs from the `grafana/k6` image, so nothing needs to be installed.

### What it does

`setup()` registers a throwaway tenant (random slug and password, never stored), creates one service with five metrics and an API key. Two scenarios then run in parallel at a constant arrival rate:

| Scenario | Request | Auth | Default rate |
|---|---|---|---|
| `ingest` | `POST /api/v1/ingest`, 500 points per batch | `X-Api-Key` | 30 batches per minute |
| `read` | `GET /api/v1/metrics/{id}/points` over the last two hours, 1 minute step | `Authorization: Bearer` (JWT) | 60 queries per minute |

The rates are deliberately modest. A new tenant is on the FREE plan, which allows 60 ingest batches and 120 metric queries per minute (`INGEST_RATE_LIMIT_FREE_CAPACITY`, `METRIC_QUERY_RATE_LIMIT_CAPACITY`), and the access token lives 15 minutes. The test therefore measures the service and not the rate limiter, and it cannot run longer than about 15 minutes. Any `429` response counts as a failed request and is also counted in `rate_limited_responses`.

Thresholds (the run exits non-zero when one is missed):

- `http_req_failed` rate below 1 percent
- p95 of `http_req_duration` below `P95_MS` (default 500 ms), separately for `ingest` and `read`
- checks pass rate above 99 percent (ingest answers 202 and accepts every point; read answers 200)
- no rate-limited responses

### Running it

Start the stack first (`make up`), then:

```sh
make loadtest
# or directly (Windows Git Bash: prefix with MSYS_NO_PATHCONV=1 and use an absolute path for the volume)
docker run --rm -i -v "$PWD/scripts/loadtest:/loadtest:ro" -e BASE_URL=http://host.docker.internal:8081 \
  grafana/k6:1.3.0 run /loadtest/pulseboard.js
```

`BASE_URL` is the web entry point (`WEB_PORT`, default 8081). `host.docker.internal` works on Docker Desktop; on plain Linux add `--add-host=host.docker.internal:host-gateway`. Optional environment variables, passed with `-e`:

| Variable | Default | Meaning |
|---|---|---|
| `DURATION` | `2m` | Length of both scenarios |
| `INGEST_PER_MINUTE` | `30` | Ingest batches per minute |
| `READ_PER_MINUTE` | `60` | Metric queries per minute |
| `BATCH_SIZE` | `500` | Points per ingest batch (1000 is the API maximum) |
| `METRIC_COUNT` | `5` | Metrics created in `setup()` |
| `P95_MS` | `500` | p95 latency threshold in milliseconds |

Each run creates a new tenant with its data. Remove it with `docker compose -f infra/docker-compose.yml --env-file .env down -v` if you want a clean database. The sample timestamps fall in the hour before the run, which needs a metric partition for the current month; a run in the first hour of a month may spread samples across a month boundary.

Start the observability stack (below) before the run to watch the same run in Grafana.

### Results

Measured with the default settings (`DURATION=2m`, 30 ingest batches per minute of 500 points, 60 reads per minute), single run, k6 `grafana/k6:1.3.0` on the same machine as the stack.

| Item | Value |
|---|---|
| Machine | Windows 11, Docker Desktop (WSL 2 backend), 12 logical CPUs, 16 GB RAM visible to Docker; stack and load generator share the host |
| Stack | `make up` style Compose stack (production images, not `DEV=1`), PostgreSQL and Redis in containers, empty database at the start |
| Requests | 190 HTTP requests (including setup), 0 failed, 0 rate-limited |
| Checks | 243 of 243 passed |

| Scenario | avg | median | p90 | p95 | max |
|---|---|---|---|---|---|
| ingest (500 points per batch) | 50.15 ms | 49.39 ms | 56.19 ms | 57.83 ms | 66.17 ms |
| read (2 h range, 1 min step) | 8.52 ms | 8.32 ms | 9.28 ms | 10.06 ms | 14.41 ms |

These numbers describe a light, constant load on a developer machine with a small database. They are not a capacity statement: the rates are bounded by the per-tenant rate limits, the database held only a few thousand rows per metric, and the load generator competed with the stack for the same CPUs. Run it again on your machine before quoting anything. Higher rates need several tenants (one `setup()` per tenant) or raised rate limits, and neither is covered by this script.

## Observability (optional)

Prometheus and Grafana are defined in `infra/docker-compose.observability.yml` under the Compose profile `observability`. The default `make up` does not start them.

```sh
# .env must contain GRAFANA_ADMIN_PASSWORD (required, no default; generate with: openssl rand -hex 16)
make up-observability     # full stack plus Prometheus and Grafana
make down-observability   # stops the whole project; named volumes are kept
```

- Grafana listens on `127.0.0.1:${GRAFANA_PORT:-3000}` only. The user is `GRAFANA_ADMIN_USER` (default `admin`); sign-up and anonymous access are off.
- Prometheus has no published port. It scrapes `api-core:8081/actuator/prometheus` (management port) and `analytics:9100/metrics` every 15 seconds and keeps 7 days of data. To query it directly, use `docker compose exec prometheus wget -qO- 'http://127.0.0.1:9090/api/v1/query?query=up'`.
- The image versions are pinned (`prom/prometheus:v3.5.0`, `grafana/grafana:12.2.0`).
- Grafana loads the datasource and the dashboard `Pulseboard overview` from `infra/observability/grafana/` at start (provisioned, read-only in the UI).

The dashboard has four groups: api-core HTTP (request rate by status, latency quantiles, 5xx ratio, latency by URI), api-core JVM and Hikari pool, api-core alerting (events created and suppressed, webhook deliveries, evaluation errors) and analytics scoring (runs, duration, scored and failed metrics, anomalies, backlog, model cache, notify calls, database pool, locks). It charts only metrics that exist: api-core currently has custom counters for alerting only (`pulseboard_alerts_*`); ingestion and anomaly volumes are visible through the HTTP metrics of `/api/v1/ingest` and through the analytics counters. The alerting panels stay empty until an alert rule fires. The `application=api-core` label comes from `management.metrics.tags`, and `http_server_requests` publishes histogram buckets so that quantiles can be computed.

`make check-compose` also checks that the default stack contains neither service, that Grafana's password is required, that Grafana is published on loopback only and that Prometheus publishes no port.
