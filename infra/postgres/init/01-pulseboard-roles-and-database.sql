-- Pulseboard PostgreSQL bootstrap.
--
-- Executed once by the official postgres image (docker-entrypoint-initdb.d) as the superuser.
-- Role passwords are never stored in this file; they are read from the container environment:
--   API_CORE_DB_PASSWORD           login password for role api_core (schema owner, Flyway)
--   ANALYTICS_READER_DB_PASSWORD   login password for role analytics_reader (read-only)
--   ANALYTICS_WRITER_DB_PASSWORD   login password for role analytics_writer (anomaly writes)
-- Optional:
--   PULSEBOARD_DB_NAME             application database name (default: pulseboard)
--
-- Table-level grants (metric_point for the reader, anomaly/detector_state for the writer) are
-- applied by the Flyway migrations that create those tables, because they do not exist yet here.

\set ON_ERROR_STOP on

\getenv api_core_password API_CORE_DB_PASSWORD
\getenv analytics_reader_password ANALYTICS_READER_DB_PASSWORD
\getenv analytics_writer_password ANALYTICS_WRITER_DB_PASSWORD
\getenv pulseboard_db PULSEBOARD_DB_NAME

-- Unset variables are normalised to the empty string so that "unset" and "set but empty" are
-- rejected by the same check below (an empty password would otherwise create a role whose
-- password is NULL, i.e. one that silently cannot authenticate).
\if :{?api_core_password}
\else
  \set api_core_password ''
\endif
\if :{?analytics_reader_password}
\else
  \set analytics_reader_password ''
\endif
\if :{?analytics_writer_password}
\else
  \set analytics_writer_password ''
\endif
\if :{?pulseboard_db}
\else
  \set pulseboard_db ''
\endif

SELECT btrim(:'api_core_password') = ''         AS api_core_password_blank,
       btrim(:'analytics_reader_password') = '' AS analytics_reader_password_blank,
       btrim(:'analytics_writer_password') = '' AS analytics_writer_password_blank,
       btrim(:'pulseboard_db') = ''             AS pulseboard_db_blank
\gset

\if :api_core_password_blank
  DO $$ BEGIN RAISE EXCEPTION 'Environment variable API_CORE_DB_PASSWORD must be set and non-empty'; END $$;
\endif
\if :analytics_reader_password_blank
  DO $$ BEGIN RAISE EXCEPTION 'Environment variable ANALYTICS_READER_DB_PASSWORD must be set and non-empty'; END $$;
\endif
\if :analytics_writer_password_blank
  DO $$ BEGIN RAISE EXCEPTION 'Environment variable ANALYTICS_WRITER_DB_PASSWORD must be set and non-empty'; END $$;
\endif
\if :pulseboard_db_blank
  \set pulseboard_db pulseboard
\endif

-- ---------------------------------------------------------------------------
-- Roles (idempotent: create when missing, then always reset the password)
-- ---------------------------------------------------------------------------

SELECT format('CREATE ROLE %I LOGIN', r.name)
FROM (VALUES ('api_core'), ('analytics_reader'), ('analytics_writer')) AS r(name)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r.name)
\gexec

ALTER ROLE api_core WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'api_core_password';
ALTER ROLE analytics_reader WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'analytics_reader_password';
ALTER ROLE analytics_writer WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    PASSWORD :'analytics_writer_password';

ALTER ROLE analytics_reader SET default_transaction_read_only = on;

-- ---------------------------------------------------------------------------
-- Database (idempotent: works whether or not POSTGRES_DB already created it)
-- ---------------------------------------------------------------------------

SELECT format('CREATE DATABASE %I OWNER api_core ENCODING ''UTF8'' TEMPLATE template0', :'pulseboard_db')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'pulseboard_db')
\gexec

SELECT format('ALTER DATABASE %I OWNER TO api_core', :'pulseboard_db')
\gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'pulseboard_db')
\gexec
SELECT format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO api_core', :'pulseboard_db')
\gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO analytics_reader, analytics_writer', :'pulseboard_db')
\gexec

-- ---------------------------------------------------------------------------
-- Schema privileges inside the application database
-- ---------------------------------------------------------------------------

\connect :pulseboard_db

ALTER SCHEMA public OWNER TO api_core;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO api_core;
GRANT USAGE ON SCHEMA public TO analytics_reader, analytics_writer;

-- Objects created later by api_core (Flyway) grant nothing implicitly to PUBLIC.
ALTER DEFAULT PRIVILEGES FOR ROLE api_core IN SCHEMA public REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE api_core IN SCHEMA public REVOKE ALL ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE api_core REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
