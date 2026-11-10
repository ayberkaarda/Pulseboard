# Pulseboard Analytics

Anomaly detection service (Python 3.14, FastAPI, uv). Every 60 seconds a scheduled job
scores the last 24 hours of each active metric with a rolling z-score and an isolation
forest, stores new anomalies with the restricted `analytics_writer` role and tells api-core
about the rows it actually inserted (`POST /internal/v1/anomalies/notify`, HMAC signed).
Schema changes remain owned by the Flyway migrations of api-core.

## Endpoints

- `GET /health` - liveness, needs no database or Redis.
- `POST /internal/v1/score` - body `{"metricId": "<uuid>"}`, scores one metric on demand.
  Requires `X-Internal-Signature`, the lowercase hex HMAC-SHA256 of the raw body keyed with
  `INTERNAL_SHARED_SECRET`. Returns `401` (bad signature), `404` (unknown metric), `413`
  (body over 4 KiB), `422` (malformed body).

The OpenAPI document is committed as `contracts/analytics.openapi.yaml`; regenerate it with
`uv run python -m pulseboard_analytics.openapi_export ../../contracts/analytics.openapi.yaml`
(a test fails when it drifts).

## Configuration

| Variable | Meaning |
|---|---|
| `DATABASE_READER_URL` | DSN for `analytics_reader` (metric catalogue and points) |
| `DATABASE_WRITER_URL` | DSN for `analytics_writer` (anomalies, detector state) |
| `REDIS_URL` | Redis for the job lock |
| `API_CORE_BASE_URL` | Base URL of api-core |
| `INTERNAL_SHARED_SECRET` | HMAC key, at least 32 characters, otherwise the service refuses to start |
| `SCHEDULER_ENABLED` | `true` (default) or `false` |
| `SCORE_INTERVAL_SECONDS`, `SCORE_LOCK_TTL_MS` | default `60` and `55000` |
| `SCORE_DEADLINE_SECONDS` | default `50`; a run starts no new metric after this, must be below the lock TTL |
| `SCORE_LEASE_MARGIN_SECONDS` | default `5`; no new metric starts with less lease than this left |
| `METRIC_LOCK_TIMEOUT_SECONDS` | default `10`; how long a caller waits for another caller training the same metric |
| `FOREST_CACHE_MAX` | default `256` fitted forests kept in memory |
| `VERIFY_DB_ROLES` | default `true`: the startup check of the reader and writer privileges; `false` is for tests only |

## Development

```sh
uv sync --locked
uv run ruff check .
uv run ruff format --check .
uv run mypy .
uv run pytest        # needs Docker for the PostgreSQL and Redis containers
```

```sh
docker build -t pulseboard-analytics .
```
