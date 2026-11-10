"""Startup verification of the reader and writer database roles."""

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, create_engine, text
from sqlalchemy.engine import make_url

from pulseboard_analytics.main import create_app
from pulseboard_analytics.repositories.engines import make_engine
from pulseboard_analytics.repositories.privileges import (
    RoleConfigurationError,
    verify_database_roles,
)
from pulseboard_analytics.settings import Settings
from tests.conftest import SECRET


def test_the_least_privilege_roles_pass(reader_engine: Engine, writer_engine: Engine) -> None:
    verify_database_roles(reader_engine, writer_engine)


@pytest.fixture
def owner_engine(postgres_url: str):  # type: ignore[no-untyped-def]
    engine = make_engine(postgres_url)
    yield engine
    engine.dispose()


def test_owner_credentials_as_reader_are_refused(
    owner_engine: Engine, writer_engine: Engine
) -> None:
    with pytest.raises(RoleConfigurationError) as error:
        verify_database_roles(owner_engine, writer_engine)

    message = str(error.value)
    assert "reader role 'test'" in message
    assert "is a superuser" in message
    assert "can INSERT into anomaly" in message


def test_owner_credentials_as_writer_are_refused(
    reader_engine: Engine, owner_engine: Engine
) -> None:
    with pytest.raises(RoleConfigurationError) as error:
        verify_database_roles(reader_engine, owner_engine)

    message = str(error.value)
    assert "writer role 'test'" in message
    assert "can SELECT metric_point" in message


def test_swapped_roles_are_refused(reader_engine: Engine, writer_engine: Engine) -> None:
    with pytest.raises(RoleConfigurationError) as error:
        verify_database_roles(writer_engine, reader_engine)

    message = str(error.value)
    assert "reader role 'analytics_writer' cannot SELECT metric_point" in message
    assert "writer role 'analytics_reader' cannot INSERT anomaly" in message


def test_a_non_superuser_table_owner_is_refused(
    postgres_url: str, owner: Engine, reader_engine: Engine, writer_engine: Engine
) -> None:
    with owner.begin() as conn:
        conn.execute(text("CREATE ROLE schema_owner LOGIN PASSWORD 'o' NOSUPERUSER"))
        conn.execute(text("GRANT SELECT ON metric_point TO schema_owner"))
        conn.execute(text("ALTER TABLE detector_state OWNER TO schema_owner"))
    try:
        url = make_url(postgres_url).set(
            drivername="postgresql+psycopg", username="schema_owner", password="o"
        )
        engine = create_engine(url)
        try:
            with pytest.raises(RoleConfigurationError, match="owns tables"):
                verify_database_roles(engine, writer_engine)
        finally:
            engine.dispose()
    finally:
        with owner.begin() as conn:
            conn.execute(text("ALTER TABLE detector_state OWNER TO test"))
            conn.execute(text("REVOKE ALL ON metric_point FROM schema_owner"))
            conn.execute(text("DROP ROLE schema_owner"))


def _settings(reader_url: str, writer_url: str, redis_url: str) -> Settings:
    return Settings(
        database_reader_url=reader_url,
        database_writer_url=writer_url,
        redis_url=redis_url,
        api_core_base_url="http://127.0.0.1:9",
        internal_shared_secret=SECRET,
        scheduler_enabled=True,
    )


def test_startup_fails_with_owner_credentials_before_the_scheduler_starts(
    postgres_url: str, reader_url: str, settings: Settings, monkeypatch: pytest.MonkeyPatch
) -> None:
    started: list[bool] = []
    monkeypatch.setattr(
        "pulseboard_analytics.runtime.start_scheduler",
        lambda *args, **kwargs: started.append(True),
    )
    owner_url = make_url(postgres_url).set(drivername="postgresql+psycopg")
    configured = _settings(
        reader_url,
        owner_url.render_as_string(hide_password=False),
        settings.redis_url,
    )

    with (
        pytest.raises(RoleConfigurationError, match="writer role"),
        TestClient(create_app(configured)),
    ):
        pass

    assert started == []


def test_startup_succeeds_with_the_restricted_roles(
    settings: Settings, monkeypatch: pytest.MonkeyPatch
) -> None:
    started: list[bool] = []
    monkeypatch.setattr(
        "pulseboard_analytics.runtime.start_scheduler",
        lambda *args, **kwargs: started.append(True),
    )
    configured = _settings(
        settings.database_reader_url, settings.database_writer_url, settings.redis_url
    )

    with TestClient(create_app(configured)) as client:
        assert client.get("/health").status_code == 200

    assert started == [True]
