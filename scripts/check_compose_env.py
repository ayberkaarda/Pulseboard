#!/usr/bin/env python3
"""Checks that `docker compose config` passes the tunable settings to the analytics and api-core services.

Renders the compose file twice with throwaway secrets: once with defaults and once with overrides, and compares
the rendered environment. Needs only the docker CLI. Exit code 1 when a value is missing or wrong.
"""

from __future__ import annotations

import json
import os
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
    "ALERT_WEBHOOK_SIGNING_SECRET": "s" * 32,
}

# (service, variable, default, override)
SETTINGS = [
    ("analytics", "SCORE_LOCK_TTL_MS", "55000", "40000"),
    ("analytics", "SCORE_DEADLINE_SECONDS", "50", "30"),
    ("analytics", "SCORE_LEASE_MARGIN_SECONDS", "5", "3"),
    ("analytics", "METRIC_LOCK_TIMEOUT_SECONDS", "10", "4"),
    ("analytics", "DB_POOL_TIMEOUT_SECONDS", "5", "2"),
    ("analytics", "DB_CONNECT_TIMEOUT_SECONDS", "5", "3"),
    ("analytics", "FOREST_CACHE_MAX", "256", "64"),
    ("analytics", "VERIFY_DB_ROLES", "true", "false"),
    ("api-core", "ANOMALY_STREAM_MAX_PER_TENANT", "10", "3"),
    ("api-core", "STREAM_TICKET_TTL", "30s", "20s"),
    ("api-core", "STREAM_TICKET_ISSUE_CAPACITY", "30", "5"),
    ("api-core", "STREAM_TICKET_ISSUE_PERIOD", "1m", "2m"),
    ("api-core", "REFRESH_FAMILY_TTL", "30d", "14d"),
    ("api-core", "ALERT_WEBHOOK_ALLOWED_PORTS", "443", "443,8443"),
    ("api-core", "ALERT_MAX_RULES_PER_TENANT", "50", "10"),
    ("api-core", "ALERT_DISPATCHER_ENABLED", "true", "false"),
    ("api-core", "ALERT_DISPATCH_INTERVAL", "5s", "15s"),
    ("api-core", "ALERT_DISPATCH_MAX_ATTEMPTS", "4", "2"),
    ("api-core", "ALERT_EVENTS_PER_PERIOD", "600", "100"),
    ("api-core", "ALERT_EVENTS_PERIOD", "1h", "30m"),
]

# Taken from the env file without a default; must reach api-core unchanged.
PASSED_THROUGH = [("api-core", "ALERT_WEBHOOK_SIGNING_SECRET", SECRETS["ALERT_WEBHOOK_SIGNING_SECRET"])]

# Must never reach a container, even when set in the environment of the caller (refused outside test/local profiles).
FORBIDDEN = ["ALERT_WEBHOOK_ALLOW_HTTP", "ALERT_WEBHOOK_ALLOW_PRIVATE_TARGETS"]


def render(
    overrides: dict[str, str], extra_process_env: dict[str, str] | None = None
) -> dict[str, dict[str, str]]:
    with tempfile.TemporaryDirectory() as tmp:
        env_file = Path(tmp) / "env"
        values = {**SECRETS, **overrides}
        env_file.write_text("".join(f"{k}={v}\n" for k, v in values.items()), encoding="utf-8")
        out = subprocess.run(
            ["docker", "compose", "--env-file", str(env_file), "-f", str(ROOT / "infra" / "docker-compose.yml"),
             "config", "--format", "json"],
            cwd=ROOT, text=True, encoding="utf-8", capture_output=True, check=True,
            env={**os.environ, **(extra_process_env or {})},
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
    for service, var, expected in PASSED_THROUGH:
        ok = defaults.get(service, {}).get(var) == expected
        print(f"[{'ok' if ok else 'FAIL'}] {service} {var} passed through")
        if not ok:
            failures.append((service, var, "pass-through"))
    forced = {var: "true" for var in FORBIDDEN}
    forbidden_env = render(forced, extra_process_env=forced)
    for var in FORBIDDEN:
        for label, rendered in (("default", defaults), ("forced", forbidden_env)):
            present = [svc for svc, env in rendered.items() if var in env]
            ok = not present
            print(f"[{'ok' if ok else 'FAIL'}] {var} absent from rendered environment ({label})")
            if not ok:
                failures.append((var, ",".join(present), label))
    with tempfile.TemporaryDirectory() as tmp:
        env_file = Path(tmp) / "env"
        env_file.write_text(
            "".join(f"{k}={v}\n" for k, v in SECRETS.items() if k != "ALERT_WEBHOOK_SIGNING_SECRET"), encoding="utf-8"
        )
        missing = subprocess.run(
            ["docker", "compose", "--env-file", str(env_file), "-f", str(ROOT / "infra" / "docker-compose.yml"),
             "config", "--format", "json"],
            cwd=ROOT, text=True, encoding="utf-8", capture_output=True,
        )  # fmt: skip
    ok = missing.returncode != 0 and "ALERT_WEBHOOK_SIGNING_SECRET" in missing.stderr
    print(f"[{'ok' if ok else 'FAIL'}] missing ALERT_WEBHOOK_SIGNING_SECRET is rejected")
    if not ok:
        failures.append(("api-core", "ALERT_WEBHOOK_SIGNING_SECRET", "required"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
