"""Prometheus metrics: what is counted, where it is served and what it never contains."""

import json
import re
import urllib.error
import urllib.request
import uuid
from collections.abc import Iterator
from dataclasses import replace
from typing import cast

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from prometheus_client.exposition import generate_latest
from redis import Redis
from sqlalchemy import Engine

from pulseboard_analytics import __version__, metrics
from pulseboard_analytics.budget import Budget
from pulseboard_analytics.clients.api_core import ApiCoreClient
from pulseboard_analytics.jobs.score_all import LOCK_KEY, RunSummary, score_all_metrics
from pulseboard_analytics.jobs.scoring import MetricScorer, ScoreOutcome
from pulseboard_analytics.main import create_app
from pulseboard_analytics.repositories.connections import PoolBusyError, connect
from pulseboard_analytics.repositories.engines import make_engine
from pulseboard_analytics.repositories.models import MetricRef
from pulseboard_analytics.repositories.reader import MetricReader
from pulseboard_analytics.repositories.writer import AnomalyWriter, DetectorStateStore
from pulseboard_analytics.security import SIGNATURE_HEADER, sign
from pulseboard_analytics.settings import Settings, SettingsError
from tests.conftest import SECRET, seed_metric
from tests.support.fake_server import fake_api_core
from tests.test_score_all import Clock, RecordingNotifier, _spiky

P = metrics.PREFIX
LOCK_TTL_MS = 30_000


def value(name: str, **labels: str) -> float:
    """Current value of one sample (0.0 when the series does not exist yet)."""
    return metrics.REGISTRY.get_sample_value(P + name, labels) or 0.0


class Delta:
    """Differences of samples since construction (the registry is process-wide)."""

    def __init__(self, *series: tuple[str, dict[str, str]]) -> None:
        self._before = {(n, tuple(sorted(lab.items()))): value(n, **lab) for n, lab in series}

    def __call__(self, name: str, **labels: str) -> float:
        key = (name, tuple(sorted(labels.items())))
        return value(name, **labels) - self._before[key]


def _scorer(reader: Engine, writer: Engine, cls: type[MetricScorer] = MetricScorer) -> MetricScorer:
    return cls(
        reader=MetricReader(reader),
        anomalies=AnomalyWriter(writer),
        states=DetectorStateStore(writer),
        clock=Clock(),
    )


def _run(
    reader: Engine, scorer: MetricScorer, notifier: RecordingNotifier | ApiCoreClient, redis: Redis
) -> RunSummary:
    return score_all_metrics(
        reader=MetricReader(reader),
        scorer=scorer,
        notifier=notifier,
        redis=redis,
        lock_ttl_ms=LOCK_TTL_MS,
    )


def _exposition() -> str:
    return generate_latest(metrics.REGISTRY).decode()


def _series_count() -> int:
    return sum(1 for line in _exposition().splitlines() if line and not line.startswith("#"))


# --- a scored run -----------------------------------------------------------------------------


