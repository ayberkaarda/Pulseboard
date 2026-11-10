"""Plain records passed between repositories, jobs and the notify client."""

from dataclasses import dataclass
from datetime import datetime
from uuid import UUID


@dataclass(frozen=True)
class MetricRef:
    """A metric together with the tenant that owns it."""

    id: UUID
    tenant_id: UUID


@dataclass(frozen=True)
class StoredAnomaly:
    """An anomaly row as inserted by this service."""

    id: UUID
    tenant_id: UUID
    metric_id: UUID
    window_start: datetime
    window_end: datetime
    score: float
    detector: str
    severity: str
    created_at: datetime
