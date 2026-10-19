-- Splunk Database Monitoring prerequisites.
-- pg_stat_statements must also be in shared_preload_libraries (see docker-compose.yml).

-- Needed in the receiver's connect database (default: postgres) and in the app database.
\connect postgres
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
\connect shop
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Read-only monitoring user for the collector's postgresql receiver.
CREATE USER otel WITH PASSWORD 'otel';
GRANT pg_monitor TO otel;
GRANT SELECT ON pg_stat_database TO otel;
-- EXPLAIN of application queries (execution plans) needs SELECT on the app tables.
GRANT USAGE ON SCHEMA public TO otel;
ALTER DEFAULT PRIVILEGES FOR ROLE shop IN SCHEMA public GRANT SELECT ON TABLES TO otel;
