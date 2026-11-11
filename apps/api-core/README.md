# Pulseboard api-core

English | [Türkçe](README.tr.md)

The backend of Pulseboard: identity and tenancy, metric ingestion and queries, the service and metric catalogue, anomalies and their live stream, alert rules and webhook delivery.
Java 25, Spring Boot 4.1, Spring Web MVC on virtual threads, Spring Security (JWT), Spring Data JPA with Hibernate, Flyway, PostgreSQL 18 and Redis 8.

It is the only component the browser and the metric agents talk to. The `analytics` service calls back into it through a signed internal endpoint.

## Package layout

Root package `io.pulseboard.core`. Layering is `api` -> `application` -> `domain` <- `infrastructure`; `domain` has no Spring dependency. ArchUnit tests enforce the layering and the tenant-scoping rule (a repository method for tenant-owned data must take a tenant id).

| Layer | Contents |
|---|---|
| `api` | REST controllers and DTOs by area: `auth`, `apikey`, `catalog` (services and metrics), `ingest`, `metrics` (queries), `anomaly`, `alert` (rules and history), `stream` (SSE and tickets), `internal` (HMAC endpoint), `ratelimit`, `error` (RFC 9457 problem details), `openapi` |
| `application` | Use cases: `auth`, `apikey`, `catalog`, `ingest`, `query`, `anomaly`, `alert` (evaluation, outbox dispatch, webhook target guard and signer), `streamticket`, `ratelimit`, `tenancy`, `common` |
| `domain` | Entities and rules without framework code: `tenancy`, `identity`, `catalog`, `metrics`, `anomaly`, `alert`, `shared` |
| `infrastructure` | Adapters: `persistence`, `security` (JWT, API key and internal HMAC filters), `cache`, `ratelimit` (Redis token bucket), `ingest` (JDBC bulk upsert), `stream` (Redis pub/sub to SSE), `anomaly`, `alert` (JDBC outbox, Redis cooldown, HTTP sender, startup safety check), `logging` (secret masking), `time` |

## Endpoints

The authoritative description is [contracts/api-core.openapi.yaml](../../contracts/api-core.openapi.yaml) (regenerated with `make openapi-gen`; CI fails on drift). Errors are `application/problem+json`.

| Method and path | Auth | Purpose |
|---|---|---|
| `POST /api/v1/auth/register` | none | Create a tenant and its first user, returns tokens |
| `POST /api/v1/auth/login` | none | Sign in with `email`, `password`, `tenantSlug` |
| `POST /api/v1/auth/refresh` | refresh token | Rotate the refresh token, issue a new access token |
| `POST /api/v1/auth/logout` | refresh token | Revoke the whole token family (idempotent, always 204) |
| `GET`, `POST /api/v1/services` | JWT | List or create services |
| `GET /api/v1/services/{serviceId}` | JWT | Read one service (with `lastPointAt`) |
| `GET`, `POST /api/v1/services/{serviceId}/metrics` | JWT | List or create metric definitions |
| `GET /api/v1/metrics/{metricId}` | JWT | Read one metric definition |
| `GET /api/v1/metrics/{metricId}/points` | JWT | Downsampled series (at most 2000 buckets; optional `step`) |
| `POST /api/v1/api-keys` | JWT | Create an API key (the plain key is returned once) |
| `DELETE /api/v1/api-keys/{id}` | JWT | Revoke an API key |
| `POST /api/v1/ingest` | `X-Api-Key` | Ingest up to 1,000 points; answers 202 with `accepted` and `rejected` |
| `GET /api/v1/anomalies` | JWT | Paged list with filters (metric, service, severity, acknowledged, range) |
| `GET /api/v1/anomalies/{id}` | JWT | Read one anomaly |
| `POST /api/v1/anomalies/{id}/ack` | JWT | Acknowledge an anomaly |
| `POST /api/v1/stream/ticket` | JWT | Issue a one-time stream ticket (30 per minute per user) |
| `GET /api/v1/stream/anomalies` | JWT bearer or `?ticket=` | Server-Sent Events stream of new anomalies |
| `GET`, `POST /api/v1/alert-rules` | JWT | List rules (any user; viewers see webhook targets reduced to the host) or create one (owner or admin) |
| `GET`, `PUT`, `DELETE /api/v1/alert-rules/{id}` | JWT | Read, replace (with the `version` the edit started from) or delete a rule; changes are for owners and admins |
| `PATCH /api/v1/alert-rules/{id}` | JWT | Switch a rule on or off (owner or admin) |
| `GET /api/v1/alert-events` | JWT | Alert history, newest first, paged, filtered by rule, delivery status and trigger time |
| `GET /api/v1/alert-events/{id}` | JWT | Read one alert event with its payload |
| `POST /internal/v1/anomalies/notify` | `X-Internal-Signature` | Called by analytics after it stored anomalies; not part of the public API |

