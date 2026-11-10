# Known limitations

Short list of behaviours that are deliberate or accepted for now.

- **Trusted proxy ranges.** `api-core` trusts `X-Forwarded-For` from any peer in the private ranges (10/8, 172.16/12, 192.168/16) by default. A client that reaches `api-core` directly from such a network can choose its own address for per-client rate limits. Narrow `TRUSTED_PROXIES` to the proxy address when `api-core` is reachable from a private network other than the Compose one.
- **Shared client bucket behind NAT.** Per-client limits (auth, API key requests) are keyed by address, so clients behind one NAT or one proxy address share a bucket.
- **Redis outage.** Requests authenticated with an API key (ingestion) are answered with 503 while Redis is unavailable, because the rate limiter fails closed.
- **Isolation Forest false positives.** On clean seasonal data the min-max normalised score with the fixed 0.65 threshold flags roughly 7 percent of points. The z-score detector does not have this issue; the threshold is not tuned per metric. Accepted for now.
- **Model refit on restart.** Only the parameters of the Isolation Forest are stored, not the fitted model (accepted trade-off). After an `analytics` restart each model is refitted on the next run.
- **SSE is bearer-only.** `/api/v1/stream/anomalies` accepts the `Authorization` bearer header only (no query-string token, no cookie). A browser `EventSource` cannot send it, so the browser client needs a fetch based reader or a short-lived stream ticket (planned for the frontend phase).
- **Reverse proxy for SSE.** The bundled nginx configuration has no location tuned for the stream yet (see `docs/ARCHITECTURE.md`, section 4.5).
- **HMAC without a timestamp.** `X-Internal-Signature` covers the body only, so a captured request can be replayed. This is harmless for `notify` because it is idempotent and publishes only database state.
- **Single scheduler.** The scoring scheduler runs inside the `analytics` process; run exactly one replica. The Redis lock only prevents overlapping runs, it is not a leader election; a second instance would only skip runs, not share the work.
- **One anomaly per point.** The detectors report each flagged point as its own anomaly with a window of that single point; adjacent anomalous points are not merged into one event.
- **Anomaly feed order.** `GET /api/v1/anomalies` is sorted by `window_start`, not by the time the anomaly was detected, so a late detection can appear among older entries.
- **Stream limits.** Each tenant may hold 10 live streams (`ANOMALY_STREAM_MAX_PER_TENANT`) and an instance 500; more are refused with 429. Streams end after 30 minutes or at token expiry and are not replayed.
- **End-to-end check is not part of push or pull request builds.** `.github/workflows/e2e.yml` runs `scripts/e2e/run.py` nightly and on manual dispatch only. A failure shows up the next day, not on the commit that caused it.
