# 0004. Server-sent events for the live anomaly feed

- **Status:** Accepted
- **Date:** 2026-11-10 (recorded 2026-11-11)

## Context

The dashboard shows new anomalies as they are stored. Traffic is one way (server to browser), the volume is low (anomalies, not metric points), and the feed is per tenant. `api-core` may run as several instances, while the anomaly notification from `analytics` reaches exactly one of them. Browser authentication is a bearer access token; a browser `EventSource` cannot set an `Authorization` header.

## Decision

Use server-sent events at `GET /api/v1/stream/anomalies`.

- **Authentication by one-time ticket.** The client calls `POST /api/v1/stream/ticket` with its bearer token (30 per minute per user) and opens the stream with `?ticket=...`. A ticket is 32 random bytes, valid for 30 seconds (`STREAM_TICKET_TTL`, never beyond the access token's remaining life), and consumed atomically with `GETDEL` by the first request that presents it, also when a stream cap then refuses that request. Redis stores only the SHA-256 of the ticket together with tenant, user and token expiry. Every failure gets the same 401. Access tokens in the query string are not accepted. A bearer header is accepted too, for non-browser clients.
- **Fan-out through Redis pub/sub.** After `POST /internal/v1/anomalies/notify`, `api-core` re-reads the announced anomalies from the database and publishes them to the exact channel `anomalies:{tenantId}`. Each instance subscribes to a tenant's channel when its first stream opens and unsubscribes when the last one closes, so any instance can serve any stream. A message whose tenant differs from its channel's is dropped.
- **Ordering.** The Redis listener container hands messages to a single-threaded dispatcher (`anomaly-dispatch`), so the messages of a channel reach listeners in publish order. Listeners only enqueue; each stream owns a bounded queue (256) and one virtual thread that writes to the client. A client that cannot keep up overflows its queue and is closed; it reconnects.
- **Caps and lifetime.** 10 streams per tenant (`ANOMALY_STREAM_MAX_PER_TENANT`) and 500 per instance (`ANOMALY_STREAM_MAX_TOTAL`), 429 above that. A `: ping` comment every 30 seconds detects dead clients. A stream ends after 30 minutes or when its access token expires, whichever is first.
- **No replay.** Events carry the anomaly id as the SSE event id, but missed events are not replayed; after reconnecting, the client reads `GET /api/v1/anomalies`.
- **Proxy.** `apps/web/nginx.conf` disables buffering and compression for the route, allows one-hour idle connections and keeps the query string out of its access log.

## Consequences

Positive:

- Plain HTTP: it passes the same security chain, rate limits and proxy as the REST API, and `EventSource` reconnection is built into the browser (the client closes the source on error and schedules its own backoff, because a ticket cannot be reused).
- The ticket never grants more than one stream of one tenant, and the access token stays out of URLs and logs.
- No sticky sessions; instances share nothing but Redis.

Negative:

- A ticket is a credential in a URL for up to 30 seconds. Logs and proxies in front of `api-core` must not record the query string; `api-core` masks `ticket=` and `access_token=` in its own messages and nginx logs `$uri` (see [../KNOWN_LIMITATIONS.md](../KNOWN_LIMITATIONS.md)).
- The feed is lossy by design (pub/sub has no persistence, no replay, overflowing clients are cut); the REST list is the source of truth.
- Every connection needs a ticket round trip and a fresh ticket per reconnect.
- Redis unavailability means 503 for new streams and for ticket issuing.

## Alternatives considered

- **WebSocket.** Bidirectional and not needed here. It has the same header problem in browsers (a handshake cannot carry `Authorization`), so it would need a ticket or a sub-protocol token as well, plus its own framing, ping handling and proxy configuration.
- **Polling the REST list.** Simplest and a fine fallback, but adds latency and request volume per open tab.
- **Long polling.** More moving parts than SSE for the same one-way flow.
- **Access token in the query string.** A long-lived credential would sit in URLs and logs; rejected and tested (`anAccessTokenInTheQueryStringIsNotAccepted`).
- **Cookie authentication for the stream.** Would introduce ambient credentials and a CSRF surface into an API that is otherwise header-authenticated.
- **A fetch-based SSE client that sends the header.** Possible, but replaces the browser's built-in event-stream parser and reconnect handling with custom code, only to avoid a short-lived ticket.

## References

`apps/api-core/src/main/java/io/pulseboard/core/api/stream/` (`AnomalyStreamController`, `AnomalyStreams`, `StreamTicketController`), `infrastructure/stream/` (`StreamConfiguration`, `RedisAnomalyEventSource`, `RedisAnomalyPublisher`, `RedisStreamTicketStore`), `infrastructure/security/StreamTicketAuthenticationFilter.java`, `application/anomaly/StreamAdmission.java`, `apps/web/src/app/features/anomalies/anomaly-stream.service.ts`, `apps/web/nginx.conf`, tests `StreamTicketIntegrationTest`, `AnomalyStreamIntegrationTest`, `AnomalyStreamLimitsIntegrationTest`.
