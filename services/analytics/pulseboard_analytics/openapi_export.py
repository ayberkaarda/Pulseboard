"""Write the service OpenAPI document as YAML.

Usage: ``python -m pulseboard_analytics.openapi_export <output.yaml>``
"""

import sys
from collections.abc import Sequence
from pathlib import Path

import yaml

from pulseboard_analytics.main import create_app


def render_openapi_yaml() -> str:
    """Return the application OpenAPI schema serialized as YAML."""
    schema = create_app().openapi()
    return yaml.safe_dump(schema, sort_keys=False, allow_unicode=True)


def main(argv: Sequence[str] | None = None) -> int:
    """Write the OpenAPI YAML to the path given as the single argument."""
    args = list(sys.argv[1:] if argv is None else argv)
    if len(args) != 1:
        sys.stderr.write("usage: python -m pulseboard_analytics.openapi_export <output.yaml>\n")
        return 2
    output = Path(args[0])
    output.write_text(render_openapi_yaml(), encoding="utf-8", newline="\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
