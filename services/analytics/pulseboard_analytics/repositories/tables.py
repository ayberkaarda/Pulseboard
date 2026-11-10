"""Core table definitions for the tables the service touches.

The schema is owned by the Flyway migrations of api-core; these definitions only describe it.
"""

from sqlalchemy import (
    Column,
    DateTime,
    Double,
    ForeignKey,
    Integer,
    MetaData,
    String,
    Table,
    Uuid,
)
from sqlalchemy.dialects.postgresql import JSONB

metadata = MetaData()

metric_definition = Table(
    "metric_definition",
    metadata,
    Column("id", Uuid, primary_key=True),
    Column("tenant_id", Uuid, nullable=False),
)

metric_point = Table(
    "metric_point",
    metadata,
    Column("metric_id", Uuid, ForeignKey("metric_definition.id"), primary_key=True),
    Column("ts", DateTime(timezone=True), primary_key=True),
    Column("value", Double, nullable=False),
)

anomaly = Table(
    "anomaly",
    metadata,
    Column("id", Uuid, primary_key=True),
    Column("tenant_id", Uuid, nullable=False),
    Column("metric_id", Uuid, nullable=False),
    Column("window_start", DateTime(timezone=True), nullable=False),
    Column("window_end", DateTime(timezone=True), nullable=False),
    Column("score", Double, nullable=False),
    Column("detector", String(16), nullable=False),
    Column("severity", String(8), nullable=False),
    Column("details", JSONB, nullable=False),
    Column("created_at", DateTime(timezone=True), nullable=False),
)

detector_state = Table(
    "detector_state",
    metadata,
    Column("metric_id", Uuid, primary_key=True),
    Column("detector", String(16), primary_key=True),
    Column("params", JSONB, nullable=False),
    Column("trained_at", DateTime(timezone=True), nullable=False),
    Column("version", Integer, nullable=False),
)
