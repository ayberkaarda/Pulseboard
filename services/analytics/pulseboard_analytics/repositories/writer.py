"""Write queries (``analytics_writer``): anomalies and detector state."""

from datetime import UTC, datetime
from typing import Any
from uuid import UUID, uuid4

from sqlalchemy import Engine, select, update
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.sql.dml import ReturningInsert, ReturningUpdate

from pulseboard_analytics.budget import Budget
from pulseboard_analytics.detectors.base import Detector, DetectorResult
from pulseboard_analytics.detectors.isolation_forest import ForestState
from pulseboard_analytics.repositories.connections import begin, connect
from pulseboard_analytics.repositories.models import MetricRef, StoredAnomaly
from pulseboard_analytics.repositories.tables import anomaly, detector_state
from pulseboard_analytics.repositories.timeouts import apply_budget

INSERT_CHUNK = 1000


class AnomalyWriter:
    """Persists detector output idempotently."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def insert_new(
        self, metric: MetricRef, results: list[DetectorResult], budget: Budget | None = None
    ) -> list[StoredAnomaly]:
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
        returning = (
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
        inserted: list[Any] = []
        with begin(self._engine, budget) as conn:
            # One multi-row statement per chunk (never an executemany that the driver would
            # split into hidden batches); the timeout is derived again from the budget left
            # before every statement, and a spent budget aborts and rolls the transaction back.
            for start in range(0, len(rows), INSERT_CHUNK):
                apply_budget(conn, budget)
                stmt = (
                    insert(anomaly)
                    .values(rows[start : start + INSERT_CHUNK])
                    .on_conflict_do_nothing()
                    .returning(*returning)
                )
                inserted.extend(conn.execute(stmt).all())
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

    def get(
        self, metric_id: UUID, detector: Detector, budget: Budget | None = None
    ) -> ForestState | None:
        """Return the persisted state or ``None``."""
        stmt = select(detector_state.c.params, detector_state.c.trained_at).where(
            detector_state.c.metric_id == metric_id,
            detector_state.c.detector == detector.value,
        )
        with connect(self._engine, budget) as conn:
            apply_budget(conn, budget)
            row = conn.execute(stmt).one_or_none()
        if row is None:
            return None
        params = row.params
        return ForestState(
            trained_at=row.trained_at,
            version=int(params["version"]),
            contamination=float(params["contamination"]),
            point_count=int(params["point_count"]),
            data_hash=str(params.get("data_hash", "")),
        )

    def compare_and_set(
        self,
        metric_id: UUID,
        detector: Detector,
        state: ForestState,
        *,
        expected_version: int | None,
        expected_trained_at: datetime | None = None,
        budget: Budget | None = None,
    ) -> bool:
        """Persist ``state`` only if the stored row still is the one the caller based it on.

        ``expected_version`` is the version the caller read (``None`` when it found no row).
        With ``None`` the row is inserted unless somebody inserted it first; otherwise the row is
        updated only while its version still equals ``expected_version``. Returns whether this
        call won; a loser must not claim the version it wanted to write. With
        ``expected_trained_at`` the update also requires that training time, so a row that was
        deleted and recreated with the same version number (ABA) is not mistaken for the one the
        caller read.
        """
        params = {
            "trained_at": state.trained_at.isoformat(),
            "version": state.version,
            "contamination": state.contamination,
            "point_count": state.point_count,
            "data_hash": state.data_hash,
        }
        stmt: ReturningInsert[Any] | ReturningUpdate[Any]
        if expected_version is None:
            stmt = (
                insert(detector_state)
                .values(
                    metric_id=metric_id,
                    detector=detector.value,
                    params=params,
                    trained_at=state.trained_at,
                    version=state.version,
                )
                .on_conflict_do_nothing()
                .returning(detector_state.c.version)
            )
        else:
            conditions = [
                detector_state.c.metric_id == metric_id,
                detector_state.c.detector == detector.value,
                detector_state.c.version == expected_version,
            ]
            if expected_trained_at is not None:
                conditions.append(detector_state.c.trained_at == expected_trained_at)
            stmt = (
                update(detector_state)
                .where(*conditions)
                .values(params=params, trained_at=state.trained_at, version=state.version)
                .returning(detector_state.c.version)
            )
        with begin(self._engine, budget) as conn:
            apply_budget(conn, budget)
            return conn.execute(stmt).first() is not None
