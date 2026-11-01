#!/bin/sh
set -eu

# Use explicit libpq fields, not credential-bearing URLs or command arguments.
: "${PGHOST:?Set the direct PostgreSQL host}" "${PGDATABASE:?Set the dedicated database}"
: "${PGUSER:?Set its owner administrator}" "${PGPASSWORD:?Set PGPASSWORD}"
: "${ISSUNEXA_DB_SCHEMA:?Set the dedicated application schema}"
: "${ISSUNEXA_FLYWAY_USERNAME:?Set ISSUNEXA_FLYWAY_USERNAME}"
: "${ISSUNEXA_FLYWAY_PASSWORD:?Set ISSUNEXA_FLYWAY_PASSWORD}"
: "${ISSUNEXA_DB_USERNAME:?Set ISSUNEXA_DB_USERNAME}"
: "${ISSUNEXA_DB_PASSWORD:?Set ISSUNEXA_DB_PASSWORD}"
# Reject URI/connection-string inputs before libpq can redirect a connection.
for identifier in "$PGDATABASE" "$PGUSER" "$ISSUNEXA_DB_SCHEMA" "$ISSUNEXA_FLYWAY_USERNAME" "$ISSUNEXA_DB_USERNAME"; do
    case "$identifier" in
        [a-z]*) ;;
        *) printf 'Use dedicated lowercase identifiers.\n' >&2; exit 1 ;;
    esac
    case "$identifier" in
        *[!a-z0-9_]*|pg_*|neon_*) printf 'Use dedicated lowercase identifiers outside reserved namespaces.\n' >&2; exit 1 ;;
    esac
    if [ ${#identifier} -gt 63 ]; then
        printf 'Identifiers must contain at most 63 characters.\n' >&2
        exit 1
    fi
done
case "$PGHOST" in
    */*|@*|*,*|*-pooler.*) printf 'Use a single direct TCP host, not a socket or pooled endpoint.\n' >&2; exit 1 ;;
esac
PGSSLMODE=${PGSSLMODE:-verify-full}
export PGSSLMODE
if [ "$PGSSLMODE" != verify-full ]; then
    printf 'Managed provisioning requires PGSSLMODE=verify-full and a trusted server certificate.\n' >&2
    exit 1
fi
if [ ${#ISSUNEXA_FLYWAY_PASSWORD} -lt 24 ] || [ ${#ISSUNEXA_DB_PASSWORD} -lt 24 ] ||
   [ "$ISSUNEXA_FLYWAY_PASSWORD" = "$ISSUNEXA_DB_PASSWORD" ] ||
   [ "$ISSUNEXA_FLYWAY_PASSWORD" = "$PGPASSWORD" ] || [ "$ISSUNEXA_DB_PASSWORD" = "$PGPASSWORD" ]; then
    printf 'Supply distinct externally generated application passwords of at least 24 characters.\n' >&2
    exit 1
fi
# libpq must negotiate verified TLS on every connection, including pg_dump.
# GSS encryption otherwise takes precedence over sslmode and skips certificates.
export PGGSSENCMODE=disable
unset PGOPTIONS PGSERVICE PGSERVICEFILE
exec psql -X --set=ON_ERROR_STOP=1 --file="$(dirname "$0")/provision-managed.sql"
