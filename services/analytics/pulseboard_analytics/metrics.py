"""Prometheus metrics of the service.

One process-wide registry. The service runs as a single process by design (``uvicorn`` with one
worker, the scheduler in threads of that process), so the default in-memory client is correct;
running several worker processes would make every scrape see only one of them, and would need
``prometheus_client``'s multiprocess mode instead. All instruments are thread-safe.

Labels are bounded sets of fixed strings (outcomes, detector and severity enums, HTTP status
codes of one endpoint). Metric ids, tenant ids, paths and error messages are never labels.

The registry is served only by :class:`MetricsServer`, a separate listener that the main
application never mounts; it carries no secrets.
"""

import threading
from wsgiref.simple_server import WSGIServer

from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram, start_http_server

from pulseboard_analytics import __version__

PREFIX = "pulseboard_analytics_"

REGISTRY = CollectorRegistry(auto_describe=True)

RUN_RESULTS = ("completed", "skipped", "lost_lease", "deadline", "error")
FAILURE_REASONS = ("budget", "busy", "error", "all_detectors_failed")
NOTIFY_OUTCOMES = ("ok", "retry", "failed")
TRAINING_RESULTS = ("won", "lost", "local", "error")
LOCK_RESULTS = ("acquired", "busy")
POOL_TIMEOUT_REASONS = ("pool_busy", "budget")

_RUN_BUCKETS = (0.1, 0.5, 1, 2.5, 5, 10, 20, 30, 45, 60, 120)
_NOTIFY_BUCKETS = (0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30)

score_runs = Counter(
    PREFIX + "score_runs_total",
    "Scheduled scoring runs by result.",
    ["result"],
    registry=REGISTRY,
)
score_run_duration = Histogram(
    PREFIX + "score_run_duration_seconds",
    "Duration of scoring runs that took the lock.",
    buckets=_RUN_BUCKETS,
    registry=REGISTRY,
)
metrics_scored = Counter(
    PREFIX + "metrics_scored_total",
    "Metrics scored by scheduled runs.",
    registry=REGISTRY,
)
metrics_failed = Counter(
    PREFIX + "metrics_failed_total",
    "Metrics that failed in scheduled runs, by reason.",
    ["reason"],
    registry=REGISTRY,
)
unscored_metrics = Gauge(
    PREFIX + "unscored_metrics",
    "Metrics left unscored by the last scheduled run that took the lock.",
    registry=REGISTRY,
)
slow_metrics = Gauge(
    PREFIX + "slow_metrics",
    "Metrics currently backing off after exhausting their time budget.",
    registry=REGISTRY,
)
anomalies_inserted = Counter(
    PREFIX + "anomalies_inserted_total",
    "Newly stored anomalies.",
    ["detector", "severity"],
    registry=REGISTRY,
)
notify_requests = Counter(
    PREFIX + "notify_requests_total",
    "Notification attempts to api-core: ok, retry (will be retried) or failed (given up).",
    ["outcome"],
    registry=REGISTRY,
)
notify_duration = Histogram(
    PREFIX + "notify_duration_seconds",
    "Duration of one notification call, retries included.",
    buckets=_NOTIFY_BUCKETS,
    registry=REGISTRY,
)
forest_cache_entries = Gauge(
    PREFIX + "forest_cache_entries",
    "Fitted isolation forests held in memory.",
    registry=REGISTRY,
)
model_trainings = Counter(
    PREFIX + "model_trainings_total",
    "Isolation forest fits: won (stored), lost (another writer won), local (refit, no write), "
    "error.",
    ["result"],
    registry=REGISTRY,
)
db_pool_wait = Counter(
    PREFIX + "db_pool_wait_seconds_total",
    "Total time spent waiting for a database connection.",
    registry=REGISTRY,
)
db_pool_timeouts = Counter(
    PREFIX + "db_pool_timeouts_total",
    "Connection checkouts that gave up waiting.",
    ["reason"],
    registry=REGISTRY,
)
lock_acquire = Counter(
    PREFIX + "lock_acquire_total",
    "Attempts to take the scoring lock.",
    ["result"],
    registry=REGISTRY,
)
score_requests = Counter(
    PREFIX + "score_requests_total",
    "Requests to the score endpoint by HTTP status.",
    ["status"],
    registry=REGISTRY,
)
build_info = Gauge(
    PREFIX + "build_info",
    "Constant 1; the version label carries the service version.",
    ["version"],
    registry=REGISTRY,
)
build_info.labels(version=__version__).set(1)

# Pre-create the bounded series so they are exported (as 0) before the first event.
for _result in RUN_RESULTS:
    score_runs.labels(result=_result)
for _reason in FAILURE_REASONS:
    metrics_failed.labels(reason=_reason)
for _outcome in NOTIFY_OUTCOMES:
    notify_requests.labels(outcome=_outcome)
for _training in TRAINING_RESULTS:
    model_trainings.labels(result=_training)
for _lock_result in LOCK_RESULTS:
    lock_acquire.labels(result=_lock_result)
for _pool_reason in POOL_TIMEOUT_REASONS:
    db_pool_timeouts.labels(reason=_pool_reason)


class MetricsServer:
    """The metrics listener: a plain HTTP server on its own port, separate from the main app."""

    def __init__(self, port: int, addr: str = "0.0.0.0") -> None:
        server, thread = start_http_server(port, addr, REGISTRY)
        self._server: WSGIServer = server
        self._thread: threading.Thread = thread

    @property
    def port(self) -> int:
        """The bound port (useful when started with port 0)."""
        return int(self._server.server_port)

    def close(self) -> None:
        """Stop serving and release the port."""
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=5)
