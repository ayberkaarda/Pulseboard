"""Write queries (``analytics_writer``): anomalies and detector state."""

from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy import Engine, select
from sqlalchemy.dialects.postgresql import insert

from pulseboard_analytics.detectors.base import Detector, DetectorResult
from pulseboard_analytics.detectors.isolation_forest import ForestState
from pulseboard_analytics.repositories.models import MetricRef, StoredAnomaly
from pulseboard_analytics.repositories.tables import anomaly, detector_state


class AnomalyWriter:
    """Persists detector output idempotently."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def insert_new(self, metric: MetricRef, results: list[DetectorResult]) -> list[StoredAnomaly]:
        """Insert results, skipping rows that already exist; returns only the new rows."""
        if not results:
            return []
        created_at = datetime.now(UTC)
        rows = [
            {
                "id": uuid4(),
                "tenant_id": metric.tenant_id,
                "metric_id": metric.id,
                "window_start": result.window_start,
                "window_end": result.window_end,
                "score": result.score,
                "detector": result.detector.value,
                "severity": result.severity.value,
                "details": result.details,
                "created_at": created_at,
            }
            for result in results
        ]
        stmt = (
            insert(anomaly)
            .on_conflict_do_nothing()
            .returning(
                anomaly.c.id,
                anomaly.c.tenant_id,
                anomaly.c.metric_id,
                anomaly.c.window_start,
                anomaly.c.window_end,
                anomaly.c.score,
                anomaly.c.detector,
                anomaly.c.severity,
                anomaly.c.created_at,
            )
        )
        with self._engine.begin() as conn:
            inserted = conn.execute(stmt, rows).all()
        return [
            StoredAnomaly(
                id=row.id,
                tenant_id=row.tenant_id,
                metric_id=row.metric_id,
                window_start=row.window_start,
                window_end=row.window_end,
                score=row.score,
                detector=row.detector,
                severity=row.severity,
                created_at=row.created_at,
            )
            for row in inserted
        ]


class DetectorStateStore:
    """Persists model metadata per metric and detector."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def get(self, metric_id: UUID, detector: Detector) -> ForestState | None:
        """Return the persisted state or ``None``."""
        stmt = select(detector_state.c.params, detector_state.c.trained_at).where(
            detector_state.c.metric_id == metric_id,
            detector_state.c.detector == detector.value,
        )
        with self._engine.connect() as conn:
            row = conn.execute(stmt).one_or_none()
        if row is None:
            return None
        params = row.params
        return ForestState(
            trained_at=row.trained_at,
            version=int(params["version"]),
            contamination=float(params["contamination"]),
            point_count=int(params["point_count"]),
        )

    def upsert(self, metric_id: UUID, detector: Detector, state: ForestState) -> bool:
        """Insert the state of ``(metric_id, detector)`` or replace an older version of it.

        The write is monotonic: a row whose stored version is equal or newer is left untouched.
        Returns whether the state was written.
        """
        params = {
            "trained_at": state.trained_at.isoformat(),
            "version": state.version,
            "contamination": state.contamination,
            "point_count": state.point_count,
        }
        stmt = insert(detector_state).values(
            metric_id=metric_id,
            detector=detector.value,
            params=params,
            trained_at=state.trained_at,
            version=state.version,
        )
        upsert = stmt.on_conflict_do_update(
            index_elements=[detector_state.c.metric_id, detector_state.c.detector],
            set_={
                "params": stmt.excluded.params,
                "trained_at": stmt.excluded.trained_at,
                "version": stmt.excluded.version,
            },
            where=detector_state.c.version < stmt.excluded.version,
        ).returning(detector_state.c.version)
        with self._engine.begin() as conn:
            return conn.execute(upsert).first() is not None