def test_a_successful_run_is_counted(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    seed_metric(owner, _spiky())
    scorer = _scorer(reader_engine, writer_engine)
    d = Delta(
        ("score_runs_total", {"result": "completed"}),
        ("score_run_duration_seconds_count", {}),
        ("metrics_scored_total", {}),
        ("lock_acquire_total", {"result": "acquired"}),
        ("model_trainings_total", {"result": "won"}),
        ("anomalies_inserted_total", {"detector": "ZSCORE", "severity": "HIGH"}),
    )

    summary = _run(reader_engine, scorer, RecordingNotifier(), redis_client)

    assert summary.inserted >= 1
    assert d("score_runs_total", result="completed") == 1
    assert d("score_run_duration_seconds_count") == 1
    assert d("metrics_scored_total") == 1
    assert d("lock_acquire_total", result="acquired") == 1
    assert d("model_trainings_total", result="won") == 1
    assert d("anomalies_inserted_total", detector="ZSCORE", severity="HIGH") >= 1
    assert value("unscored_metrics") == 0
    assert value("slow_metrics") == 0
    assert value("forest_cache_entries") == scorer.cached_forests == 1
    text = _exposition()
    for name in (
        "score_runs_total",
        "score_run_duration_seconds_bucket",
        "metrics_scored_total",
        "metrics_failed_total",
        "unscored_metrics",
        "anomalies_inserted_total",
        "notify_requests_total",
        "notify_duration_seconds_bucket",
        "forest_cache_entries",
        "model_trainings_total",
        "db_pool_wait_seconds_total",
        "db_pool_timeouts_total",
        "lock_acquire_total",
        "slow_metrics",
        "score_requests_total",
        "build_info",
    ):
        assert P + name in text, name
    assert f'{P}build_info{{version="{__version__}"}} 1.0' in text


def test_a_lock_held_elsewhere_is_a_skipped_run(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    seed_metric(owner, _spiky())
    redis_client.set(LOCK_KEY, "someone-else", px=20_000)
    d = Delta(
        ("score_runs_total", {"result": "skipped"}),
        ("lock_acquire_total", {"result": "busy"}),
        ("metrics_scored_total", {}),
    )

    _run(reader_engine, _scorer(reader_engine, writer_engine), RecordingNotifier(), redis_client)

    assert d("score_runs_total", result="skipped") == 1
    assert d("lock_acquire_total", result="busy") == 1
    assert d("metrics_scored_total") == 0


class _FailsFor(MetricScorer):
    broken: MetricRef | None = None

    def score_metric(self, metric: MetricRef, budget: Budget | None = None) -> ScoreOutcome:
        if metric == self.broken:
            raise RuntimeError("boom with a private detail")
        return super().score_metric(metric, budget)


def test_one_failing_metric_counts_as_failed_and_the_others_as_scored(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    tenant, broken = seed_metric(owner, _spiky())
    seed_metric(owner, _spiky())
    scorer = _scorer(reader_engine, writer_engine, _FailsFor)
    assert isinstance(scorer, _FailsFor)
    scorer.broken = MetricRef(id=broken, tenant_id=tenant)
    d = Delta(
        ("metrics_failed_total", {"reason": "error"}),
        ("metrics_scored_total", {}),
        ("score_runs_total", {"result": "completed"}),
    )

    _run(reader_engine, scorer, RecordingNotifier(), redis_client)

    assert d("metrics_failed_total", reason="error") == 1
    assert d("metrics_scored_total") == 1
    assert d("score_runs_total", result="completed") == 1
    assert "private detail" not in _exposition()


class _StealsTheLock(MetricScorer):
    redis: Redis | None = None

    def score_metric(self, metric: MetricRef, budget: Budget | None = None) -> ScoreOutcome:
        assert self.redis is not None
        self.redis.delete(LOCK_KEY)  # the lease is lost while the first metric is scored
        return super().score_metric(metric, budget)


def test_a_lost_lease_is_a_lost_lease_run_with_unscored_metrics(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    for _ in range(3):
        seed_metric(owner, _spiky())
    scorer = _scorer(reader_engine, writer_engine, _StealsTheLock)
    assert isinstance(scorer, _StealsTheLock)
    scorer.redis = redis_client
    d = Delta(
        ("score_runs_total", {"result": "lost_lease"}),
        ("score_runs_total", {"result": "completed"}),
        ("metrics_scored_total", {}),
    )

    _run(reader_engine, scorer, RecordingNotifier(), redis_client)

    assert d("score_runs_total", result="lost_lease") == 1
    assert d("score_runs_total", result="completed") == 0
    assert d("metrics_scored_total") == 1
    assert value("unscored_metrics") == 2


def test_the_run_deadline_is_a_deadline_run(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    seed_metric(owner, _spiky())
    d = Delta(("score_runs_total", {"result": "deadline"}))

    score_all_metrics(
        reader=MetricReader(reader_engine),
        scorer=_scorer(reader_engine, writer_engine),
        notifier=RecordingNotifier(),
        redis=redis_client,
        lock_ttl_ms=LOCK_TTL_MS,
        deadline_seconds=-1.0,
    )

    assert d("score_runs_total", result="deadline") == 1
    assert value("unscored_metrics") == 1


def test_a_second_scoring_of_the_same_metric_does_not_train_again(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    seed_metric(owner, _spiky())
    scorer = _scorer(reader_engine, writer_engine)
    _run(reader_engine, scorer, RecordingNotifier(), redis_client)
    d = Delta(("model_trainings_total", {"result": "won"}), ("metrics_scored_total", {}))

    _run(reader_engine, scorer, RecordingNotifier(), redis_client)

    assert d("model_trainings_total", result="won") == 0
    assert d("metrics_scored_total") == 1


# --- notification -----------------------------------------------------------------------------

_NOTIFY_SERIES = [("notify_requests_total", {"outcome": o}) for o in ("ok", "retry", "failed")] + [
    ("notify_duration_seconds_count", {})
]


def _batch(owner: Engine, reader: Engine, writer: Engine, redis: Redis) -> list:  # type: ignore[type-arg]
    seed_metric(owner, _spiky())
    notifier = RecordingNotifier()
    _run(reader, _scorer(reader, writer), notifier, redis)
    assert notifier.calls
    return notifier.calls[0]


def test_notify_success_is_counted_ok(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    batch = _batch(owner, reader_engine, writer_engine, redis_client)
    with fake_api_core([202]) as server:
        d = Delta(*_NOTIFY_SERIES)
        ApiCoreClient(server.url, SECRET, sleep=lambda _: None).notify(batch)
    assert d("notify_requests_total", outcome="ok") == 1
    assert d("notify_requests_total", outcome="failed") == 0
    assert d("notify_duration_seconds_count") == 1


def test_notify_failure_counts_retries_then_one_failure(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    batch = _batch(owner, reader_engine, writer_engine, redis_client)
    with fake_api_core([500]) as server:
        d = Delta(*_NOTIFY_SERIES)
        result = ApiCoreClient(server.url, SECRET, sleep=lambda _: None).notify(batch)
    assert result.failed == len(batch)
    assert d("notify_requests_total", outcome="retry") == 2
    assert d("notify_requests_total", outcome="failed") == 1
    assert d("notify_requests_total", outcome="ok") == 0
    assert d("notify_duration_seconds_count") == 1


def test_notify_retry_then_success_and_rejection(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    batch = _batch(owner, reader_engine, writer_engine, redis_client)
    with fake_api_core([503, 202]) as server:
        d = Delta(*_NOTIFY_SERIES)
        ApiCoreClient(server.url, SECRET, sleep=lambda _: None).notify(batch)
    assert d("notify_requests_total", outcome="retry") == 1
    assert d("notify_requests_total", outcome="ok") == 1
    assert d("notify_requests_total", outcome="failed") == 0

    with fake_api_core([400]) as server:
        d = Delta(*_NOTIFY_SERIES)
        ApiCoreClient(server.url, SECRET, sleep=lambda _: None).notify(batch)
    assert d("notify_requests_total", outcome="failed") == 1
    assert d("notify_requests_total", outcome="retry") == 0


# --- database pool ----------------------------------------------------------------------------


def test_pool_timeouts_and_waits_are_counted(writer_url: str) -> None:
    engine = make_engine(writer_url, pool_size=1, pool_timeout=0.2)  # 1 + 2 overflow
    held = [connect(engine) for _ in range(3)]
    try:
        for context in held:
            context.__enter__()
        d = Delta(
            ("db_pool_timeouts_total", {"reason": "pool_busy"}),
            ("db_pool_wait_seconds_total", {}),
        )
        with pytest.raises(PoolBusyError), connect(engine):
            pass
        assert d("db_pool_timeouts_total", reason="pool_busy") == 1
        assert d("db_pool_wait_seconds_total") >= 0.15
    finally:
        for context in held:
            context.__exit__(None, None, None)
        engine.dispose()


# --- score endpoint ---------------------------------------------------------------------------


@pytest.fixture
def served(settings: Settings, owner: Engine, redis_client: Redis) -> Iterator[TestClient]:
    configured = replace(settings, metrics_enabled=True, metrics_port=0)
    with TestClient(create_app(configured)) as client:
        yield client


def _metrics_url(client: TestClient) -> str:
    server = cast(FastAPI, client.app).state.runtime.metrics_server
    assert server is not None
    return f"http://127.0.0.1:{server.port}/metrics"


def _post(client: TestClient, payload: bytes, signature: str | None) -> int:
    headers = {} if signature is None else {SIGNATURE_HEADER: signature}
    return client.post("/internal/v1/score", content=payload, headers=headers).status_code


def test_score_requests_are_counted_by_status_only(served: TestClient) -> None:
    body = json.dumps({"metricId": str(uuid.uuid4())}).encode()
    d = Delta(*(("score_requests_total", {"status": s}) for s in ("401", "404", "422")))
    before_health = _series_count()

    assert _post(served, body, None) == 401
    assert _post(served, body, sign(SECRET, body)) == 404
    bad = b'{"nope": 1}'
    assert _post(served, bad, sign(SECRET, bad)) == 422
    assert served.get("/health").status_code == 200  # other paths are not counted

    assert d("score_requests_total", status="401") == 1
    assert d("score_requests_total", status="404") == 1
    assert d("score_requests_total", status="422") == 1
    prefix = f"{P}score_requests_total{{"
    series = [line for line in _exposition().splitlines() if line.startswith(prefix)]
    assert series
    assert all(re.fullmatch(r'\w+\{status="\d{3}"\} \S+', line) for line in series)
    assert _series_count() <= before_health + 6  # _total and _created per status


# --- where the metrics are served -------------------------------------------------------------


def test_the_main_app_does_not_serve_metrics(served: TestClient) -> None:
    assert served.get("/metrics").status_code == 404
    assert served.get("/metrics/").status_code == 404
    assert "/metrics" not in served.get("/openapi.json").text


def test_metrics_are_served_on_their_own_port_without_hmac(served: TestClient) -> None:
    with urllib.request.urlopen(_metrics_url(served), timeout=5) as response:
        body = response.read().decode()
        content_type = response.headers["Content-Type"]
        status = response.status

    assert status == 200
    assert content_type.startswith("text/plain")
    assert f"{P}build_info" in body
    assert "# TYPE" in body


def test_the_metrics_listener_is_stopped_with_the_service(
    settings: Settings, owner: Engine, redis_client: Redis
) -> None:
    configured = replace(settings, metrics_enabled=True, metrics_port=0)
    with TestClient(create_app(configured)) as client:
        url = _metrics_url(client)
    with pytest.raises(urllib.error.URLError):
        urllib.request.urlopen(url, timeout=2)


def test_metrics_can_be_disabled(settings: Settings, owner: Engine, redis_client: Redis) -> None:
    with TestClient(create_app(replace(settings, metrics_enabled=False))) as client:
        assert cast(FastAPI, client.app).state.runtime.metrics_server is None


def test_settings_defaults_and_validation() -> None:
    env = {
        "DATABASE_READER_URL": "postgresql://r",
        "DATABASE_WRITER_URL": "postgresql://w",
        "REDIS_URL": "redis://x",
        "API_CORE_BASE_URL": "http://x",
        "INTERNAL_SHARED_SECRET": "k" * 40,
    }
    defaults = Settings.from_env(env)
    assert defaults.metrics_enabled is True
    assert defaults.metrics_port == 9100
    custom = Settings.from_env({**env, "METRICS_ENABLED": "false", "METRICS_PORT": "9200"})
    assert custom.metrics_enabled is False
    assert custom.metrics_port == 9200
    with pytest.raises(SettingsError, match="METRICS_PORT"):
        Settings.from_env({**env, "METRICS_PORT": "70000"})
    with pytest.raises(SettingsError, match="at least 1"):
        Settings.from_env({**env, "METRICS_PORT": "0"})


# --- cardinality and secrets ------------------------------------------------------------------


def test_scoring_many_metrics_creates_no_per_metric_series(
    owner: Engine, reader_engine: Engine, writer_engine: Engine, redis_client: Redis
) -> None:
    ids = [seed_metric(owner, _spiky()) for _ in range(3)]
    scorer = _scorer(reader_engine, writer_engine)
    _run(reader_engine, scorer, RecordingNotifier(), redis_client)
    after_three = _series_count()

    ids += [seed_metric(owner, _spiky()) for _ in range(15)]
    _run(reader_engine, scorer, RecordingNotifier(), redis_client)

    text = _exposition()
    assert value("metrics_scored_total") >= 18
    assert _series_count() <= after_three + 2  # at most a detector/severity pair seen first now
    for tenant, metric in ids:
        assert str(metric) not in text
        assert str(tenant) not in text
    assert re.search(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}", text) is None


def test_the_exposition_contains_no_secret(
    owner: Engine,
    reader_engine: Engine,
    writer_engine: Engine,
    redis_client: Redis,
    settings: Settings,
) -> None:
    seed_metric(owner, _spiky())
    with fake_api_core([500]) as server:
        notifier = ApiCoreClient(server.url, SECRET, sleep=lambda _: None)
        _run(reader_engine, _scorer(reader_engine, writer_engine), notifier, redis_client)
    text = _exposition()

    assert "score_runs_total" in text
    for secret in (
        SECRET,
        "reader-secret",
        "writer-secret",
        settings.database_reader_url,
        settings.database_writer_url,
        settings.redis_url,
        "127.0.0.1",
    ):
        assert secret not in text
