#!/bin/sh
# Runs once, the first time the Postgres volume is created.
#
# Least privilege, two logins for the helpdesk plus one for Keycloak:
#   helpdesk_owner -> owns schema "helpdesk" and runs the Flyway migrations (DDL)
#   helpdesk_app   -> what the running service uses. The migrations grant it only what it
#                     needs: no DDL, and no UPDATE/DELETE on the history tables.
#   keycloak       -> owns database "keycloak"
set -eu

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname "$POSTGRES_DB" \
     -v owner_pw="$DB_OWNER_PASSWORD" \
     -v app_pw="$DB_APP_PASSWORD" \
     -v kc_pw="$KEYCLOAK_DB_PASSWORD" <<'EOSQL'
-- :'name' makes psql quote the value safely as a SQL literal
CREATE ROLE helpdesk_owner LOGIN PASSWORD :'owner_pw';
CREATE ROLE helpdesk_app   LOGIN PASSWORD :'app_pw';
CREATE ROLE keycloak       LOGIN PASSWORD :'kc_pw';

CREATE DATABASE keycloak OWNER keycloak;
REVOKE CONNECT ON DATABASE keycloak FROM PUBLIC;

REVOKE CONNECT ON DATABASE helpdesk FROM PUBLIC;
GRANT  CONNECT ON DATABASE helpdesk TO helpdesk_owner, helpdesk_app;

CREATE SCHEMA helpdesk AUTHORIZATION helpdesk_owner;

ALTER ROLE helpdesk_owner SET search_path = helpdesk;
ALTER ROLE helpdesk_app   SET search_path = helpdesk;
EOSQL
