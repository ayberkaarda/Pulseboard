"""OpenAPI YAML export verification."""

from pathlib import Path

import yaml

from pulseboard_analytics.main import create_app
from pulseboard_analytics.openapi_export import main


def test_export_writes_valid_yaml(tmp_path: Path) -> None:
    """The exported file parses as YAML and equals the application schema."""
    target = tmp_path / "analytics.openapi.yaml"

    assert main([str(target)]) == 0

    loaded = yaml.safe_load(target.read_text(encoding="utf-8"))
    assert loaded == create_app().openapi()
    assert "/health" in loaded["paths"]


def test_export_requires_single_argument(tmp_path: Path) -> None:
    """Missing or extra arguments return a usage error without writing files."""
    assert main([]) == 2
    assert main([str(tmp_path / "a.yaml"), str(tmp_path / "b.yaml")]) == 2
    assert list(tmp_path.iterdir()) == []
