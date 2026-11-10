"""Notify client against a local fake api-core."""

import json
import socket
from datetime import UTC, datetime
from uuid import uuid4

from pulseboard_analytics.clients.api_core import MAX_BATCH, NOTIFY_PATH, ApiCoreClient
from pulseboard_analytics.repositories.models import StoredAnomaly
from pulseboard_analytics.security import SIGNATURE_HEADER, sign
from tests.support.fake_server import fake_api_core

SECRET = "n" * 40


def _anomaly() -> StoredAnomaly:
    moment = datetime(2026, 6, 1, 11, 0, tzinfo=UTC)
    return StoredAnomaly(
        id=uuid4(),
        tenant_id=uuid4(),
        metric_id=uuid4(),
        window_start=moment,
        window_end=moment,
        score=0.91,
        detector="ZSCORE",
        severity="HIGH",
        created_at=moment,
    )


def _client(url: str, sleeps: list[float] | None = None) -> ApiCoreClient:
    recorded = sleeps if sleeps is not None else []
    return ApiCoreClient(url, SECRET, sleep=recorded.append, timeout=2.0)


def test_signature_covers_the_exact_bytes_and_body_follows_the_event_schema() -> None:
    item = _anomaly()
    with fake_api_core() as server:
        result = _client(server.url).notify([item])

    assert (result.delivered, result.failed) == (1, 0)
    (request,) = server.requests
    assert request.path == NOTIFY_PATH
    assert request.headers[SIGNATURE_HEADER.lower()] == sign(SECRET, request.body)
    assert request.headers["content-type"] == "application/json"
    document = json.loads(request.body)
    assert document == {
        "anomalies": [
            {
                "id": str(item.id),
                "tenantId": str(item.tenant_id),
                "metricId": str(item.metric_id),
                "windowStart": "2026-06-01T11:00:00+00:00",
                "windowEnd": "2026-06-01T11:00:00+00:00",
                "score": 0.91,
                "detector": "ZSCORE",
                "severity": "HIGH",
                "createdAt": "2026-06-01T11:00:00+00:00",
            }
        ]
    }


def test_retries_on_503_then_succeeds() -> None:
    sleeps: list[float] = []
    with fake_api_core([503, 503, 202]) as server:
        result = _client(server.url, sleeps).notify([_anomaly()])

    assert (result.delivered, result.failed) == (1, 0)
    assert len(server.requests) == 3
    assert sleeps == [0.5, 1.0]
    assert len({r.body for r in server.requests}) == 1


def test_retries_on_429() -> None:
    with fake_api_core([429, 202]) as server:
        result = _client(server.url).notify([_anomaly()])

    assert result.delivered == 1
    assert len(server.requests) == 2


def test_does_not_retry_on_400() -> None:
    sleeps: list[float] = []
    with fake_api_core([400]) as server:
        result = _client(server.url, sleeps).notify([_anomaly()])

    assert (result.delivered, result.failed) == (0, 1)
    assert len(server.requests) == 1
    assert sleeps == []


def test_does_not_retry_on_401() -> None:
    with fake_api_core([401]) as server:
        result = _client(server.url).notify([_anomaly()])

    assert result.failed == 1
    assert len(server.requests) == 1


def test_gives_up_after_three_attempts_without_raising() -> None:
    sleeps: list[float] = []
    with fake_api_core([500]) as server:
        result = _client(server.url, sleeps).notify([_anomaly(), _anomaly()])

    assert (result.delivered, result.failed) == (0, 2)
    assert len(server.requests) == 3
    assert sleeps == [0.5, 1.0]


def test_connection_failure_is_retried_and_never_raised() -> None:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    sleeps: list[float] = []

    result = _client(f"http://127.0.0.1:{port}", sleeps).notify([_anomaly()])

    assert (result.delivered, result.failed) == (0, 1)
    assert len(sleeps) == 2


def test_batches_hold_at_most_500_anomalies() -> None:
    items = [_anomaly() for _ in range(MAX_BATCH * 2 + 200)]
    with fake_api_core() as server:
        result = _client(server.url).notify(items)

    assert (result.delivered, result.failed) == (1200, 0)
    sizes = [len(json.loads(r.body)["anomalies"]) for r in server.requests]
    assert sizes == [500, 500, 200]
    sent = [a["id"] for r in server.requests for a in json.loads(r.body)["anomalies"]]
    assert sent == [str(i.id) for i in items]


def test_one_failed_batch_does_not_stop_the_next() -> None:
    items = [_anomaly() for _ in range(MAX_BATCH + 1)]
    with fake_api_core([400, 202]) as server:
        result = _client(server.url).notify(items)

    assert (result.delivered, result.failed) == (1, 500)
    assert len(server.requests) == 2


def test_nothing_to_send_makes_no_request() -> None:
    with fake_api_core() as server:
        result = _client(server.url).notify([])

    assert (result.delivered, result.failed) == (0, 0)
    assert server.requests == []
