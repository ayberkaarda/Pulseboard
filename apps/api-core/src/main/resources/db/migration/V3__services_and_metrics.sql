-- Service catalogue, metric definitions and the monthly range-partitioned metric_point table.

CREATE TABLE service (
    id          uuid         PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    name        varchar(100) NOT NULL,
    environment varchar(16)  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT service_tenant_name_uk UNIQUE (tenant_id, name),
    CONSTRAINT service_id_tenant_uk UNIQUE (id, tenant_id),
    CONSTRAINT service_name_not_blank_ck CHECK (btrim(name) <> ''),
    CONSTRAINT service_environment_ck CHECK (environment IN ('PROD', 'STAGING', 'DEV'))
);

-- tenant_id is denormalised from service and pinned to it by the composite foreign key, so a metric can
-- never belong to a service of another tenant and tenant-scoped lookups need no join.
CREATE TABLE metric_definition (
    id          uuid         PRIMARY KEY,
    tenant_id   uuid         NOT NULL,
    service_id  uuid         NOT NULL,
    name        varchar(100) NOT NULL,
    unit        varchar(32)  NOT NULL,
    aggregation varchar(8)   NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT metric_definition_service_name_uk UNIQUE (service_id, name),
    CONSTRAINT metric_definition_service_fk FOREIGN KEY (service_id, tenant_id)
        REFERENCES service (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT metric_definition_name_format_ck CHECK (name ~ '^[a-z][a-z0-9_.]*$'),
    CONSTRAINT metric_definition_unit_not_blank_ck CHECK (btrim(unit) <> ''),
    CONSTRAINT metric_definition_aggregation_ck CHECK (aggregation IN ('AVG', 'SUM', 'MAX', 'P95'))
);

CREATE INDEX metric_definition_tenant_idx ON metric_definition (tenant_id);

CREATE TABLE metric_point (
    metric_id uuid             NOT NULL REFERENCES metric_definition (id) ON DELETE CASCADE,
    ts        timestamptz      NOT NULL,
    value     double precision NOT NULL,
    CONSTRAINT metric_point_pk PRIMARY KEY (metric_id, ts)
) PARTITION BY RANGE (ts);

COMMENT ON TABLE metric_point IS
    'Time-series samples, range-partitioned by calendar month (UTC). Partitions are created by '
    'ensure_metric_partitions(); there is deliberately no default partition.';

-- Creates the partitions for the current UTC month and the next months_ahead months when missing.
-- Idempotent and safe under concurrent callers (transaction-scoped advisory lock). Returns the number of
-- partitions created by this call.
CREATE FUNCTION ensure_metric_partitions(months_ahead int)
    RETURNS int
    LANGUAGE plpgsql
    SET search_path = public, pg_temp
AS $$
DECLARE
    current_month  date := date_trunc('month', now() AT TIME ZONE 'UTC')::date;
    month_start    date;
    partition_name text;
    created_count  int := 0;
BEGIN
    IF months_ahead IS NULL OR months_ahead < 0 OR months_ahead > 120 THEN
        RAISE EXCEPTION 'months_ahead must be between 0 and 120, got %', months_ahead;
    END IF;

    PERFORM pg_advisory_xact_lock(hashtext('pulseboard.ensure_metric_partitions'));

    FOR month_offset IN 0..months_ahead LOOP
        month_start := (current_month + make_interval(months => month_offset))::date;
        partition_name := format('metric_point_%s', to_char(month_start, 'YYYY_MM'));

        IF to_regclass(format('public.%I', partition_name)) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE public.%I PARTITION OF public.metric_point FOR VALUES FROM (%L) TO (%L)',
                partition_name,
                month_start::timestamp AT TIME ZONE 'UTC',
                (month_start + interval '1 month')::timestamp AT TIME ZONE 'UTC');
            created_count := created_count + 1;
        END IF;
    END LOOP;

    RETURN created_count;
END;
$$;

COMMENT ON FUNCTION ensure_metric_partitions(int) IS
    'Creates missing monthly metric_point partitions from the current UTC month up to months_ahead months ahead.';

SELECT ensure_metric_partitions(3);
