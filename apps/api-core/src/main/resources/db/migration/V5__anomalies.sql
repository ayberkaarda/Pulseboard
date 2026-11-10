-- Anomalies found by the analytics service, and the model state its detectors persist.
--
-- Rows are written by analytics_writer (INSERT only for anomaly, so a finding can never be rewritten or removed by
-- that role); api-core only reads them and records the acknowledgement. tenant_id is denormalised from the metric and
-- pinned to it by a composite foreign key, like metric_definition does for service.

-- Target of the composite foreign key below.
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
    -- One finding per metric, detector and window: the key analytics uses to insert idempotently.
    CONSTRAINT anomaly_window_uk UNIQUE (metric_id, detector, window_start, window_end),
    CONSTRAINT anomaly_metric_fk FOREIGN KEY (metric_id, tenant_id)
        REFERENCES metric_definition (id, tenant_id) ON DELETE CASCADE,
    -- A deleted user only clears acknowledged_by; the tenant and the acknowledgement time stay.
    CONSTRAINT anomaly_acknowledged_by_fk FOREIGN KEY (acknowledged_by, tenant_id)
        REFERENCES app_user (id, tenant_id) ON DELETE SET NULL (acknowledged_by),
    CONSTRAINT anomaly_window_ck CHECK (window_end >= window_start),
    CONSTRAINT anomaly_score_ck CHECK (score >= 0 AND score <= 1),
    CONSTRAINT anomaly_detector_ck CHECK (detector IN ('ZSCORE', 'ISOLATION_FOREST')),
    CONSTRAINT anomaly_severity_ck CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT anomaly_details_object_ck CHECK (jsonb_typeof(details) = 'object'),
    CONSTRAINT anomaly_acknowledgement_ck CHECK (acknowledged_by IS NULL OR acknowledged_at IS NOT NULL)
);

-- Tenant listing, newest window first; the id breaks ties so pages are stable.
CREATE INDEX anomaly_tenant_window_idx ON anomaly (tenant_id, window_start DESC, id DESC);
CREATE INDEX anomaly_metric_window_idx ON anomaly (metric_id, window_start);
CREATE INDEX anomaly_tenant_unacknowledged_idx ON anomaly (tenant_id, window_start DESC, id DESC)
    WHERE acknowledged_at IS NULL;

COMMENT ON TABLE anomaly IS
    'Anomaly window scored by a detector. Inserted by analytics_writer, acknowledged through api-core.';

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

COMMENT ON TABLE detector_state IS 'Persisted detector parameters per metric, upserted by analytics_writer.';

-- Least privilege. V4 already restricted metric_point; nothing is granted implicitly to PUBLIC (default
-- privileges of the schema owner), and the revokes make the intended final state explicit.
REVOKE ALL ON anomaly, detector_state, metric_definition FROM PUBLIC, analytics_reader, analytics_writer;

-- Metric selection joins metric_point with metric_definition (tenant_id) and reads nothing else.
GRANT SELECT ON metric_definition TO analytics_reader;

-- Anomalies are insert-only for the writer (SELECT is needed for ON CONFLICT ... RETURNING); model state is upserted.
GRANT SELECT, INSERT ON anomaly TO analytics_writer;
GRANT SELECT, INSERT, UPDATE ON detector_state TO analytics_writer;
