"""Read-only queries (``analytics_reader``)."""

import math
from datetime import datetime
from uuid import UUID

import pandas as pd
import structlog
from sqlalchemy import Engine, exists, func, select

from pulseboard_analytics.repositories.models import MetricRef
from pulseboard_analytics.repositories.tables import metric_definition, metric_point

MAX_WINDOW_POINTS = 20_000

_log = structlog.get_logger(__name__)


class MetricReader:
    """Loads metric catalogue entries and point windows."""

    def __init__(self, engine: Engine, *, max_points: int = MAX_WINDOW_POINTS) -> None:
        self._engine = engine
        self._max_points = max_points
        self.downsampled_windows = 0

    def get_metric(self, metric_id: UUID) -> MetricRef | None:
        """Return the metric or ``None`` when it does not exist."""
        stmt = select(metric_definition.c.id, metric_definition.c.tenant_id).where(
            metric_definition.c.id == metric_id
        )
        with self._engine.connect() as conn:
            row = conn.execute(stmt).one_or_none()
        return None if row is None else MetricRef(id=row.id, tenant_id=row.tenant_id)

    def active_metrics(self, since: datetime, until: datetime) -> list[MetricRef]:
        """Metrics with at least one point in ``[since, until]``; later points do not count."""
        has_point = exists().where(
            metric_point.c.metric_id == metric_definition.c.id,
            metric_point.c.ts >= since,
            metric_point.c.ts <= until,
        )
        stmt = (
            select(metric_definition.c.id, metric_definition.c.tenant_id)
            .where(has_point)
            .order_by(metric_definition.c.id)
        )
        with self._engine.connect() as conn:
            rows = conn.execute(stmt).all()
        return [MetricRef(id=row.id, tenant_id=row.tenant_id) for row in rows]

    def load_series(self, metric_id: UUID, since: datetime, until: datetime) -> pd.DataFrame:
        """Points with ``since <= ts <= until``, ascending by ts.

        A window with more than ``max_points`` points is downsampled deterministically: the
        points are grouped into fixed epoch-aligned buckets, each bucket becomes one point (its
        first timestamp, the mean of its values), and the bucket width is the smallest whole
        number of seconds for which the whole window fits into ``max_points`` buckets. The whole
        window stays covered, so late points are never cut off; a bucket mean dilutes a spike
        only by the number of points sharing its bucket.
        """
        in_window = (
            metric_point.c.metric_id == metric_id,
            metric_point.c.ts >= since,
            metric_point.c.ts <= until,
        )
        with self._engine.connect() as conn:
            total = conn.execute(select(func.count()).where(*in_window)).scalar_one()
            if total <= self._max_points:
                raw = conn.execute(
                    select(metric_point.c.ts, metric_point.c.value)
                    .where(*in_window)
                    .order_by(metric_point.c.ts)
                ).all()
                return _frame([row.ts for row in raw], [row.value for row in raw])

            width = max(1, math.ceil((until - since).total_seconds() / self._max_points))
            bucket = func.floor(func.extract("epoch", metric_point.c.ts) / width).label("bucket")
            rows = conn.execute(
                select(
                    func.min(metric_point.c.ts).label("ts"),
                    func.avg(metric_point.c.value).label("value"),
                )
                .where(*in_window)
                .group_by(bucket)
                .order_by(bucket)
            ).all()
        self.downsampled_windows += 1
        _log.warning(
            "window_downsampled",
            metric_id=str(metric_id),
            points=total,
            buckets=len(rows),
            bucket_seconds=width,
        )
        return _frame([row.ts for row in rows], [row.value for row in rows])


def _frame(ts: list[datetime], values: list[float]) -> pd.DataFrame:
    return pd.DataFrame(
        {
            "ts": pd.to_datetime(ts, utc=True),
            "value": pd.array([float(v) for v in values], dtype="float64"),
        }
    )
