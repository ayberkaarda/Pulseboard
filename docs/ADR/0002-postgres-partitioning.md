# 0002. Monthly range partitions for metric_point

- **Status:** Accepted
- **Date:** 2026-11-10 (recorded 2026-11-11)

## Context

`metric_point (metric_id, ts, value)` is the only high-volume table: ingestion upserts batches of up to 1,000 points, `analytics` reads the last 24 hours of every active metric each minute, and the dashboard reads ranges. Old data must eventually be removable without a long `DELETE`, and index size and vacuum work should stay bounded.

## Decision

Use native PostgreSQL declarative partitioning (`V3__services_and_metrics.sql`):

- `metric_point` is `PARTITION BY RANGE (ts)` with one partition per calendar month in UTC, named `metric_point_YYYY_MM`. The primary key is `(metric_id, ts)`, which contains the partition key.
- The function `ensure_metric_partitions(months_ahead int)` creates the partitions of the current month and the following `months_ahead` months when missing. It is idempotent, serialised by a transaction-scoped advisory lock, accepts 0 to 120 and has a fixed `search_path`. `EXECUTE` is revoked from `PUBLIC` (`V4__grants.sql`). The migration itself calls it with 3.
- `api-core` keeps the partitions ahead of time: `MetricPartitionMaintenance` calls the function at `ApplicationReadyEvent` and then daily at 00:15 UTC (`METRIC_PARTITIONS_CRON`). `METRIC_PARTITIONS_MONTHS_AHEAD` defaults to 3 and must be at least 1.
- There is deliberately **no default partition**. A default partition would silently collect points whose month has no partition, and creating that partition later would then fail or require moving rows. Instead `JdbcMetricSampleRepository` reads the partition bounds in the ingestion transaction and rejects only the points outside them (`OUTSIDE_STORAGE_RANGE`); a row outside every partition would otherwise abort the whole batch.
- `analytics_reader` holds `SELECT` on the parent only. Privileges are checked on the parent, so new partitions need no grant (see [0003](0003-analytics-role-separation.md)).

## Consequences

Positive:

- Retention becomes a metadata operation: a partition can be detached or dropped without touching the others, and queries bounded by `ts` prune to the partitions they need.
- No extension is required; the stock `postgres:18` image is enough.
- Partition creation is tested: `SchemaAndPartitionIntegrationTest` covers creation ahead of time, idempotency, rejection of a negative horizon and routing of points to their month.

Negative:

- **Nothing deletes old data yet.** No retention job exists, so partitions grow without bound. The partitioning makes one possible; it is not implemented.
- If the daily job and the startup call both fail for longer than the horizon (3 months), points for the missing months are rejected until a partition exists.
- Every query should carry a `ts` range; one without it scans all partitions.
- The foreign key from `metric_point` to `metric_definition` is `ON DELETE CASCADE`, so deleting a metric deletes its points across all partitions.

## Alternatives considered

- **TimescaleDB.** Brings hypertables, compression and continuous aggregates, none of which the project needs at its scale, but also a non-standard image and an extension to build, upgrade and secure. Plain partitioning keeps the dependency list to PostgreSQL.
- **A default partition.** Rejected, see above.
- **Partitioning by day or by tenant.** Daily partitions would multiply the partition count by about 30 for no gain at this volume; partitioning by tenant cannot be created ahead of time without knowing the tenants.
- **A single unpartitioned table with periodic deletes.** Large deletes bloat the table and the primary-key index and need vacuum to reclaim the space.

## References

`apps/api-core/src/main/resources/db/migration/V3__services_and_metrics.sql`, `V4__grants.sql`, `apps/api-core/src/main/java/io/pulseboard/core/infrastructure/persistence/MetricPartitionMaintenance.java`, `JdbcMetricSampleRepository.java`, `apps/api-core/src/main/resources/application.yml` (`pulseboard.partitions`), `SchemaAndPartitionIntegrationTest`.
