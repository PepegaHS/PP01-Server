#!/bin/sh
set -eu

create_database() {
    psql --username "$POSTGRES_USER" --dbname postgres --set=ON_ERROR_STOP=1 \
        --set=db_name="kodr_$1" --set=db_password="$2" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'db_name', :'db_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'db_name') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'db_name', :'db_name')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'db_name') \gexec
SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', :'db_name') \gexec
SQL
}

create_database user "${USER_DB_PASSWORD:-kodr-dev-user}"
create_database course "${COURSE_DB_PASSWORD:-kodr-dev-course}"
create_database task "${TASK_DB_PASSWORD:-kodr-dev-task}"
create_database submission "${SUBMISSION_DB_PASSWORD:-kodr-dev-submission}"
create_database progress "${PROGRESS_DB_PASSWORD:-kodr-dev-progress}"
create_database keycloak "${KEYCLOAK_DB_PASSWORD:-kodr-dev-keycloak}"
