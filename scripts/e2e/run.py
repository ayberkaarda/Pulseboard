#!/usr/bin/env python3
"""End-to-end check of the anomaly chain: ingest -> analytics job -> api-core -> REST and SSE.

Brings up postgres, redis, api-core and analytics as a dedicated compose project (pulseboard-e2e) with
throwaway secrets, runs the scenario against the real stack and always removes the project afterwards.
Only the Python standard library and the docker CLI are needed. The user's own stack is never touched.
"""

from __future__ import annotations

import hashlib
import hmac
import http.client
import json
import random
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
PROJECT = "pulseboard-e2e"
DEADLINE_SECONDS = 180
POINTS = 300

results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    results.append((name, ok, detail))
    print(f"  [{'ok' if ok else 'FAIL'}] {name}" + (f" - {detail}" if detail else ""), flush=True)
    return ok


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def write_env(path: Path, api_port: int) -> dict[str, str]:
    """Create a throwaway env file from .env.example with freshly generated secrets."""
    values: dict[str, str] = {}
    for raw in (ROOT / ".env.example").read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, _, value = line.partition("=")
            values[key] = value
    for key in (
        "POSTGRES_PASSWORD",
        "API_CORE_DB_PASSWORD",
        "ANALYTICS_READER_DB_PASSWORD",
        "ANALYTICS_WRITER_DB_PASSWORD",
        "REDIS_PASSWORD",
    ):
        values[key] = secrets.token_hex(16)
    values["INTERNAL_SHARED_SECRET"] = secrets.token_hex(32)
    values["JWT_SECRET"] = secrets.token_hex(32)
    values["E2E_API_PORT"] = str(api_port)
    path.write_text("".join(f"{k}={v}\n" for k, v in values.items()), encoding="utf-8")
    return values


def compose(env_file: Path, *args: str, check_rc: bool = True) -> subprocess.CompletedProcess[str]:
    cmd = [
        "docker", "compose", "-p", PROJECT, "--env-file", str(env_file),
        "-f", str(ROOT / "infra" / "docker-compose.yml"),
        "-f", str(ROOT / "infra" / "docker-compose.e2e.yml"),
        *args,
    ]  # fmt: skip
    return subprocess.run(
        cmd, cwd=ROOT, text=True, encoding="utf-8", errors="replace", capture_output=True, check=check_rc
    )


