#!/usr/bin/env python3
"""Checks that `docker compose config` passes the tunable settings to the analytics and api-core services.

Renders the compose file twice with throwaway secrets: once with defaults and once with overrides, and compares
the rendered environment. Needs only the docker CLI. Exit code 1 when a value is missing or wrong.
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SECRETS = {
    "POSTGRES_PASSWORD": "x" * 16,
    "API_CORE_DB_PASSWORD": "x" * 16,
    "ANALYTICS_READER_DB_PASSWORD": "x" * 16,
    "ANALYTICS_WRITER_DB_PASSWORD": "x" * 16,
    "REDIS_PASSWORD": "x" * 16,
    "INTERNAL_SHARED_SECRET": "x" * 32,
    "JWT_SECRET": "x" * 32,
}

# (service, variable, default, override)
SETTINGS = [
    ("analytics", "SCORE_DEADLINE_SECONDS", "50", "30"),
    ("analytics", "FOREST_CACHE_MAX", "256", "64"),
    ("analytics", "VERIFY_DB_ROLES", "true", "false"),
    ("api-core", "ANOMALY_STREAM_MAX_PER_TENANT", "10", "3"),
]


def render(overrides: dict[str, str]) -> dict[str, dict[str, str]]:
    with tempfile.TemporaryDirectory() as tmp:
        env_file = Path(tmp) / "env"
        values = {**SECRETS, **overrides}
        env_file.write_text("".join(f"{k}={v}\n" for k, v in values.items()), encoding="utf-8")
        out = subprocess.run(
            ["docker", "compose", "--env-file", str(env_file), "-f", str(ROOT / "infra" / "docker-compose.yml"),
             "config", "--format", "json"],
            cwd=ROOT, text=True, encoding="utf-8", capture_output=True, check=True,
        ).stdout  # fmt: skip
    services = json.loads(out)["services"]
    return {name: {k: str(v) for k, v in svc.get("environment", {}).items()} for name, svc in services.items()}


def main() -> int:
    failures = []
    defaults = render({})
    overridden = render({var: override for _, var, _, override in SETTINGS})
    for service, var, default, override in SETTINGS:
        for label, rendered, expected in (("default", defaults, default), ("override", overridden, override)):
            actual = rendered.get(service, {}).get(var)
            ok = actual == expected
            print(f"[{'ok' if ok else 'FAIL'}] {service} {var} {label}: {actual!r} (expected {expected!r})")
            if not ok:
                failures.append((service, var, label))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
