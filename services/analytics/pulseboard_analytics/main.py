"""HTTP application and service health endpoint."""

from typing import Literal

from fastapi import FastAPI
from pydantic import BaseModel

from pulseboard_analytics import __version__


class HealthResponse(BaseModel):
    """Service liveness and installed package version."""

    status: Literal["ok"]
    version: str


def create_app() -> FastAPI:
    """Build the Phase 0 HTTP application."""
    app = FastAPI(title="Pulseboard Analytics", version=__version__)

    @app.get("/health", response_model=HealthResponse, tags=["health"])
    def health() -> HealthResponse:
        """Return liveness without requiring external services."""
        return HealthResponse(status="ok", version=__version__)

    return app
