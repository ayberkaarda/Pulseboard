# 0005. Alert delivery through an outbox and an SSRF-hardened webhook client

- **Status:** Accepted
- **Date:** 2026-11-11

## Context

An alert rule lets a tenant user enter a URL that the server will call whenever the rule fires. That is a server-side request forgery (SSRF) primitive: the caller chooses the destination, and `api-core` sits next to PostgreSQL, Redis, `analytics` and, in a cloud deployment, a metadata service. Receivers are slow, down or hostile in practice, and rules fire from the ingestion hot path, which must not wait for them. The webhook receiver also needs a way to tell a genuine delivery from a forged one.

## Decision

**Outbox.** Firing a rule inserts an `alert_event` row with `delivery_status = PENDING` (`V8__alert_rules.sql`); the insert is the only thing done on the hot path. A unique key on `(rule_id, source_ref)` makes a replayed trigger a no-op. The cooldown (`SET NX PX` in Redis) and a per-tenant event budget are claimed before the insert and fail closed when Redis is down.

**Dispatcher.** Every `api-core` instance polls every 5 seconds (`AlertDispatcherRunner`, `AlertDispatchService`). `JdbcAlertOutbox.claimDue` selects due rows with `FOR UPDATE SKIP LOCKED` inside one `UPDATE ... RETURNING` statement, so instances take disjoint rows without waiting, and sets a 60 second lease (`claimed_until`); a crashed worker's claim simply runs out and is taken over. Deliveries run on a small fixed pool (4). Failures are retried after 10 s, 60 s and 300 s, then the event becomes `FAILED`. Delivery is therefore at least once; receivers deduplicate on `X-Pulseboard-Event-Id`.

**Target validation**, repeated for every delivery because DNS answers change (`WebhookTargetGuard`, `AddressClassifier`):

- scheme `https` only, at most 2048 ASCII characters, no credentials, port from `ALERT_WEBHOOK_ALLOWED_PORTS` (default 443);
- the host must be a DNS name: every IP-literal spelling, `localhost`-style and single-label names are refused;
- the name is resolved once and every answer must be a public unicast address (loopback, RFC 1918, link-local including the metadata address, carrier-grade NAT, multicast, reserved and transition ranges are refused, IPv4-mapped IPv6 judged by the embedded address); one blocked answer rejects the target.

**Client** (`ApacheWebhookSender`): each request gets its own HTTP client whose DNS resolver can only answer with the validated addresses (DNS pinning), while the URL host remains the TLS server name and `Host` header so certificate validation still applies. Redirects, retries, cookies, compression and proxies are off; a 3xx fails the event without following it. The response body is not read; the connection is closed once the status is known.

**Signature.** Each request carries `X-Pulseboard-Event-Id`, `X-Pulseboard-Timestamp` (Unix seconds) and `X-Pulseboard-Signature`, the lower-case hex HMAC-SHA256 of `timestamp + "." + raw body` keyed with `ALERT_WEBHOOK_SIGNING_SECRET` (at least 32 characters). Without the secret the `WEBHOOK` channel is off. Binding the timestamp into the MAC lets a receiver reject replays; see [../ARCHITECTURE.md](../ARCHITECTURE.md) for the verification recipe.

**Circuit breaker.** After 5 consecutive failures to one receiver (host and port), its deliveries are postponed for 60 seconds so a dead receiver cannot occupy the delivery pool. It is per instance and in memory.

**Test-only switches.** `ALERT_WEBHOOK_ALLOW_HTTP` and `ALERT_WEBHOOK_ALLOW_PRIVATE_TARGETS` exist for tests and local development. `AlertSafetyCheck` refuses to start the application with either outside the `test` and `local` profiles, and Compose does not pass them on.

## Consequences

Positive:

- A slow or dead receiver never delays ingestion; the work survives restarts and is shared between instances.
- A tenant cannot aim the server at internal addresses, even through DNS rebinding between check and connect, redirects or IPv6 spellings; metadata and Compose service names are unreachable on purpose.
- Receivers can authenticate deliveries and reject replays.

Negative:

- At-least-once delivery: duplicates are possible after a crash or a lost response.
- Evaluation happens after the data is committed and is not itself transactional, so a crash in between loses that alert; only delivery is an outbox.
- Legitimate receivers on private networks cannot be used. A single signing secret serves all tenants, with no rotation overlap.
- The breaker and the rule cache are per instance and reset on restart; polling adds up to 5 seconds of latency and constant light database load; nothing cleans old `alert_event` rows.

Details are listed in [../KNOWN_LIMITATIONS.md](../KNOWN_LIMITATIONS.md).

## Alternatives considered

- **Delivering inline in the ingestion request.** Couples ingestion latency and availability to arbitrary third parties.
- **An in-memory queue or a Redis list.** Loses events on restart or needs its own persistence and claim protocol; the table already provides ordering, leases and tenant-scoped history to the UI.
- **A message broker.** One more component to run for a modest event volume.
- **Resolve-then-connect by URL host name** (validate, then let the HTTP client resolve again). Leaves a rebinding window; pinning removes it.
- **Deny-listing on the URL string only.** Misses decimal, octal, hexadecimal and IPv6 spellings and DNS names that resolve to internal addresses.
- **An allow list of receiver domains.** Not workable for tenant-chosen endpoints.
- **An outbound proxy or egress firewall.** The stronger control in a real deployment and complementary; the Compose stack has none (see [../SECURITY_CHECKLIST.md](../SECURITY_CHECKLIST.md)).

## References

`apps/api-core/src/main/java/io/pulseboard/core/application/alert/` (`AlertDispatchService`, `AlertDeliveryService`, `HostCircuitBreaker`, `webhook/WebhookTargetGuard`, `webhook/AddressClassifier`, `webhook/WebhookSigner`), `infrastructure/alert/` (`ApacheWebhookSender`, `JdbcAlertOutbox`, `RedisAlertCooldown`, `AlertSafetyCheck`), `V8__alert_rules.sql`, tests `WebhookTargetGuardTest`, `ApacheWebhookSenderTest`, `AlertDeliveryIntegrationTest`, `AlertPollerIntegrationTest`, `AlertResilienceIntegrationTest`, `AlertPropertiesTest`.