## Alerting

A rule watches one metric of the caller's tenant (`THRESHOLD` with an operator and a number, or `ANOMALY_SEVERITY` with a minimum severity) and notifies through `WEBHOOK` or `EMAIL_LOG`. A tenant owns at most 50 rules by default; names are unique per tenant; API keys cannot manage rules.

- **Evaluation.** After an ingestion batch is committed, the enabled threshold rules of its metrics are evaluated (cached for 5 seconds, at most 20 per metric and 200 per batch, a rule fires at most once per batch, points older than an hour or more than five minutes ahead are ignored). `POST /internal/v1/anomalies/notify` evaluates the anomaly rules of the announced anomalies.
- **Cooldown and budget.** A match claims `alert:cooldown:{ruleId}` in Redis (`SET NX PX`, the rule's `cooldownSeconds`, 60 to 86400) and a per-tenant budget (`ALERT_EVENTS_PER_PERIOD`, 600 per hour). Both fail closed: with Redis down no event is created.
- **Outbox.** A firing inserts an `alert_event` row (`PENDING`; the unique key `(rule_id, source_ref)` makes a replay a no-op). A poller in every instance (`ALERT_DISPATCH_INTERVAL`, 5 s) claims due rows with `FOR UPDATE SKIP LOCKED` and a 60 second lease and delivers them on a small pool. A failure is retried after 10 s, 60 s and 300 s (4 attempts in all, `ALERT_DISPATCH_MAX_ATTEMPTS`), then the event is `FAILED`; success is `SENT`. Delivery is at least once.
- **Webhook target guard.** `https` only, a DNS name (no IP literal, no credentials, no `localhost`-style name), port from `ALERT_WEBHOOK_ALLOWED_PORTS`; the name is resolved once, every address must be public, and the connection is pinned to those addresses. The check runs on save and again before each delivery; redirects are not followed and the response body is never read.
- **Signature.** `X-Pulseboard-Event-Id`, `X-Pulseboard-Timestamp` and `X-Pulseboard-Signature` (lower-case hex HMAC-SHA256 of `timestamp + "." + body`, keyed with `ALERT_WEBHOOK_SIGNING_SECRET`, at least 32 characters). With an empty secret the `WEBHOOK` channel is off and rules for it are refused; Compose makes the secret mandatory.
- **Unsafe switches.** `ALERT_WEBHOOK_ALLOW_HTTP` and `ALERT_WEBHOOK_ALLOW_PRIVATE_TARGETS` exist for tests and local development; `AlertSafetyCheck` stops the application when either is on outside the `test` and `local` profiles.
- **Metrics.** Counters `pulseboard.alerts.events.created`, `.events.suppressed`, `.deliveries`, `.evaluation.errors` and `.email` are exposed on the management port (Prometheus names such as `pulseboard_alerts_deliveries_total`).

The decision record is [ADR 0005](../../docs/ADR/0005-webhook-ssrf-and-outbox.md); the flow is in [docs/ARCHITECTURE.md](../../docs/ARCHITECTURE.md#43-alerting) and the accepted limits in [docs/KNOWN_LIMITATIONS.md](../../docs/KNOWN_LIMITATIONS.md).

## Security model

- **Tenancy:** a `TenantContext` is resolved from the JWT or the API key on every request; the tenant never comes from the request body.
- **Passwords:** argon2id. Login attempts are limited per tenant slug and email before any hashing happens.
- **Tokens:** HS256 access tokens (15 minutes). Refresh tokens (7 days) rotate on each use inside a family; reuse of a rotated token revokes the family, and a family ends at an absolute lifetime (`REFRESH_FAMILY_TTL`, default 30 days).
- **API keys:** stored as SHA-256 hashes with an 8 character visible prefix. A successful lookup is cached in Redis (`API_KEY_CACHE_TTL`, 60 s); revocation writes a marker that replaces the cached result at once.
- **Rate limits** (Redis token buckets, defaults in the next section) cover authentication, ingestion, metric queries, API-key requests and failed API-key attempts. With Redis unavailable the limiter fails closed (503 for API-key requests).
- **Client address:** `X-Forwarded-For` is honoured only from `TRUSTED_PROXIES` (private Docker ranges by default).
- **Internal endpoint:** HMAC-SHA256 over the raw body in `X-Internal-Signature`, constant-time comparison, body limit enforced before hashing, separate security chain. The handler ignores tenant, metric and severity from the body and re-reads each announced id from the database.
- **Stream:** one-time tickets (32 random bytes, hash only in Redis, consumed atomically); caps of 10 streams per tenant and 500 per instance; 30 minute maximum lifetime; `ticket=` and `access_token=` are masked in all log messages.
- **Operations:** actuator (`health`, `info`, `prometheus`) runs on a separate management port (8081) that is not published outside the Docker network. Stack traces and exception messages are never returned to clients.

## Database migrations

Flyway, `src/main/resources/db/migration`. `spring.jpa.hibernate.ddl-auto` is `validate`.

| Migration | Content |
|---|---|
| `V1__tenancy.sql` | Tenants |
| `V2__identity.sql` | Users, refresh tokens (families), API keys |
| `V3__services_and_metrics.sql` | Services, metric definitions, monthly-partitioned `metric_point` |
| `V4__grants.sql` | Table privileges for the `analytics_reader` and `analytics_writer` roles |
| `V5__anomalies.sql` | `anomaly` and `detector_state` with least-privilege grants |
| `V6__refresh_family_lifetime.sql` | Absolute session lifetime (`family_expires_at`) |
| `V7__refresh_family_expiry_uniform.sql` | One expiry per refresh-token family |
| `V8__alert_rules.sql` | `alert_rule` and the `alert_event` outbox with their indexes |

Monthly `metric_point` partitions are created ahead of time (`METRIC_PARTITIONS_MONTHS_AHEAD`, default 3) by a daily job that also runs at startup. The database roles are created by `infra/postgres/init`, not by Flyway.

## Configuration

Defaults are in `src/main/resources/application.yml`; the Compose stack passes the secrets from `.env`.

| Property (environment variable) | Default | Meaning |
|---|---|---|
| `JWT_SECRET` | none, required | HS256 signing key, at least 32 bytes |
| `INTERNAL_SHARED_SECRET` | none, required | HMAC key shared with analytics, at least 32 characters |
| `ALERT_WEBHOOK_SIGNING_SECRET` | none, required by Compose | Signs alert webhooks, at least 32 characters; empty switches the WEBHOOK channel off |
| `ALERT_WEBHOOK_ALLOWED_PORTS` | `443` | Ports a webhook URL may use |
| `ALERT_WEBHOOK_CONNECT_TIMEOUT`, `ALERT_WEBHOOK_READ_TIMEOUT`, `ALERT_DELIVERY_TIMEOUT` | `5s`, `5s`, `20s` | Webhook timeouts and the hard limit of one delivery |
| `ALERT_MAX_RULES_PER_TENANT`, `ALERT_MAX_RULES_PER_METRIC`, `ALERT_MAX_RULES_PER_BATCH` | `50`, `20`, `200` | Rules a tenant may own; rules evaluated per metric and per ingestion batch |
| `ALERT_RULE_CACHE_TTL`, `ALERT_MAX_POINT_AGE`, `ALERT_MAX_POINT_FUTURE` | `5s`, `1h`, `5m` | Rule cache lifetime; window around now in which a point can fire a rule |
| `ALERT_EVENTS_PER_PERIOD`, `ALERT_EVENTS_PERIOD` | `600`, `1h` | Alert events one tenant may create; the excess is dropped and counted |
| `ALERT_DISPATCHER_ENABLED`, `ALERT_DISPATCH_INTERVAL`, `ALERT_DISPATCH_BATCH_SIZE`, `ALERT_DISPATCH_CONCURRENCY` | `true`, `5s`, `10`, `4` | Outbox poller |
| `ALERT_DISPATCH_LEASE`, `ALERT_DISPATCH_MAX_ATTEMPTS`, `ALERT_DISPATCH_BACKOFF` | `60s`, `4`, `10s,60s,300s` | Claim lease, attempts per event, waits between attempts |
| `ALERT_HOST_FAILURE_THRESHOLD`, `ALERT_HOST_PAUSE` | `5`, `60s` | Consecutive failures to one receiver before its deliveries pause, and for how long |
| `REFRESH_FAMILY_TTL` | `30d` | Absolute session lifetime; not shorter than the 7 day refresh token |
| `AUTH_RATE_LIMIT_CLIENT_CAPACITY` / `_PERIOD` | `30` / `1m` | Auth requests per client address |
| `AUTH_RATE_LIMIT_ACCOUNT_CAPACITY` / `_PERIOD` | `10` / `15m` | Login attempts per tenant slug and email |
| `METRIC_QUERY_RATE_LIMIT_CAPACITY` | `120` per minute | Metric queries per tenant |
| `INGEST_RATE_LIMIT_FREE_CAPACITY`, `INGEST_RATE_LIMIT_PRO_CAPACITY` | `60`, `600` per minute | Ingest batches per tenant by plan |
| `INGEST_MAX_BODY_BYTES` | `524288` | Raw ingest body limit |
| `API_KEY_RATE_LIMIT_CLIENT_CAPACITY`, `API_KEY_RATE_LIMIT_INVALID_CAPACITY` | `1200`, `20` per minute | API-key requests and failed attempts per client address |
| `API_KEY_CACHE_TTL` | `60s` | Redis cache of verified API keys |
| `METRIC_QUERY_STATEMENT_TIMEOUT` | `5s` | Per-query `statement_timeout`; exceeding it answers 503 |
| `ANOMALY_STREAM_MAX_PER_TENANT`, `ANOMALY_STREAM_MAX_TOTAL` | `10`, `500` | Concurrent SSE streams |
| `ANOMALY_STREAM_HEARTBEAT`, `ANOMALY_STREAM_MAX_LIFETIME` | `30s`, `30m` | Keep-alive interval and maximum stream age |
| `STREAM_TICKET_TTL`, `STREAM_TICKET_ISSUE_CAPACITY` | `30s`, `30` per minute | One-time stream tickets |
| `TRUSTED_PROXIES` | private Docker ranges | Regex of peers allowed to set `X-Forwarded-For` |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_MAX_SIZE` | `localhost`, `5432`, `pulseboard`, `api_core`, empty, `10` | PostgreSQL connection |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | `localhost`, `6379`, empty | Redis connection |
| `SERVER_PORT`, `MANAGEMENT_PORT` | `8080`, `8081` | Application and actuator ports |

## Running tests and gates

Tests use Testcontainers (PostgreSQL and Redis), so Docker must be running.

```sh
cd apps/api-core
./gradlew test                                                  # Windows: gradlew.bat test
./gradlew checkstyleMain checkstyleTest test verifyOpenApiDocs  # the full gate
```

| Task | Purpose |
|---|---|
| `checkstyleMain`, `checkstyleTest` | Style rules, zero warnings allowed |
| `test` | Unit, integration, architecture and attack-scenario tests, including the alert rules, outbox delivery and webhook target checks |
| `generateOpenApiDocs` | Rewrites `contracts/api-core.openapi.yaml` from the code (`make openapi-gen`) |
| `verifyOpenApiDocs` | Fails when the checked-in contract differs from the code |

From the repository root, `make test-api` runs the tests and `make lint` runs checkstyle.

The Docker image is built from `Dockerfile`; the stack is started from the repository root with `make up` (see the [root README](../../README.md)).
