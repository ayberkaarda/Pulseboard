# 0003. Separate least-privilege database roles for analytics

- **Status:** Accepted
- **Date:** 2026-11-10 (recorded 2026-11-11)

## Context

`analytics` runs statistical code over data of all tenants and writes the anomalies it finds. It is the component with the largest dependency tree (numpy, pandas, scikit-learn) and it parses internal requests. If it is compromised, or has a bug, the database privileges it holds are the limit of the damage. `api-core` owns the schema and runs Flyway, so its role is the owner of every table.

## Decision

`analytics` connects with two dedicated roles, created by `infra/postgres/init/01-pulseboard-roles-and-database.sql` (not by Flyway), each with its own connection pool and password:

| Relation | `analytics_reader` | `analytics_writer` | Set by |
|---|---|---|---|
| `metric_point` (parent; partitions inherit the check) | SELECT | none | V4 |
| `metric_definition` | SELECT | none | V5 |
| `anomaly` | none | SELECT, INSERT | V5 |
| `detector_state` | none | SELECT, INSERT, UPDATE | V5 |
| `alert_rule`, `alert_event` | none | none | V8 |
| every other table (`tenant`, `app_user`, `api_key`, `refresh_token`, `service`) | none | none | init script (default privileges revoked from `PUBLIC`) |

- Both roles are `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`, have `USAGE` but not `CREATE` on schema `public`, and `analytics_reader` additionally has `default_transaction_read_only = on`.
- `INSERT` without `UPDATE` or `DELETE` on `anomaly` means the writer can add a finding but never rewrite or remove one. `SELECT` is only there for `INSERT ... ON CONFLICT DO NOTHING RETURNING`.
- `ensure_metric_partitions` is not executable by either role (V4).
- **Startup verification.** `verify_database_roles` (`repositories/privileges.py`, called from `runtime.py` unless `VERIFY_DB_ROLES=false`, a switch for tests) compares the effective privileges of the connected user with the matrix above, including column grants, sequences, functions, other schemas and partitions, and refuses roles that own objects, are superusers or reach such a role through membership or `SET ROLE`. Every problem is listed and the service does not start.
- Detectors never use the owner role. Because the check inspects `current_user`, handing owner credentials to either DSN is itself a startup failure (`test_owner_credentials_as_reader_are_refused`, `test_owner_credentials_as_writer_are_refused`).

## Consequences

Positive:

- A SQL injection or code-execution bug in `analytics` cannot read password hashes, refresh tokens, API key hashes or alert targets, cannot alter metric data and cannot erase anomalies.
- A wrong deployment (owner DSN, swapped roles, an extra grant added by hand) fails at startup instead of running with more rights than intended.
- Each new table must state its analytics access explicitly in its migration (V5 and V8 do), which makes privilege changes visible in review.

Negative:

- The matrix lives twice: in the migrations and in `privileges.py`. Changing one without the other fails the startup check or its tests, which is the intent, but it is two edits.
- Migrations depend on the roles existing (V4 raises if they are missing), and the init script runs only on an empty data volume, so existing volumes need the roles created by hand.
- There is no row-level security: `analytics_reader` can read the points of every tenant. The separation limits what the service can do, not which tenant's data it sees; scoring needs all of them.
- Two engines per process and two sets of credentials to rotate.

## Alternatives considered

- **One role for `analytics` with `SELECT` on metrics and `INSERT` on anomalies.** Smaller, but one credential then both reads all metric data and writes findings, so read-only access to metrics could no longer be guaranteed.
- **Reusing the `api-core` owner role.** Simplest, and the worst blast radius: schema changes, identity data and alert targets would all be reachable.
- **Having `analytics` write through `api-core` over HTTP.** Removes the writer role but puts bulk inserts and idempotency behind an extra hop; the existing internal call is already used only for notification.
- **Row-level security per tenant.** Would not help here, since the detectors must score all tenants in one process.

## References

`infra/postgres/init/01-pulseboard-roles-and-database.sql`, `apps/api-core/src/main/resources/db/migration/V4__grants.sql`, `V5__anomalies.sql`, `V8__alert_rules.sql`, `services/analytics/pulseboard_analytics/repositories/privileges.py`, `runtime.py`, `services/analytics/tests/test_db_roles.py`, `test_db_roles_matrix.py`, `SchemaAndPartitionIntegrationTest` (`analyticsReaderCanOnlyReadMetricPoints`).
