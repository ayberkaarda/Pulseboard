"""HTTP health contract verification."""

from importlib.metadata import version

from fastapi.testclient import TestClient

from pulseboard_analytics.main import create_app


def test_health() -> None:
    """Health returns HTTP 200 and the installed distribution version."""
    with TestClient(create_app()) as client:
        response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "version": version("pulseboard-analytics"),
    }
