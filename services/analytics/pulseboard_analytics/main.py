"""HTTP application, health endpoint and scheduler lifecycle."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any, Literal

from fastapi import FastAPI
from pydantic import BaseModel

from pulseboard_analytics import __version__
from pulseboard_analytics.api.internal import router as internal_router
from pulseboard_analytics.logging_setup import configure_logging
from pulseboard_analytics.runtime import Runtime
from pulseboard_analytics.settings import Settings


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
        runtime.start()
        try:
            yield
        finally:
            runtime.stop()

    app = FastAPI(title="Pulseboard Analytics", version=__version__, lifespan=lifespan)
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
