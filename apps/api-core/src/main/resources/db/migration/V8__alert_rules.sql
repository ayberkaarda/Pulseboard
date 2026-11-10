-- Alert rules and the alert events they raise. alert_event doubles as the delivery outbox: a row is inserted PENDING
-- when a rule fires and a dispatcher claims, delivers and finalises it.
--
-- Both tables belong to api-core alone. tenant_id is denormalised from the owning rows and pinned to them by
-- composite foreign keys, like anomaly does for metric_definition.

CREATE TABLE alert_rule (
    id               uuid             PRIMARY KEY,
    tenant_id        uuid             NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    metric_id        uuid             NOT NULL,
    name             varchar(100)     NOT NULL,
    condition        varchar(16)      NOT NULL,
    operator         varchar(3),
    threshold        double precision,
    min_severity     varchar(8),
    cooldown_seconds int              NOT NULL,
    channel          varchar(16)      NOT NULL,
    target           varchar(2048)    NOT NULL,
    enabled          boolean          NOT NULL DEFAULT true,
    created_by       uuid,
    created_at       timestamptz      NOT NULL DEFAULT now(),
    updated_at       timestamptz      NOT NULL DEFAULT now(),
    version          bigint           NOT NULL DEFAULT 0,
    -- Target of the composite foreign key of alert_event.
    CONSTRAINT alert_rule_id_tenant_uk UNIQUE (id, tenant_id),
    CONSTRAINT alert_rule_tenant_name_uk UNIQUE (tenant_id, name),
    CONSTRAINT alert_rule_metric_fk FOREIGN KEY (metric_id, tenant_id)
        REFERENCES metric_definition (id, tenant_id) ON DELETE CASCADE,
    -- A deleted user only clears created_by.
    CONSTRAINT alert_rule_created_by_fk FOREIGN KEY (created_by, tenant_id)
        REFERENCES app_user (id, tenant_id) ON DELETE SET NULL (created_by),
    CONSTRAINT alert_rule_name_not_blank_ck CHECK (btrim(name) <> ''),
    CONSTRAINT alert_rule_condition_ck CHECK (condition IN ('THRESHOLD', 'ANOMALY_SEVERITY')),
    CONSTRAINT alert_rule_operator_ck CHECK (operator IS NULL OR operator IN ('GT', 'GTE', 'LT', 'LTE')),
    CONSTRAINT alert_rule_min_severity_ck CHECK (min_severity IS NULL OR min_severity IN ('LOW', 'MEDIUM', 'HIGH')),
    -- NaN sorts above every number in PostgreSQL, so the upper bound rejects NaN as well as +/-Infinity.
    CONSTRAINT alert_rule_threshold_finite_ck CHECK (
        threshold IS NULL OR (threshold > '-Infinity'::double precision AND threshold < 'Infinity'::double precision)),
    -- The fields of the other condition stay empty, so a row never carries a meaningless half rule.
    CONSTRAINT alert_rule_condition_fields_ck CHECK (
        (condition = 'THRESHOLD' AND operator IS NOT NULL AND threshold IS NOT NULL AND min_severity IS NULL)
        OR (condition = 'ANOMALY_SEVERITY' AND operator IS NULL AND threshold IS NULL AND min_severity IS NOT NULL)),
    CONSTRAINT alert_rule_cooldown_ck CHECK (cooldown_seconds BETWEEN 60 AND 86400),
    CONSTRAINT alert_rule_channel_ck CHECK (channel IN ('WEBHOOK', 'EMAIL_LOG')),
    CONSTRAINT alert_rule_target_ck CHECK (btrim(target) <> '' AND char_length(target) <= 2048),
    CONSTRAINT alert_rule_version_ck CHECK (version >= 0)
);

-- Evaluation looks up the enabled rules of a metric.
CREATE INDEX alert_rule_metric_enabled_idx ON alert_rule (metric_id, condition) WHERE enabled;
CREATE INDEX alert_rule_tenant_idx ON alert_rule (tenant_id, created_at, id);

COMMENT ON TABLE alert_rule IS 'Alert rule of a tenant on one metric: a threshold or a minimum anomaly severity.';
COMMENT ON COLUMN alert_rule.target IS 'Webhook URL or e-mail label. Sensitive: never written to logs in full.';

CREATE TABLE alert_event (
    id               uuid         PRIMARY KEY,
    tenant_id        uuid         NOT NULL,
    rule_id          uuid         NOT NULL,
    triggered_at     timestamptz  NOT NULL,
    payload          jsonb        NOT NULL,
    delivery_status  varchar(8)   NOT NULL DEFAULT 'PENDING',
    attempts         int          NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz  NOT NULL,
    -- Lease of the dispatcher that claimed the row; a crashed dispatcher's claim simply runs out.
    claimed_until    timestamptz,
    last_error       varchar(500),
    delivered_at     timestamptz,
    -- What caused the event: the timestamp of the metric point or the id of the anomaly.
    source_ref       varchar(100) NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT alert_event_rule_fk FOREIGN KEY (rule_id, tenant_id)
        REFERENCES alert_rule (id, tenant_id) ON DELETE CASCADE,
    -- The same trigger can never raise two events for one rule (replays, retried batches).
    CONSTRAINT alert_event_rule_source_uk UNIQUE (rule_id, source_ref),
    CONSTRAINT alert_event_status_ck CHECK (delivery_status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT alert_event_attempts_ck CHECK (attempts >= 0),
    CONSTRAINT alert_event_source_ref_ck CHECK (btrim(source_ref) <> ''),
    CONSTRAINT alert_event_payload_object_ck CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT alert_event_delivered_ck CHECK ((delivery_status = 'SENT') = (delivered_at IS NOT NULL))
);

-- Tenant history, newest first; the id breaks ties so pages are stable.
CREATE INDEX alert_event_tenant_time_idx ON alert_event (tenant_id, triggered_at DESC, id DESC);
CREATE INDEX alert_event_rule_time_idx ON alert_event (rule_id, triggered_at DESC, id DESC);
-- The dispatcher's scan of events that are due.
CREATE INDEX alert_event_pending_due_idx ON alert_event (next_attempt_at, id) WHERE delivery_status = 'PENDING';

COMMENT ON TABLE alert_event IS 'An alert that was raised, with its delivery state (outbox).';

-- Least privilege: the analytics roles get nothing on these tables.
REVOKE ALL ON alert_rule, alert_event FROM PUBLIC, analytics_reader, analytics_writer;
