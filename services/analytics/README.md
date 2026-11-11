# Pulseboard Analytics

English | [Türkçe](README.tr.md)

Anomaly detection service (Python 3.14, FastAPI, uv). Every 60 seconds a scheduled job scores the last 24 hours of each active metric with a rolling z-score and an Isolation Forest, stores new anomalies with the restricted `analytics_writer` role and tells api-core about the rows it actually inserted (`POST /internal/v1/anomalies/notify`, HMAC signed).
Schema changes remain owned by the Flyway migrations of api-core; analytics reads and writes existing tables without its own migrations.

The service is reachable only inside the Docker network. It has no authentication of its own other than the HMAC signature on its single internal endpoint.

## Detectors

Both detectors are pure functions over a `pandas.DataFrame` (see `detectors/base.py` for the input schema). They work on whatever points exist in the 24 hour window; the thresholds below do not depend on the sampling interval.

| | Rolling z-score (`detectors/zscore.py`) | Isolation Forest (`detectors/isolation_forest.py`) |
|---|---|---|
| Baseline | The (at most) 288 points before each point, so a spike never dilutes its own baseline | The whole 24 hour window, fitted per metric |
| Minimum data | More than 30 points (a point is scored once 30 preceding points exist) | 60 points |
| Features | `value` | `value`, `diff_1`, `rolling_mean_12`, `rolling_std_12`, `hour_of_day_sin`, `hour_of_day_cos` |
| Model | none | `n_estimators=200`, `contamination=0.02`, `random_state=42` |
| Anomaly when | `\|z\| >= 3` | score `>= 0.65` |
| Score | `min(1, \|z\| / 6)` | `1 - normalised decision_function`, in `[0, 1]` |
| Severity | LOW `3 <= \|z\| < 4`, MEDIUM `4 <= \|z\| < 5`, HIGH `\|z\| >= 5` | Tertiles of `[0.65, 1.0]`: LOW below 0.767, MEDIUM from 0.767, HIGH from 0.883 |
| Retraining | not applicable | Every 6 hours or when the point count grew by at least 20 percent; `trained_at`, `version` and the contamination are stored in `detector_state.params` |
| Flat series | No anomalies when the baseline is constant | No anomalies when the value column is flat; features are standardised (median and MAD) and clipped |

The z-score works on values divided by a power of two near the largest magnitude (an exact operation), so extremely small or large units do not underflow. Only the parameters of the Isolation Forest are persisted, not the fitted model: after a restart each model is refitted on the next run (see [docs/KNOWN_LIMITATIONS.md](../../docs/KNOWN_LIMITATIONS.md) for the accepted limits, such as false positives of the Isolation Forest on clean seasonal data).

## Job flow

`jobs/score_all.py` runs under APScheduler inside the service process (`SCHEDULER_ENABLED`, keep exactly one replica):

1. **Lock.** `SET NX PX` on a Redis key (`SCORE_LOCK_TTL_MS`, owner token, compare-and-delete release). A run that finds the lock held is skipped. The lock bounds the work of a run but is not leader election; correctness rests on the idempotent inserts.
2. **Select.** Metrics with at least one point in the last 24 hours are read through `analytics_reader`. A Redis cursor remembers where a run stopped at its deadline (`SCORE_DEADLINE_SECONDS`), so the next run continues after that metric and every metric gets its turn. A metric that exhausts the run budget is backed off (120 s doubling up to 3600 s) and runs after the others once it is due.
3. **Score.** For each metric the last 24 hours are loaded and both detectors run. Every run re-scores the whole window, so late points are picked up. A failure for one metric does not stop the others.
4. **Store.** `INSERT ... ON CONFLICT (metric_id, detector, window_start, window_end) DO NOTHING RETURNING` through `analytics_writer`. Only rows that were really inserted are reported, so re-scoring never produces duplicates or repeated notifications. `detector_state` is upserted.
5. **Notify.** The inserted ids go to api-core in batches of at most 500, HMAC signed, with three attempts and exponential backoff on network errors, 5xx and 429. A failed notification is logged and does not undo stored anomalies; they stay visible through `GET /api/v1/anomalies`.

api-core then re-reads every announced id and publishes it on the Redis channel `anomalies:{tenantId}`, from where it reaches the dashboard as a server-sent event.

## Role model

| Role | Access |
|---|---|
| `analytics_reader` | Read-only on `metric_point` and the metric catalogue |
| `analytics_writer` | Limited to `anomaly` and `detector_state` |

