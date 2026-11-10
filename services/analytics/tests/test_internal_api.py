"""POST /internal/v1/score: authentication, validation and behaviour."""

import json
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from redis import Redis
from sqlalchemy import Engine, text

from pulseboard_analytics.main import create_app
from pulseboard_analytics.security import SIGNATURE_HEADER, sign
from pulseboard_analytics.settings import Settings
from tests.conftest import SECRET, seed_metric
from tests.support.fake_server import FakeApiCore, fake_api_core
from tests.support.series import seasonal

PATH = "/internal/v1/score"


@pytest.fixture
def api_core() -> Iterator[FakeApiCore]:
    with fake_api_core() as server:
        yield server


@pytest.fixture
def client(
    settings: Settings, api_core: FakeApiCore, owner: Engine, redis_client: Redis
) -> Iterator[TestClient]:
    configured = Settings(
        database_reader_url=settings.database_reader_url,
        database_writer_url=settings.database_writer_url,
        redis_url=settings.redis_url,
        api_core_base_url=api_core.url,
        internal_shared_secret=SECRET,
        scheduler_enabled=False,
    )
    with TestClient(create_app(configured)) as test_client:
        yield test_client


def _body(metric_id: object) -> bytes:
    return json.dumps({"metricId": str(metric_id)}).encode()


def _spiky_metric(owner: Engine) -> str:
    values = seasonal(288)
    values[250] += 80.0
    _, metric = seed_metric(owner, values, end=datetime.now(UTC) - timedelta(minutes=5))
    return str(metric)


def test_valid_signature_scores_stores_and_notifies(
    client: TestClient, owner: Engine, api_core: FakeApiCore
) -> None:
    metric = _spiky_metric(owner)
    body = _body(metric)

    response = client.post(PATH, content=body, headers={SIGNATURE_HEADER: sign(SECRET, body)})

    assert response.status_code == 200
    payload = response.json()
    assert payload["inserted"] >= 1
    assert any(r["detector"] == "ZSCORE" and r["severity"] == "HIGH" for r in payload["results"])
    with owner.connect() as conn:
        stored = conn.execute(text("SELECT count(*) FROM anomaly")).scalar_one()
    assert stored == payload["inserted"]
    (notification,) = api_core.requests
    assert notification.headers[SIGNATURE_HEADER.lower()] == sign(SECRET, notification.body)
    assert len(json.loads(notification.body)["anomalies"]) == payload["inserted"]


def test_repeating_the_call_inserts_and_notifies_nothing_more(
    client: TestClient, owner: Engine, api_core: FakeApiCore
) -> None:
    body = _body(_spiky_metric(owner))
    headers = {SIGNATURE_HEADER: sign(SECRET, body)}

    first = client.post(PATH, content=body, headers=headers)
    second = client.post(PATH, content=body, headers=headers)

    assert first.json()["inserted"] >= 1
    assert second.status_code == 200
    assert second.json()["inserted"] == 0
    assert second.json()["results"] == first.json()["results"]
    assert len(api_core.requests) == 1


def test_missing_signature_is_unauthorized(client: TestClient, owner: Engine) -> None:
    response = client.post(PATH, content=_body(_spiky_metric(owner)))

    assert response.status_code == 401


def test_wrong_signature_is_unauthorized(client: TestClient, owner: Engine) -> None:
    body = _body(_spiky_metric(owner))

    for signature in (sign("w" * 40, body), "0" * 64, "nonsense", "", sign(SECRET, body).upper()):
        response = client.post(PATH, content=body, headers={SIGNATURE_HEADER: signature})
        assert response.status_code == 401, signature


def test_tampered_body_is_unauthorized_and_nothing_is_written(
    client: TestClient, owner: Engine
) -> None:
    metric = _spiky_metric(owner)
    signature = sign(SECRET, _body(metric))
    tampered = _body(metric) + b" "

    response = client.post(PATH, content=tampered, headers={SIGNATURE_HEADER: signature})

    assert response.status_code == 401
    with owner.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM anomaly")).scalar_one() == 0


def test_signature_for_another_metric_cannot_be_replayed(client: TestClient, owner: Engine) -> None:
    signature = sign(SECRET, _body(uuid4()))

    response = client.post(
        PATH, content=_body(_spiky_metric(owner)), headers={SIGNATURE_HEADER: signature}
    )

    assert response.status_code == 401


def test_unknown_metric_is_not_found(client: TestClient) -> None:
    body = _body(uuid4())

    response = client.post(PATH, content=body, headers={SIGNATURE_HEADER: sign(SECRET, body)})

    assert response.status_code == 404


@pytest.mark.parametrize(
    "raw",
    [
        b'{"metricId":"not-a-uuid"}',
        b"{}",
        b'{"metricId":"' + b"0" * 8 + b'"}',
        b'{"metricId":"00000000-0000-0000-0000-000000000000","extra":1}',
        b"not json",
    ],
)
def test_malformed_body_with_valid_signature_is_unprocessable(
    client: TestClient, raw: bytes
) -> None:
    response = client.post(PATH, content=raw, headers={SIGNATURE_HEADER: sign(SECRET, raw)})

    assert response.status_code == 422


def test_malformed_body_without_signature_is_unauthorized_not_unprocessable(
    client: TestClient,
) -> None:
    assert client.post(PATH, content=b'{"metricId":"nope"}').status_code == 401


def test_oversized_body_is_rejected_before_hashing(client: TestClient) -> None:
    raw = b'{"metricId":"' + b"a" * 10_000 + b'"}'

    response = client.post(PATH, content=raw, headers={SIGNATURE_HEADER: sign(SECRET, raw)})

    assert response.status_code == 413


def test_notify_outage_does_not_fail_the_request(
    settings: Settings, owner: Engine, redis_client: Redis
) -> None:
    with fake_api_core([400]) as server:
        configured = Settings(
            database_reader_url=settings.database_reader_url,
            database_writer_url=settings.database_writer_url,
            redis_url=settings.redis_url,
            api_core_base_url=server.url,
            internal_shared_secret=SECRET,
            scheduler_enabled=False,
        )
        body = _body(_spiky_metric(owner))
        with TestClient(create_app(configured)) as test_client:
            response = test_client.post(
                PATH, content=body, headers={SIGNATURE_HEADER: sign(SECRET, body)}
            )

    assert response.status_code == 200
    assert response.json()["inserted"] >= 1


@pytest.mark.parametrize("valid_first", [True, False])
@pytest.mark.parametrize("name", ["X-Internal-Signature", "x-internal-signature"])
def test_duplicate_signature_headers_are_unauthorized_in_both_orders(
    client: TestClient, owner: Engine, valid_first: bool, name: str
) -> None:
    body = _body(_spiky_metric(owner))
    good = (SIGNATURE_HEADER, sign(SECRET, body))
    bad = (name, "0" * 64)

    response = client.post(PATH, content=body, headers=[good, bad] if valid_first else [bad, good])

    assert response.status_code == 401
    with owner.connect() as conn:
        assert conn.execute(text("SELECT count(*) FROM anomaly")).scalar_one() == 0


def test_identical_duplicate_signature_headers_are_unauthorized_too(
    client: TestClient, owner: Engine
) -> None:
    body = _body(_spiky_metric(owner))
    signature = sign(SECRET, body)

    response = client.post(
        PATH,
        content=body,
        headers=[(SIGNATURE_HEADER, signature), (SIGNATURE_HEADER.lower(), signature)],
    )

    assert response.status_code == 401
