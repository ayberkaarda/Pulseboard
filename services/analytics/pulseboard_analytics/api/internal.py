"""Internal endpoints guarded by the shared-secret HMAC."""

from typing import Any
from uuid import UUID

from fastapi import APIRouter, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from pulseboard_analytics.detectors.base import DetectorResult
from pulseboard_analytics.runtime import Runtime
from pulseboard_analytics.security import SIGNATURE_HEADER, verify

router = APIRouter(prefix="/internal/v1", tags=["internal"])


class ScoreRequest(BaseModel):
    """Body of ``POST /internal/v1/score``."""

    model_config = ConfigDict(extra="forbid", populate_by_name=False)

    metric_id: UUID = Field(alias="metricId")


class ScoreResponse(BaseModel):
    """Detector output for one metric and the number of newly stored anomalies."""

    results: list[DetectorResult]
    inserted: int


async def _read_limited_body(request: Request, limit: int) -> bytes:
    declared = request.headers.get("content-length")
    if declared is not None and declared.isdigit() and int(declared) > limit:
        raise HTTPException(status_code=413, detail="request body too large")
    chunks: list[bytes] = []
    size = 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > limit:
            raise HTTPException(status_code=413, detail="request body too large")
        chunks.append(chunk)
    return b"".join(chunks)


@router.post(
    "/score",
    response_model=ScoreResponse,
    responses={
        401: {"description": "Missing or invalid X-Internal-Signature"},
        404: {"description": "Unknown metric"},
        413: {"description": "Request body too large"},
        422: {"description": "Malformed request body"},
    },
    openapi_extra={
        "security": [{"InternalHmac": []}],
        "requestBody": {
            "required": True,
            "content": {
                "application/json": {"schema": ScoreRequest.model_json_schema()},
            },
        },
    },
)
async def score_metric(request: Request) -> ScoreResponse:
    """Score one metric over its last 24 hours, store new anomalies and notify api-core."""
    runtime: Runtime = request.app.state.runtime
    body = await _read_limited_body(request, runtime.settings.max_request_bytes)
    signatures = request.headers.getlist(SIGNATURE_HEADER)
    # Exactly one signature header is accepted, like api-core; duplicates are ambiguous.
    signature = signatures[0] if len(signatures) == 1 else None
    if not verify(runtime.settings.internal_shared_secret, body, signature):
        raise HTTPException(status_code=401, detail="invalid signature")
    try:
        payload = ScoreRequest.model_validate_json(body)
    except ValidationError as error:
        errors: list[Any] = error.errors(include_url=False, include_context=False)
        raise RequestValidationError(errors) from None

    metric = await run_in_threadpool(runtime.reader.get_metric, payload.metric_id)
    if metric is None:
        raise HTTPException(status_code=404, detail="metric not found")
    outcome = await run_in_threadpool(runtime.scorer.score_metric, metric)
    if outcome.inserted:
        await run_in_threadpool(runtime.notifier.notify, outcome.inserted)
    return ScoreResponse(results=outcome.results, inserted=len(outcome.inserted))