The roles are created by `infra/postgres/init`, which revokes everything from `PUBLIC` first and then grants explicitly; their table grants live in the api-core migrations. At startup (`VERIFY_DB_ROLES`, default `true`) the service checks that neither role can create objects in `public`, that each has exactly the required privileges, that nothing is granted on other schemas, and that no reachable role has superuser, CREATEROLE, CREATEDB, REPLICATION or BYPASSRLS. It refuses to start otherwise.

## Endpoints

- `GET /health` - liveness, needs no database or Redis.
- `POST /internal/v1/score` - body `{"metricId": "<uuid>"}`, scores one metric on demand.
  Requires `X-Internal-Signature`, the lowercase hex HMAC-SHA256 of the raw body keyed with `INTERNAL_SHARED_SECRET` (constant-time comparison; the secret and signatures are never logged). Returns `401` (bad signature), `404` (unknown metric), `413` (body over 4 KiB), `422` (malformed body).

The same signature scheme protects `POST /internal/v1/anomalies/notify` on api-core, which this service calls. It covers the body only (no timestamp); that is acceptable because `notify` is idempotent and publishes only what the database holds.

The OpenAPI document is committed as [`contracts/analytics.openapi.yaml`](../../contracts/analytics.openapi.yaml); regenerate it with
`uv run python -m pulseboard_analytics.openapi_export ../../contracts/analytics.openapi.yaml`
(a test fails when it drifts).

## Configuration

Read from the environment by `settings.py`; invalid values stop the service at startup.

| Variable | Default | Meaning |
|---|---|---|
| `DATABASE_READER_URL` | required | DSN for `analytics_reader` (metric catalogue and points) |
| `DATABASE_WRITER_URL` | required | DSN for `analytics_writer` (anomalies, detector state) |
| `REDIS_URL` | required | Redis for the job lock, cursor and backoff state |
| `API_CORE_BASE_URL` | required | Base URL of api-core |
| `INTERNAL_SHARED_SECRET` | required | HMAC key, at least 32 characters (after trimming), otherwise the service refuses to start |
| `SCHEDULER_ENABLED` | `true` | Turns the scheduled job on or off |
| `SCORE_INTERVAL_SECONDS` | `60` | Interval of the job |
| `SCORE_LOCK_TTL_MS` | `55000` | Redis lease of a run |
| `SCORE_DEADLINE_SECONDS` | `50` | A run starts no new metric after this; must be below the lock TTL |
| `SCORE_LEASE_MARGIN_SECONDS` | `5` | No new metric starts with less lease than this left |
| `METRIC_LOCK_TIMEOUT_SECONDS` | `10` | How long a caller waits for another caller training the same metric |
| `DB_POOL_TIMEOUT_SECONDS`, `DB_CONNECT_TIMEOUT_SECONDS` | `5`, `5` | Wait for a free connection and for opening one |
| `FOREST_CACHE_MAX` | `256` | Fitted forests kept in memory |
| `VERIFY_DB_ROLES` | `true` | Startup check of the reader and writer privileges; `false` is for tests only |
| `METRICS_ENABLED`, `METRICS_PORT` | `true`, `9100` | Prometheus listener on its own port (never the main application) |

## Metrics

The service serves Prometheus metrics (`pulseboard_analytics_*`: scoring runs and their duration, metrics scored and failed, anomalies inserted by severity, notify calls to api-core, model cache and trainings, database pool waits, lock acquisition) from a separate listener on `METRICS_PORT` (9100), which the main application never mounts. Labels are bounded sets (outcomes, detectors, severities); metric ids, tenant ids and paths are never labels. The default stack does not publish the port. The optional Prometheus and Grafana profile scrapes it; see [docs/LOAD_TEST.md](../../docs/LOAD_TEST.md).

Alert rules for the anomalies this service writes are evaluated by api-core after it receives the `notify` call.

## Development

```sh
uv sync --locked
uv run ruff check .
uv run ruff format --check .
uv run mypy .            # strict
uv run pytest            # needs Docker for the PostgreSQL and Redis containers
```

From the repository root, `make test-analytics` runs the tests and `make lint` includes ruff and mypy. The detectors are tested with synthetic series (injected spikes, level shifts, seasonal baselines), the repositories and the job against real PostgreSQL and Redis containers.

```sh
docker build -t pulseboard-analytics .
```

The full chain (ingest, scoring, notification, REST and SSE) is checked by `make e2e`; see the [root README](../../README.md).
