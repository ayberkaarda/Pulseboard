"""Test database schema.

The anomaly and detector_state definitions, their constraints and the grants for
analytics_reader / analytics_writer mirror apps/api-core/src/main/resources/db/migration/
V5__anomalies.sql. The tenant, app_user, metric_definition and metric_point tables are reduced
to the columns the analytics service touches (V1 to V4 define them in full). Keep this file in
step with the migration.
"""

from sqlalchemy import Engine, text

ROLES = """
CREATE ROLE analytics_reader LOGIN PASSWORD 'reader-secret';
CREATE ROLE analytics_writer LOGIN PASSWORD 'writer-secret';
"""

BASE_TABLES = """
CREATE TABLE tenant (id uuid PRIMARY KEY);

CREATE TABLE app_user (
    id        uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    CONSTRAINT app_user_id_tenant_uk UNIQUE (id, tenant_id)
);

CREATE TABLE metric_definition (
    id        uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id) ON DELETE CASCADE
);

CREATE TABLE metric_point (
    metric_id uuid             NOT NULL REFERENCES metric_definition (id) ON DELETE CASCADE,
    ts        timestamptz      NOT NULL,
    value     double precision NOT NULL,
    CONSTRAINT metric_point_pk PRIMARY KEY (metric_id, ts)
) PARTITION BY RANGE (ts);

CREATE TABLE metric_point_all PARTITION OF metric_point
    FOR VALUES FROM ('2000-01-01') TO ('2100-01-01');

REVOKE ALL ON metric_point FROM analytics_reader, analytics_writer;
GRANT SELECT ON metric_point TO analytics_reader;
"""

V5_MIRROR = """
ALTER TABLE metric_definition ADD CONSTRAINT metric_definition_id_tenant_uk UNIQUE (id, tenant_id);

CREATE TABLE anomaly (
    id              uuid             PRIMARY KEY,
    tenant_id       uuid             NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    metric_id       uuid             NOT NULL,
    window_start    timestamptz      NOT NULL,
    window_end      timestamptz      NOT NULL,
    score           double precision NOT NULL,
    detector        varchar(16)      NOT NULL,
    severity        varchar(8)       NOT NULL,
    details         jsonb            NOT NULL DEFAULT '{}',
    created_at      timestamptz      NOT NULL DEFAULT now(),
    acknowledged_by uuid,
    acknowledged_at timestamptz,
    CONSTRAINT anomaly_window_uk UNIQUE (metric_id, detector, window_start, window_end),
    CONSTRAINT anomaly_metric_fk FOREIGN KEY (metric_id, tenant_id)
        REFERENCES metric_definition (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT anomaly_acknowledged_by_fk FOREIGN KEY (acknowledged_by, tenant_id)
        REFERENCES app_user (id, tenant_id) ON DELETE SET NULL (acknowledged_by),
    CONSTRAINT anomaly_window_ck CHECK (window_end >= window_start),
    CONSTRAINT anomaly_score_ck CHECK (score >= 0 AND score <= 1),
    CONSTRAINT anomaly_detector_ck CHECK (detector IN ('ZSCORE', 'ISOLATION_FOREST')),
    CONSTRAINT anomaly_severity_ck CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT anomaly_details_object_ck CHECK (jsonb_typeof(details) = 'object'),
    CONSTRAINT anomaly_acknowledgement_ck
        CHECK (acknowledged_by IS NULL OR acknowledged_at IS NOT NULL)
);

CREATE TABLE detector_state (
    metric_id  uuid        NOT NULL REFERENCES metric_definition (id) ON DELETE CASCADE,
    detector   varchar(16) NOT NULL,
    params     jsonb       NOT NULL,
    trained_at timestamptz NOT NULL,
    version    int         NOT NULL,
    CONSTRAINT detector_state_pk PRIMARY KEY (metric_id, detector),
    CONSTRAINT detector_state_detector_ck CHECK (detector IN ('ZSCORE', 'ISOLATION_FOREST')),
    CONSTRAINT detector_state_version_ck CHECK (version >= 1)
);

REVOKE ALL ON anomaly, detector_state, metric_definition
    FROM PUBLIC, analytics_reader, analytics_writer;
GRANT SELECT ON metric_definition TO analytics_reader;
GRANT SELECT, INSERT ON anomaly TO analytics_writer;
GRANT SELECT, INSERT, UPDATE ON detector_state TO analytics_writer;
"""


def create_schema(owner: Engine) -> None:
    """Create roles, tables and grants on an empty database."""
    with owner.begin() as conn:
        for script in (ROLES, BASE_TABLES, V5_MIRROR):
            conn.exec_driver_sql(script)


def reset_data(owner: Engine) -> None:
    """Remove all rows between tests."""
    with owner.begin() as conn:
        conn.execute(text("TRUNCATE tenant CASCADE"))
