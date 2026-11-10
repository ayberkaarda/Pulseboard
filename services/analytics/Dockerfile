FROM ghcr.io/astral-sh/uv:0.13.7 AS uv

FROM python:3.14.10-slim-trixie AS builder

COPY --from=uv /uv /uvx /bin/
ENV UV_LINK_MODE=copy \
    UV_COMPILE_BYTECODE=1 \
    UV_PYTHON_DOWNLOADS=never
WORKDIR /app

COPY pyproject.toml uv.lock README.md ./
COPY pulseboard_analytics ./pulseboard_analytics
RUN uv sync --locked --no-dev --no-editable --python /usr/local/bin/python

FROM python:3.14.10-slim-trixie AS runtime

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1
WORKDIR /app

RUN groupadd --gid 10001 analytics \
    && useradd --uid 10001 --gid analytics --no-create-home --shell /usr/sbin/nologin analytics
COPY --from=builder --chown=10001:10001 /app/.venv /app/.venv

USER 10001:10001
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3).close()"]
CMD ["uvicorn", "pulseboard_analytics.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]
