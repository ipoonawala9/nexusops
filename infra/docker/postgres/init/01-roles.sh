#!/bin/bash
# Creates the least-privilege roles described in ADR-0002.
# nexusops_owner: owns the schema, runs Flyway migrations.
# nexusops_app:   runtime role. DML only (granted by migrations), never bypasses RLS.
set -euo pipefail

: "${NEXUSOPS_OWNER_PASSWORD:?NEXUSOPS_OWNER_PASSWORD must be set}"
: "${NEXUSOPS_APP_PASSWORD:?NEXUSOPS_APP_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v owner_pw="$NEXUSOPS_OWNER_PASSWORD" \
  -v app_pw="$NEXUSOPS_APP_PASSWORD" <<'EOSQL'
CREATE ROLE nexusops_owner LOGIN PASSWORD :'owner_pw'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
CREATE ROLE nexusops_app LOGIN PASSWORD :'app_pw'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

ALTER SCHEMA public OWNER TO nexusops_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO nexusops_app;
EOSQL
