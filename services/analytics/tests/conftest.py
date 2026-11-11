"""Shared fixtures: disposable Postgres and Redis containers."""

import uuid
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta

import numpy as np
import pytest
from redis import Redis
from sqlalchemy import Engine, create_engine, text
from sqlalchemy.engine import make_url
from testcontainers.community.postgres import PostgresContainer
from testcontainers.core.container import DockerContainer
from testcontainers.core.wait_strategies import LogMessageWaitStrategy

from pulseboard_analytics.repositories.engines import make_engine
from pulseboard_analytics.settings import Settings
from tests.support.schema import create_schema, reset_data

POSTGRES_IMAGE = "postgres:18.6-alpine"
REDIS_IMAGE = "redis:8.10.3-alpine"
SECRET = "s3cret-" + "x" * 40
NOW = datetime(2026, 6, 1, 12, 0, tzinfo=UTC)


@pytest.fixture(scope="session")
def postgres_url() -> Iterator[str]:
    with PostgresContainer(POSTGRES_IMAGE, driver=None) as container:
        url = container.get_connection_url()
        owner = create_engine(make_url(url).set(drivername="postgresql+psycopg"))
        create_schema(owner)
        owner.dispose()
        yield url


def _role_url(base: str, user: str, password: str) -> str:
    return (
        make_url(base)
        .set(drivername="postgresql+psycopg", username=user, password=password)
        .render_as_string(hide_password=False)
    )


@pytest.fixture(scope="session")
def reader_url(postgres_url: str) -> str:
    return _role_url(postgres_url, "analytics_reader", "reader-secret")


@pytest.fixture(scope="session")
def writer_url(postgres_url: str) -> str:
    return _role_url(postgres_url, "analytics_writer", "writer-secret")


@pytest.fixture
def owner(postgres_url: str) -> Iterator[Engine]:
    engine = create_engine(make_url(postgres_url).set(drivername="postgresql+psycopg"))
    reset_data(engine)
    yield engine
    engine.dispose()


@pytest.fixture
def reader_engine(reader_url: str, owner: Engine) -> Iterator[Engine]:
    engine = make_engine(reader_url)
    yield engine
    engine.dispose()


@pytest.fixture
def writer_engine(writer_url: str, owner: Engine) -> Iterator[Engine]:
    engine = make_engine(writer_url)
    yield engine
    engine.dispose()


@pytest.fixture(scope="session")
def redis_address() -> Iterator[tuple[str, int]]:
    container = (
        DockerContainer(REDIS_IMAGE)
        .with_exposed_ports(6379)
        .waiting_for(LogMessageWaitStrategy("Ready to accept connections"))
    )
    with container:
        yield container.get_container_host_ip(), int(container.get_exposed_port(6379))


@pytest.fixture
def redis_client(redis_address: tuple[str, int]) -> Iterator[Redis]:
    client = Redis(host=redis_address[0], port=redis_address[1], decode_responses=True)
    client.flushall()
    yield client
    client.close()


@pytest.fixture
def settings(reader_url: str, writer_url: str, redis_address: tuple[str, int]) -> Settings:
    return Settings(
        database_reader_url=reader_url,
        database_writer_url=writer_url,
        redis_url=f"redis://{redis_address[0]}:{redis_address[1]}/0",
        api_core_base_url="http://127.0.0.1:9",
        internal_shared_secret=SECRET,
        scheduler_enabled=False,
        metrics_enabled=False,
    )


def seed_metric(
    owner: Engine,
    values: np.ndarray | list[float],
    *,
    tenant_id: uuid.UUID | None = None,
    end: datetime = NOW - timedelta(minutes=5),
) -> tuple[uuid.UUID, uuid.UUID]:
    """Create a tenant and a metric with one point per five minutes ending at ``end``."""
    tenant = tenant_id or uuid.uuid4()
    metric = uuid.uuid4()
    with owner.begin() as conn:
        conn.execute(
            text("INSERT INTO tenant (id) VALUES (:id) ON CONFLICT DO NOTHING"), {"id": tenant}
        )
        conn.execute(
            text("INSERT INTO metric_definition (id, tenant_id) VALUES (:id, :t)"),
            {"id": metric, "t": tenant},
        )
    add_points(owner, metric, values, end=end)
    return tenant, metric


def add_points(
    owner: Engine,
    metric_id: uuid.UUID,
    values: np.ndarray | list[float],
    *,
    end: datetime,
) -> None:
    """Insert one point per five minutes, the last at ``end``."""
    count = len(values)
    rows = [
        {"m": metric_id, "ts": end - timedelta(minutes=5 * (count - 1 - i)), "v": float(value)}
        for i, value in enumerate(values)
    ]
    with owner.begin() as conn:
        conn.execute(
            text("INSERT INTO metric_point (metric_id, ts, value) VALUES (:m, :ts, :v)"), rows
        )
