"""HMAC-signed client for the api-core anomaly notification endpoint."""

import json
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass

import httpx2
import structlog

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
        self._secret = secret
        self._timeout = timeout
        self._sleep = sleep
        self._http = httpx2.Client(base_url=base_url, timeout=timeout, transport=transport)

    def close(self) -> None:
        """Release the underlying connection pool."""
        self._http.close()

    def notify(
        self, anomalies: Sequence[StoredAnomaly], budget: Budget | None = None
    ) -> NotifyResult:
        """Send ``anomalies`` in batches of at most 500; a failed batch is logged, not raised.

        With a ``budget`` the whole call, retries and backoff sleeps included, never takes longer
        than the time left in it: every request gets at most that time as its timeout, and no
        attempt starts when it is spent. A failed batch does not stop the following ones.
        """
        delivered = 0
        failed = 0
        for start in range(0, len(anomalies), MAX_BATCH):
            batch = anomalies[start : start + MAX_BATCH]
            if self._send(_payload(batch), len(batch), budget):
                delivered += len(batch)
            else:
                failed += len(batch)
        return NotifyResult(delivered=delivered, failed=failed)

    def _send(self, body: bytes, count: int, budget: Budget | None) -> bool:
        headers = {"Content-Type": "application/json", SIGNATURE_HEADER: sign(self._secret, body)}
        for attempt in range(1, MAX_ATTEMPTS + 1):
            outcome: str
            timeout = self._timeout
            if budget is not None:
                left = budget.remaining_seconds()
                if left <= 0.0:
                    _log.warning("notify_budget_spent", attempts=attempt - 1, count=count)
                    return False
                timeout = min(timeout, left)
            try:
                response = self._http.post(
                    NOTIFY_PATH, content=body, headers=headers, timeout=timeout
                )
            except httpx2.HTTPError as error:
                outcome = type(error).__name__
            else:
                if response.is_success:
                    return True
                if not _is_retryable(response.status_code):
                    _log.warning("notify_rejected", status=response.status_code, count=count)
                    return False
                outcome = f"status {response.status_code}"
            if attempt == MAX_ATTEMPTS:
                _log.error("notify_failed", reason=outcome, attempts=attempt, count=count)
                return False
            delay = BASE_DELAY_SECONDS * 2 ** (attempt - 1)
            if budget is not None and budget.remaining_seconds() <= delay:
                _log.warning("notify_budget_spent", attempts=attempt, count=count)
                return False
            _log.warning("notify_retry", reason=outcome, attempt=attempt)
            self._sleep(delay)
        return False