class Api:
    def __init__(self, base: str) -> None:
        self.base = base

    def call(
        self,
        method: str,
        path: str,
        body: Any = None,
        raw: bytes | None = None,
        headers: dict[str, str] | None = None,
        token: str | None = None,
    ) -> tuple[int, Any]:
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        hdrs = {"Accept": "application/json", **(headers or {})}
        if data is not None:
            hdrs.setdefault("Content-Type", "application/json")
        if token:
            hdrs["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=hdrs)
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                status, payload = resp.status, resp.read()
        except urllib.error.HTTPError as err:
            status, payload = err.code, err.read()
        try:
            return status, json.loads(payload) if payload else None
        except ValueError:
            return status, payload.decode(errors="replace")


class SseClient(threading.Thread):
    """Reads GET /api/v1/stream/anomalies with a bearer token and collects the events."""

    def __init__(self, host: str, port: int, token: str) -> None:
        super().__init__(daemon=True)
        self.host, self.port, self.token = host, port, token
        self.events: list[dict[str, str]] = []
        self.lock = threading.Lock()
        self.connected = threading.Event()
        self.status = 0
        self.content_type = ""
        self.stop_flag = False
        self.conn: http.client.HTTPConnection | None = None

    def run(self) -> None:
        try:
            self.conn = http.client.HTTPConnection(self.host, self.port, timeout=240)
            self.conn.request(
                "GET",
                "/api/v1/stream/anomalies",
                headers={"Authorization": f"Bearer {self.token}", "Accept": "text/event-stream"},
            )
            resp = self.conn.getresponse()
            self.status = resp.status
            self.content_type = resp.getheader("Content-Type") or ""
            self.connected.set()
            if resp.status != 200:
                return
            current: dict[str, str] = {}
            while not self.stop_flag:
                line = resp.fp.readline()
                if not line:
                    break
                text = line.decode("utf-8").rstrip("\r\n")
                if text == "":
                    if current:
                        with self.lock:
                            self.events.append(current)
                        current = {}
                elif text.startswith(":"):
                    continue
                else:
                    field, _, value = text.partition(":")
                    current[field] = value[1:] if value.startswith(" ") else value
        except (OSError, http.client.HTTPException):
            pass
        finally:
            self.connected.set()

    def snapshot(self) -> list[dict[str, str]]:
        with self.lock:
            return list(self.events)

    def close(self) -> None:
        self.stop_flag = True
        if self.conn is not None:
            try:
                self.conn.close()
            except OSError:
                pass


def sign(secret: str, body: bytes) -> str:
    return hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()


def iso(ts: datetime) -> str:
    return ts.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def register(api: Api, label: str) -> dict[str, Any]:
    suffix = uuid.uuid4().hex[:8]
    status, body = api.call(
        "POST",
        "/api/v1/auth/register",
        {
            "email": f"{label}-{suffix}@example.com",
            "password": secrets.token_urlsafe(18),
            "tenantName": f"E2E {label} {suffix}",
            "tenantSlug": f"e2e-{label}-{suffix}",
        },
    )
    if status != 201:
        raise RuntimeError(f"register {label}: {status} {body}")
    return body  # type: ignore[no-any-return]


def scenario(api: Api, host: str, port: int, secret: str, env_file: Path) -> None:
    print("Scenario", flush=True)
    tenant_a = register(api, "a")
    token = tenant_a["accessToken"]
    check("register tenant A returns tokens", bool(token) and bool(tenant_a.get("tenantId")))

    status, svc = api.call(
        "POST", "/api/v1/services", {"name": "checkout", "environment": "PROD"}, token=token
    )
    check("create service", status == 201, str(status))
    status, metric = api.call(
        "POST",
        f"/api/v1/services/{svc['id']}/metrics",
        {"name": "latency.p95", "unit": "ms", "aggregation": "AVG"},
        token=token,
    )
    check("create metric", status == 201, str(status))
    metric_id = metric["id"]
    status, key = api.call("POST", "/api/v1/api-keys", {"name": "e2e"}, token=token)
    check("create API key", status == 201 and bool(key.get("key")), str(status))

    sse = SseClient(host, port, token)
    sse.start()
    sse.connected.wait(15)
    check(
        "SSE stream opened before ingest",
        sse.status == 200 and sse.content_type.startswith("text/event-stream"),
        f"status={sse.status} type={sse.content_type}",
    )

    rng = random.Random(42)
    end = datetime.now(timezone.utc) - timedelta(seconds=30)
    points = [
        {
            "metricId": metric_id,
            "ts": iso(end - timedelta(minutes=POINTS - 1 - i)),
            "value": round(100 + rng.gauss(0, 1.0), 4),
        }
        for i in range(POINTS)
    ]
    for offset in (6, 5):  # a short, large spike near the end
        points[-offset]["value"] = 400.0 + offset
    status, ing = api.call(
        "POST", "/api/v1/ingest", {"points": points, "source": "e2e"}, headers={"X-Api-Key": key["key"]}
    )
    check(
        f"ingest {POINTS} points",
        status == 202 and ing["accepted"] == POINTS and not ing["rejected"],
        f"{status} {ing if status != 202 else ''}",
    )

    started = time.monotonic()
    deadline = started + DEADLINE_SECONDS
    anomalies: list[dict[str, Any]] = []
    sse_match: dict[str, str] | None = None
    while time.monotonic() < deadline:
        status, page = api.call("GET", f"/api/v1/anomalies?metricId={metric_id}&size=100", token=token)
        if status == 200:
            anomalies = page["items"]
        ids = {a["id"] for a in anomalies}
        sse_match = next(
            (e for e in sse.snapshot() if e.get("event") == "anomaly" and e.get("id") in ids), None
        )
        if anomalies and sse_match:
            break
        time.sleep(2)
    waited = time.monotonic() - started
    check("anomaly listed for the metric within deadline", bool(anomalies), f"after {waited:.0f}s")
    if anomalies:
        top = max(anomalies, key=lambda a: a["score"])
        check(
            "anomalies have a severity and a score in (0,1]",
            all(a["severity"] in ("LOW", "MEDIUM", "HIGH") and 0 < a["score"] <= 1 for a in anomalies),
            f"{len(anomalies)} anomalies, top {top['detector']} {top['severity']} {top['score']:.3f}",
        )
        check(
            "anomalies belong to tenant A and the metric",
            all(a["tenantId"] == tenant_a["tenantId"] and a["metricId"] == metric_id for a in anomalies),
        )
    check(
        "SSE client received an anomaly event with a listed id",
        sse_match is not None,
        f"{len(sse.snapshot())} events",
    )
    if sse_match is not None:
        data = json.loads(sse_match["data"])
        listed = next(a for a in anomalies if a["id"] == sse_match["id"])
        check("SSE payload matches the REST representation", data == listed)
    sse.close()

    print("Isolation and internal security", flush=True)
    tenant_b = register(api, "b")
    status, page_b = api.call("GET", "/api/v1/anomalies?size=100", token=tenant_b["accessToken"])
    check("tenant B lists no anomalies", status == 200 and page_b["items"] == [], str(status))
    if anomalies:
        status, _ = api.call(
            "GET", f"/api/v1/anomalies/{anomalies[0]['id']}", token=tenant_b["accessToken"]
        )
        check("tenant B cannot read tenant A anomaly by id", status == 404, str(status))

    path = "/internal/v1/anomalies/notify"
    ref = anomalies[0] if anomalies else None
    entry = {
        "id": ref["id"] if ref else str(uuid.uuid4()),
        "tenantId": tenant_a["tenantId"],
        "metricId": metric_id,
        "windowStart": ref["windowStart"] if ref else iso(end),
        "windowEnd": ref["windowEnd"] if ref else iso(end),
        "score": ref["score"] if ref else 0.9,
        "detector": ref["detector"] if ref else "ZSCORE",
        "severity": ref["severity"] if ref else "HIGH",
        "createdAt": ref["createdAt"] if ref else iso(end),
    }
    body = json.dumps({"anomalies": [entry]}).encode()
    status, _ = api.call("POST", path, raw=body)
    check("notify without signature is 401", status == 401, str(status))
    status, _ = api.call("POST", path, raw=body, headers={"X-Internal-Signature": "0" * 64})
    check("notify with a wrong signature is 401", status == 401, str(status))
    status, _ = api.call("POST", path, raw=body, token=token)
    check("notify with a JWT only is 401", status == 401, str(status))
    good = {"X-Internal-Signature": sign(secret, body)}
    status1, res1 = api.call("POST", path, raw=body, headers=good)
    status2, res2 = api.call("POST", path, raw=body, headers=good)
    check(
        "notify with a valid signature is 202 and a replay is harmless",
        status1 == 202
        and status2 == 202
        and res1 == res2
        and res1["accepted"] == 1
        and res1["unknown"] == 0,
        f"{status1} {res1} / {status2} {res2}",
    )
    foreign = json.dumps({"anomalies": [{**entry, "tenantId": tenant_b["tenantId"]}]}).encode()
    status, res = api.call(
        "POST", path, raw=foreign, headers={"X-Internal-Signature": sign(secret, foreign)}
    )
    check(
        "notify with a foreign tenant id reports it unknown",
        status == 202 and res["unknown"] == 1,
        str(res),
    )
    status, page = api.call("GET", f"/api/v1/anomalies?metricId={metric_id}&size=100", token=token)
    check(
        "replay did not create anomaly rows",
        status == 200 and page["totalItems"] == len(anomalies),
    )

    print("Database roles", flush=True)
    proc = compose(
        env_file,
        "exec",
        "-T",
        "postgres",
        "sh",
        "-c",
        'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c '
        "\"select distinct usename from pg_stat_activity where usename like 'analytics_%' order by 1\"",
        check_rc=False,
    )
    roles = proc.stdout.split()
    check(
        "analytics connects as analytics_reader and analytics_writer",
        roles == ["analytics_reader", "analytics_writer"],
        str(roles),
    )


def main() -> int:
    if shutil.which("docker") is None:
        print("docker is required", file=sys.stderr)
        return 2
    tmp = Path(tempfile.mkdtemp(prefix="pulseboard-e2e-"))
    env_file = tmp / "e2e.env"
    port = free_port()
    env_values = write_env(env_file, port)
    started = time.monotonic()
    try:
        print(f"Starting stack (project {PROJECT}, api-core on 127.0.0.1:{port})", flush=True)
        up = compose(env_file, "up", "-d", "--build", "--wait", "api-core", "analytics", check_rc=False)
        if up.returncode != 0:
            check("stack comes up healthy", False, (up.stderr or up.stdout)[-1500:])
        else:
            check("stack comes up healthy", True, f"{time.monotonic() - started:.0f}s")
            scenario(
                Api(f"http://127.0.0.1:{port}"),
                "127.0.0.1",
                port,
                env_values["INTERNAL_SHARED_SECRET"],
                env_file,
            )
    except Exception as exc:  # noqa: BLE001
        check("scenario ran to completion", False, f"{type(exc).__name__}: {exc}")
    finally:
        if any(not ok for _, ok, _ in results):
            logs = compose(
                env_file, "logs", "--no-color", "--tail", "60", "api-core", "analytics", check_rc=False
            )
            print("---- service logs (tail) ----\n" + logs.stdout[-6000:], flush=True)
        compose(env_file, "down", "-v", "--remove-orphans", check_rc=False)
        shutil.rmtree(tmp, ignore_errors=True)
    failed = [name for name, ok, _ in results if not ok]
    total = len(results)
    elapsed = time.monotonic() - started
    print(f"\n{'PASS' if not failed else 'FAIL'}: {total - len(failed)}/{total} checks in {elapsed:.0f}s")
    for name in failed:
        print(f"  failed: {name}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
