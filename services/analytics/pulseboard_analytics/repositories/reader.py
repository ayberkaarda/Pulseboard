"""Read-only queries (``analytics_reader``)."""

from datetime import datetime
from uuid import UUID

import pandas as pd
from sqlalchemy import Engine, exists, select

from pulseboard_analytics.repositories.models import MetricRef
from pulseboard_analytics.repositories.tables import metric_definition, metric_point

MAX_WINDOW_POINTS = 20_000


class MetricReader:
    """Loads metric catalogue entries and point windows."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def get_metric(self, metric_id: UUID) -> MetricRef | None:
        """Return the metric or ``None`` when it does not exist."""
        stmt = select(metric_definition.c.id, metric_definition.c.tenant_id).where(
            metric_definition.c.id == metric_id
        )
        with self._engine.connect() as conn:
            row = conn.execute(stmt).one_or_none()
        return None if row is None else MetricRef(id=row.id, tenant_id=row.tenant_id)

    def active_metrics(self, since: datetime) -> list[MetricRef]:
        """Metrics with at least one point at or after ``since``."""
        has_point = exists().where(
            metric_point.c.metric_id == metric_definition.c.id, metric_point.c.ts >= since
        )
        stmt = (
            select(metric_definition.c.id, metric_definition.c.tenant_id)
            .where(has_point)
            .order_by(metric_definition.c.id)
        )
        with self._engine.connect() as conn:
            rows = conn.execute(stmt).all()
        return [MetricRef(id=row.id, tenant_id=row.tenant_id) for row in rows]

    def load_series(self, metric_id: UUID, since: datetime) -> pd.DataFrame:
        """Points at or after ``since`` (newest ``MAX_WINDOW_POINTS`` at most), ascending by ts."""
        stmt = (
            select(metric_point.c.ts, metric_point.c.value)
            .where(metric_point.c.metric_id == metric_id, metric_point.c.ts >= since)
            .order_by(metric_point.c.ts.desc())
            .limit(MAX_WINDOW_POINTS)
        )
        with self._engine.connect() as conn:
            rows = conn.execute(stmt).all()
        ordered = list(reversed(rows))
        ts = pd.to_datetime([row.ts for row in ordered], utc=True)
        values = pd.array([float(row.value) for row in ordered], dtype="float64")
        return pd.DataFrame({"ts": ts, "value": values})
