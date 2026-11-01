#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

action=${1:-}
if [[ $# != 2 || ( $action != backup && $action != restore ) ]]; then
    printf 'Usage: recovery-managed.sh backup PRIVATE_DIRECTORY | restore TRUSTED_ARCHIVE\n' >&2
    exit 1
fi
destination=$2
: "${PGHOST:?Set the direct host}" "${PGDATABASE:?Set the dedicated database}"
: "${PGUSER:?Set the marked migrator}" "${PGPASSWORD:?Supply its secret outside Git}"
: "${ISSUNEXA_DB_SCHEMA:?Set the application schema}"
for identifier in "$PGDATABASE" "$PGUSER" "$ISSUNEXA_DB_SCHEMA"; do
    if [[ ! $identifier =~ ^[a-z][a-z0-9_]{0,62}$ || $identifier == pg_* || $identifier == neon_* ]]; then
        printf 'Use dedicated lowercase identifiers, not connection strings.\n' >&2
        exit 1
    fi
done
case "$PGHOST" in
    */*|*,*|*-pooler.*) printf 'Use one direct TCP host.\n' >&2; exit 1 ;;
esac
export PGSSLMODE=${PGSSLMODE:-verify-full}
if [[ $PGSSLMODE != verify-full ]]; then
    printf 'Recovery requires verify-full TLS and a trusted certificate.\n' >&2
    exit 1
fi
unset PGOPTIONS PGSERVICE PGSERVICEFILE
export ISSUNEXA_RECOVERY_ACTION=$action
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
scratch=$(mktemp -d "${TMPDIR:-/tmp}/issunexa-recovery.XXXXXXXX")
archive=
cleanup() {
    result=$?
    trap - EXIT
    rm -rf -- "$scratch"
    if (( result != 0 )) && [[ -n $archive ]]; then rm -f -- "$archive"; fi
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
guard="$script_dir/recovery-guard.sql"
callback="$script_dir/../src/main/resources/db/migration/afterMigrate__runtime_privileges.sql"
psql_options=(-X --quiet --no-password --set=ON_ERROR_STOP=1 --set=VERBOSITY=terse --set=SHOW_CONTEXT=never)

if [[ $action == backup ]]; then
    if [[ ! -d $destination || $(stat -c '%a' -- "$destination") != 700 || ! -O $destination ]]; then
        printf 'Backup destination must be an existing private directory owned by this user (mode700).\n' >&2
        exit 1
    fi
    psql "${psql_options[@]}" --single-transaction --file="$guard"
    archive=$(mktemp -- "$destination/issunexa.XXXXXXXX.dump")
    pg_dump --no-password --format=custom --no-owner --no-privileges \
        --schema="$ISSUNEXA_DB_SCHEMA" --file="$archive"
    printf '%s\n' "$archive"
else
    if [[ ! -f $destination || -L $destination ]]; then
        printf 'Restore requires a trusted regular archive file.\n' >&2
        exit 1
    fi
    # Parse the archive fully before connecting to the target. No live SQL pipeline.
    if ! pg_restore --list -- "$destination" > "$scratch/archive.list"; then
        printf 'Restore requires a valid archive.\n' >&2
        exit 1
    fi
    if ! awk -v schema="$ISSUNEXA_DB_SCHEMA" '
        /^;/ { print; next }
        $4 == "SCHEMA" {
            if ($5 != "-" || $6 != schema) exit 1
            schemas++
            print ";" $0
            next
        }
        $4 == "TABLE" && $5 == schema && $6 == "flyway_schema_history" { history++ }
        { print }
        END { if (schemas != 1 || history != 1) exit 1 }
    ' "$scratch/archive.list" > "$scratch/restore.list"; then
        printf 'Archive must contain exactly the selected schema and Flyway history.\n' >&2
        exit 1
    fi
    pg_restore --no-owner --no-privileges --no-comments --schema="$ISSUNEXA_DB_SCHEMA" \
        --use-list="$scratch/restore.list" --file="$scratch/archive.sql" -- "$destination"
    cat "$guard" "$scratch/archive.sql" > "$scratch/transaction.sql"
    printf '\nSET LOCAL search_path TO "%s";\n' "$ISSUNEXA_DB_SCHEMA" >> "$scratch/transaction.sql"
    cat "$callback" >> "$scratch/transaction.sql"
    # Guard, objects, data, sequence state and explicit grants commit together.
    psql "${psql_options[@]}" --single-transaction --file="$scratch/transaction.sql"
fi
