"""OpenAPI YAML export verification."""

from pathlib import Path

import yaml

from pulseboard_analytics.main import create_app
from pulseboard_analytics.openapi_export import main, render_openapi_yaml

CONTRACT = Path(__file__).resolve().parents[3] / "contracts" / "analytics.openapi.yaml"


def test_export_writes_valid_yaml(tmp_path: Path) -> None:
    """The exported file parses as YAML and equals the application schema."""
    target = tmp_path / "analytics.openapi.yaml"

    assert main([str(target)]) == 0

    loaded = yaml.safe_load(target.read_text(encoding="utf-8"))
    assert loaded == create_app().openapi()
    assert "/health" in loaded["paths"]
    assert "/internal/v1/score" in loaded["paths"]


def test_export_requires_single_argument(tmp_path: Path) -> None:
    """Missing or extra arguments return a usage error without writing files."""
    assert main([]) == 2
    assert main([str(tmp_path / "a.yaml"), str(tmp_path / "b.yaml")]) == 2
    assert list(tmp_path.iterdir()) == []


def test_committed_contract_matches_the_application() -> None:
    """contracts/analytics.openapi.yaml is regenerated whenever the API changes."""
    assert CONTRACT.is_file(), f"missing {CONTRACT}; run the openapi export"

    committed = CONTRACT.read_text(encoding="utf-8")

    assert committed.replace("\r\n", "\n") == render_openapi_yaml()


def test_score_endpoint_declares_the_hmac_scheme() -> None:
    schema = create_app().openapi()

    operation = schema["paths"]["/internal/v1/score"]["post"]

    assert operation["security"] == [{"InternalHmac": []}]
    assert schema["components"]["securitySchemes"]["InternalHmac"]["name"] == "X-Internal-Signature"
    assert set(operation["responses"]) >= {"200", "401", "404", "413", "422"}
