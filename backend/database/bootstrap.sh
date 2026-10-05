#!/bin/sh
set -eu

# Passwords travel through the environment, never command arguments or tracing.
: "${PGDATABASE:?Set PGDATABASE}" "${PGUSER:?Set PGUSER}" "${PGPASSWORD:?Set PGPASSWORD}"
: "${ISSUNEXA_FLYWAY_USERNAME:?Set ISSUNEXA_FLYWAY_USERNAME}"
: "${ISSUNEXA_FLYWAY_PASSWORD:?Set ISSUNEXA_FLYWAY_PASSWORD}"
: "${ISSUNEXA_DB_USERNAME:?Set ISSUNEXA_DB_USERNAME}"
: "${ISSUNEXA_DB_PASSWORD:?Set ISSUNEXA_DB_PASSWORD}"
if [ "$ISSUNEXA_FLYWAY_PASSWORD" = "$ISSUNEXA_DB_PASSWORD" ] ||
   [ "$ISSUNEXA_FLYWAY_PASSWORD" = "$PGPASSWORD" ] ||
   [ "$ISSUNEXA_DB_PASSWORD" = "$PGPASSWORD" ]; then
    printf 'Bootstrap, migration and runtime passwords must be distinct.\n' >&2
    exit 1
fi
exec psql -X --set=ON_ERROR_STOP=1 --file="$(dirname "$0")/bootstrap.sql"
