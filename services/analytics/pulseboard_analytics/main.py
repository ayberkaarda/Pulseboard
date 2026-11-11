"""HTTP application, health endpoint and scheduler lifecycle."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any, Literal

from fastapi import FastAPI
from pydantic import BaseModel
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from pulseboard_analytics import __version__, metrics
from pulseboard_analytics.api.internal import router as internal_router
from pulseboard_analytics.logging_setup import configure_logging
from pulseboard_analytics.runtime import Runtime
from pulseboard_analytics.settings import Settings

SCORE_PATH = "/internal/v1/score"


class ScoreRequestMetrics:
    """Counts responses of the score endpoint by status; the only label is the status code."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or scope["path"] != SCORE_PATH:
            await self.app(scope, receive, send)
            return
        status = 500

        async def counting_send(message: Message) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = int(message["status"])
            await send(message)

        try:
            await self.app(scope, receive, counting_send)
        finally:
            metrics.score_requests.labels(status=str(status)).inc()


class HealthResponse(BaseModel):
    """Service liveness and installed package version."""

    status: Literal["ok"]
    version: str


def create_app(settings: Settings | None = None) -> FastAPI:
    """Build the application.

    Settings come from the environment when not given; the service refuses to start (the
    lifespan raises) when they are missing or the shared secret is too short.
    """

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        configure_logging()
        runtime = Runtime.build(settings if settings is not None else Settings.from_env())
        app.state.runtime = runtime
        try:
            runtime.verify_roles()
            runtime.start()
            yield
        finally:
            runtime.stop()

    app = FastAPI(title="Pulseboard Analytics", version=__version__, lifespan=lifespan)
    app.add_middleware(ScoreRequestMetrics)
    app.include_router(internal_router)

    @app.get("/health", response_model=HealthResponse, tags=["health"])
    def health() -> HealthResponse:
        """Return liveness without requiring external services."""
        return HealthResponse(status="ok", version=__version__)

    original_openapi = app.openapi

    def openapi_with_security() -> dict[str, Any]:
        schema = original_openapi()
        components = schema.setdefault("components", {})
        components.setdefault("securitySchemes", {})["InternalHmac"] = {
            "type": "apiKey",
            "in": "header",
            "name": "X-Internal-Signature",
            "description": (
                "Lowercase hex HMAC-SHA256 of the raw request body, "
                "keyed with INTERNAL_SHARED_SECRET."
            ),
        }
        return schema

    app.openapi = openapi_with_security  # type: ignore[method-assign]
    return app
