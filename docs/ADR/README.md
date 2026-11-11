# Architecture decision records

Each record states the context, the decision, its consequences, the alternatives that were weighed and where the decision lives in the code. Records are numbered in the order they were written and are not renumbered.

| Record | Decision |
|---|---|
| [0001-monorepo.md](0001-monorepo.md) | One repository for the three components, the contracts and the infrastructure |
| [0002-postgres-partitioning.md](0002-postgres-partitioning.md) | `metric_point` is range-partitioned by month in plain PostgreSQL, with no default partition |
| [0003-analytics-role-separation.md](0003-analytics-role-separation.md) | `analytics` uses two least-privilege database roles, verified at startup, and never the owner role |
| [0004-sse-over-websocket.md](0004-sse-over-websocket.md) | The live anomaly feed is server-sent events with one-time tickets and Redis fan-out |
| [0005-webhook-ssrf-and-outbox.md](0005-webhook-ssrf-and-outbox.md) | Alert webhooks are delivered from a database outbox through an SSRF-hardened, signed client |

The decisions were taken while the phases P0 to P5 were built; the records were written afterwards (2026-11-11) from the code as it stands. The date in each record is the day the decision first appeared in the repository. The alternatives sections give the reasons that speak against each option for this project; they are not minutes of a discussion.

Related: [../ARCHITECTURE.md](../ARCHITECTURE.md), [../KNOWN_LIMITATIONS.md](../KNOWN_LIMITATIONS.md), [../SECURITY_CHECKLIST.md](../SECURITY_CHECKLIST.md).
