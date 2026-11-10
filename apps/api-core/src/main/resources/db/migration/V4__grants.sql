-- Table privileges for the analytics service roles created by infra/postgres/init.
--
-- analytics_reader: read-only access to metric_point. Privileges are checked on the partitioned parent
-- only, so partitions created later by ensure_metric_partitions() need no additional grant as long as
-- analytics reads through metric_point.
-- analytics_writer: its tables (anomaly, detector_state) are created by a later migration, which grants
-- them in the same migration. No privileges on existing tables are given to it here.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'analytics_reader') THEN
        RAISE EXCEPTION 'Role analytics_reader is missing; run infra/postgres/init before migrating';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'analytics_writer') THEN
        RAISE EXCEPTION 'Role analytics_writer is missing; run infra/postgres/init before migrating';
    END IF;
END;
$$;

REVOKE ALL ON metric_point FROM analytics_reader, analytics_writer;
GRANT SELECT ON metric_point TO analytics_reader;

-- Partition maintenance is an api-core responsibility only.
REVOKE ALL ON FUNCTION ensure_metric_partitions(int) FROM PUBLIC;
