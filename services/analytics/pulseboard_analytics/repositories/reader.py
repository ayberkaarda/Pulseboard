"""Read-only queries (``analytics_reader``)."""

import math
from dataclasses import dataclass
from datetime import datetime
from uuid import UUID

import pandas as pd
import structlog
from sqlalchemy import Engine, exists, select, text

from pulseboard_analytics.budget import Budget
from pulseboard_analytics.repositories.models import MetricRef
from pulseboard_analytics.repositories.tables import metric_definition, metric_point
from pulseboard_analytics.repositories.timeouts import apply_budget

MAX_WINDOW_POINTS = 20_000

_BUCKETED = text(
    """
    WITH w AS (
        SELECT ts, value, floor(extract(epoch FROM ts) / :width) AS b
        FROM metric_point
        WHERE metric_id = :m AND ts >= :since AND ts <= :until
    ), g AS MATERIALIZED (
        SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY value) AS med FROM w
    ), s AS MATERIALIZED (
        SELECT b, percentile_cont(0.5) WITHIN GROUP (ORDER BY value) AS med, count(*) AS n
        FROM w GROUP BY b
    )
    SELECT DISTINCT ON (w.b) w.ts, w.value, s.n
    FROM w JOIN s ON s.b = w.b CROSS JOIN g
    ORDER BY w.b, abs(w.value - s.med) DESC, abs(w.value - g.med) DESC, w.ts
    """
)

_log = structlog.get_logger(__name__)


@dataclass(frozen=True)
class SeriesWindow:
    """A loaded window: the frame, the raw observation count and the bucket width, if any."""

    frame: pd.DataFrame
    raw_count: int
    bucket_seconds: int | None


class MetricReader:
    """Loads metric catalogue entries and point windows."""

    def __init__(self, engine: Engine, *, max_points: int = MAX_WINDOW_POINTS) -> None:
        self._engine = engine
        if max_points < 2:
            raise ValueError("max_points must be at least 2")
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

    def load_series(
        self, metric_id: UUID, since: datetime, until: datetime, budget: Budget | None = None
    ) -> pd.DataFrame:
        """The frame of :meth:`load_window`."""
        return self.load_window(metric_id, since, until, budget).frame

    def load_window(
        self, metric_id: UUID, since: datetime, until: datetime, budget: Budget | None = None
    ) -> SeriesWindow:
        """Points with ``since <= ts <= until``, ascending by ts, from one consistent snapshot.

        The window is read inside one ``REPEATABLE READ`` read-only transaction. The first
        statement asks for at most ``max_points + 1`` raw rows, so memory stays bounded even when
        points are committed concurrently; if no more than ``max_points`` come back they are the
        window. Otherwise the same snapshot is queried again with a bucketed statement: fixed
        epoch-aligned buckets whose width is the smallest whole number of seconds that makes the
        window fit into ``max_points`` buckets. A bucket is represented by its real raw point
        farthest from the bucket median (ties, e.g. in a two-point bucket, go to the point farther
        from the median of the whole window, then to the earliest), with that point's own
        timestamp and value, so a short spike inside a dense bucket is neither averaged away nor
        moved in time. The frame then carries a ``raw_points`` column with the number of raw
        observations behind every row, and the window reports the bucket width. Because the
        detectors see one extreme per bucket, baseline lengths (288 points, 12-point rolling
        features) are counted in buckets, not in raw observations. Taking the signed extreme also
        widens the spread the detectors see, so only clearly anomalous buckets stand out.
        """
        in_window = (
            metric_point.c.metric_id == metric_id,
            metric_point.c.ts >= since,
            metric_point.c.ts <= until,
        )
        raw_query = (
            select(metric_point.c.ts, metric_point.c.value)
            .where(*in_window)
            .order_by(metric_point.c.ts)
            .limit(self._max_points + 1)
        )
        width = max(1, math.ceil((until - since).total_seconds() / (self._max_points - 1)))
        with self._engine.connect().execution_options(
            isolation_level="REPEATABLE READ", postgresql_readonly=True
        ) as conn:
            apply_budget(conn, budget)
            raw = conn.execute(raw_query).all()
            if len(raw) <= self._max_points:
                frame = _frame([row.ts for row in raw], [row.value for row in raw])
                return SeriesWindow(frame=frame, raw_count=len(raw), bucket_seconds=None)
            apply_budget(conn, budget)
            rows = conn.execute(
                _BUCKETED,
                {"m": metric_id, "since": since, "until": until, "width": width},
            ).all()
        self.downsampled_windows += 1
        frame = _frame([row.ts for row in rows], [row.value for row in rows])
        frame["raw_points"] = pd.array([int(row.n) for row in rows], dtype="int64")
        raw_count = int(frame["raw_points"].sum())
        _log.warning(
            "window_downsampled",
            metric_id=str(metric_id),
            points=raw_count,
            buckets=len(rows),
            bucket_seconds=width,
        )
        return SeriesWindow(frame=frame, raw_count=raw_count, bucket_seconds=width)


def _frame(ts: list[datetime], values: list[float]) -> pd.DataFrame:
    return pd.DataFrame(
        {
            "ts": pd.to_datetime(ts, utc=True),
            "value": pd.array([float(v) for v in values], dtype="float64"),
        }
    )
