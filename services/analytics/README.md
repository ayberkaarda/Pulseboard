# Pulseboard Analytics

Phase 0 foundation for the Python 3.12 analytics service. The application factory
exposes `GET /health`, returning `{"status": "ok", "version": "0.1.0"}`. The version
is defined in `pulseboard_analytics/__init__.py` and used by package metadata and
the HTTP application. Health requires no database or Redis connection.

Run these commands from this service directory with Python 3.12 and uv installed:

```sh
uv sync
uv run ruff check .
uv run mypy --strict pulseboard_analytics
uv run pytest
uv run uvicorn pulseboard_analytics.main:create_app --factory --host 0.0.0.0 --port 8000
```

Build and run the multi-stage image from this directory:

```sh
docker build -t pulseboard-analytics .
docker run --rm -p 8000:8000 pulseboard-analytics
```

The image uses uv 0.8.22 and Python 3.12, installs runtime dependencies only, runs
as UID/GID 10001, and checks `/health` on port 8000. Dependencies resolve from the
bounded ranges in `pyproject.toml`; the first `uv sync` creates a local lockfile.
The image resolves dependencies during its build independently of that lockfile.
Database schema changes remain owned by the core service's Flyway migrations.
