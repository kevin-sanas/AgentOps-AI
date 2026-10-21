#!/bin/sh
set -eu
psql --username "$POSTGRES_USER" --dbname postgres --set=ON_ERROR_STOP=1 --set=checkpoint_password="$CHECKPOINT_PASSWORD" <<'SQL'
CREATE ROLE agentops_checkpoint LOGIN PASSWORD :'checkpoint_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE agentops_checkpoint OWNER agentops_checkpoint;
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;
REVOKE CONNECT ON DATABASE agentops_checkpoint FROM PUBLIC;
GRANT CONNECT ON DATABASE agentops_checkpoint TO agentops_checkpoint;
SQL
