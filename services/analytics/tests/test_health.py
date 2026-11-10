"""HTTP health contract verification."""

from importlib.metadata import version

from fastapi.testclient import TestClient

from pulseboard_analytics.main import create_app
from pulseboard_analytics.settings import Settings


def test_health_needs_no_external_service() -> None:
    """Health returns HTTP 200 and the installed version while no backend is reachable."""
    settings = Settings(
        database_reader_url="postgresql+psycopg://r:r@127.0.0.1:9/db",
        database_writer_url="postgresql+psycopg://w:w@127.0.0.1:9/db",
        redis_url="redis://127.0.0.1:9/0",
        api_core_base_url="http://127.0.0.1:9",
        internal_shared_secret="h" * 40,
        scheduler_enabled=False,
    )

    with TestClient(create_app(settings)) as client:
        response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "version": version("pulseboard-analytics"),
    }
