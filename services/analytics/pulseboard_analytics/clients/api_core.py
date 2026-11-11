"""HMAC-signed client for the api-core anomaly notification endpoint."""

import json
import threading
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass

import httpx2
import structlog

from pulseboard_analytics import metrics
from pulseboard_analytics.budget import Budget
from pulseboard_analytics.repositories.models import StoredAnomaly
from pulseboard_analytics.security import SIGNATURE_HEADER, sign

NOTIFY_PATH = "/internal/v1/anomalies/notify"
MAX_BATCH = 500
MAX_ATTEMPTS = 3
BASE_DELAY_SECONDS = 0.5

_log = structlog.get_logger(__name__)


@dataclass(frozen=True)
class NotifyResult:
    """How many anomalies api-core accepted and how many could not be delivered."""

    delivered: int
    failed: int


def _is_retryable(status: int) -> bool:
    return status == 429 or status >= 500


def _payload(anomalies: Sequence[StoredAnomaly]) -> bytes:
    document = {
        "anomalies": [
            {
                "id": str(item.id),
                "tenantId": str(item.tenant_id),
                "metricId": str(item.metric_id),
                "windowStart": item.window_start.isoformat(),
                "windowEnd": item.window_end.isoformat(),
                "score": item.score,
                "detector": item.detector,
                "severity": item.severity,
                "createdAt": item.created_at.isoformat(),
            }
            for item in anomalies
        ]
    }
    return json.dumps(document, separators=(",", ":")).encode("utf-8")


class ApiCoreClient:
    """Posts newly stored anomalies to api-core; delivery failures never raise."""

    def __init__(
        self,
        base_url: str,
        secret: str,
        *,
        timeout: float = 5.0,
        sleep: Callable[[float], None] = time.sleep,
        transport: httpx2.BaseTransport | None = None,
    ) -> None:
        self._base_url = base_url
        self._secret = secret
        self._timeout = timeout
        self._sleep = sleep
        self._transport = transport

    def close(self) -> None:
        """Release resources; each call owns its own connection pool, so nothing is held."""

    def notify(
        self, anomalies: Sequence[StoredAnomaly], budget: Budget | None = None
    ) -> NotifyResult:
        """Send ``anomalies`` in batches of at most 500; a failed batch is logged, not raised.

        With a ``budget`` the whole call, retries and backoff sleeps included, never takes longer
        than the time left in it. httpx timeouts apply per network operation, so a server that
        trickles bytes could keep one request alive for ever; a watchdog therefore closes the
        call's own client (and with it every open socket) when the budget ends, and the request
        timeout is also capped to what is left. No attempt starts when the budget is spent. A
        failed batch does not stop the following ones.
        """
        client = httpx2.Client(
            base_url=self._base_url, timeout=self._timeout, transport=self._transport
        )
        watchdog: threading.Timer | None = None
        if budget is not None:
            watchdog = threading.Timer(max(0.0, budget.remaining_seconds()), client.close)
            watchdog.daemon = True
            watchdog.start()
        delivered = 0
        failed = 0
        started = time.monotonic()
        try:
            for start in range(0, len(anomalies), MAX_BATCH):
                batch = anomalies[start : start + MAX_BATCH]
                if self._send(client, _payload(batch), len(batch), budget):
                    delivered += len(batch)
                else:
                    failed += len(batch)
        finally:
            metrics.notify_duration.observe(time.monotonic() - started)
            if watchdog is not None:
                watchdog.cancel()
            client.close()
        return NotifyResult(delivered=delivered, failed=failed)

    def _send(self, client: httpx2.Client, body: bytes, count: int, budget: Budget | None) -> bool:
        headers = {"Content-Type": "application/json", SIGNATURE_HEADER: sign(self._secret, body)}
        for attempt in range(1, MAX_ATTEMPTS + 1):
            outcome: str
            timeout = self._timeout
            if budget is not None:
                left = budget.remaining_seconds()
                if left <= 0.0:
                    metrics.notify_requests.labels(outcome="failed").inc()
                    _log.warning("notify_budget_spent", attempts=attempt - 1, count=count)
                    return False
                timeout = min(timeout, left)
            try:
                # The body is not read: the status decides, and closing the stream drops the socket.
                with client.stream(
                    "POST", NOTIFY_PATH, content=body, headers=headers, timeout=timeout
                ) as response:
                    status = response.status_code
            except (httpx2.HTTPError, RuntimeError, OSError) as error:
                outcome = type(error).__name__
            else:
                if 200 <= status < 300:
                    metrics.notify_requests.labels(outcome="ok").inc()
                    return True
                if not _is_retryable(status):
                    metrics.notify_requests.labels(outcome="failed").inc()
                    _log.warning("notify_rejected", status=status, count=count)
                    return False
                outcome = f"status {status}"
            if attempt == MAX_ATTEMPTS:
                metrics.notify_requests.labels(outcome="failed").inc()
                _log.error("notify_failed", reason=outcome, attempts=attempt, count=count)
                return False
            delay = BASE_DELAY_SECONDS * 2 ** (attempt - 1)
            if budget is not None and budget.remaining_seconds() <= delay:
                metrics.notify_requests.labels(outcome="failed").inc()
                _log.warning("notify_budget_spent", attempts=attempt, count=count)
                return False
            metrics.notify_requests.labels(outcome="retry").inc()
            _log.warning("notify_retry", reason=outcome, attempt=attempt)
            self._sleep(delay)
        return False
