# Managed database recovery

This procedure makes a logical PostgreSQL18 archive and restores it into a **separate, freshly provisioned, empty application schema**. It uses the ordinary managed migrator over verify-full TLS. It never replaces an existing database. [Managed provisioning](managed-postgresql.md) and [ADR 008](decisions/008-managed-recovery.md) describe the identity and recovery boundaries.

## What is preserved

The selected schema's tables, data, indexes, constraints, identity sequences and Flyway history are included. Restored objects belong to the target migrator; the shared afterMigrate callback restores the target's explicit runtime grants. Schema creation, archived owners, ACLs and comments are omitted, retaining the fresh target's database/role/schema markers. Source and target must use the same application schema name. The target database name, administrator, passwords and application role names can differ; provision the target identities independently.

This is a schema-scoped logical recovery path, not cluster recovery or a provider snapshot. Provider objects, roles, credentials, large objects and dependencies outside the selected schema are excluded. Application password hashes are application data and are included. Treat the whole archive and generated SQL as sensitive, even for a synthetic demo. New cross-schema dependencies, extensions, objects or major PostgreSQL versions require a reviewed recovery update.

## Prepare and back up

Use Bash, GNU stat/mktemp, awk and PostgreSQL18 pg_dump/pg_restore/psql on a trusted operator machine, with the complete backend directory from the corresponding reviewed application release. Keep operator access private. Obtain these environment values through approved secret storage, without shell tracing, connection-string secrets or command-line passwords:

| Variable | Value |
| --- | --- |
| PGHOST, PGPORT | One direct TCP host and port; no pooler endpoint. |
| PGDATABASE | Source dedicated database. |
| PGUSER, PGPASSWORD | Source marked migrator and its secret; not runtime or provider administrator. |
| PGSSLMODE, PGSSLROOTCERT | verify-full and the trusted server CA configuration. |
| ISSUNEXA_DB_SCHEMA | Exact provisioned application schema. |
| TMPDIR | Optional trusted private temporary storage with enough space for expanded SQL. |

The wrapper rejects weakened TLS and connection strings in identifiers. It clears PGOPTIONS/PGSERVICE overrides. Never inject untrusted environment variables or tools into the operator process.

Create a new private destination outside the repository. The directory must already be owned by the operator and have mode700; archives are newly created mode600 files, with no overwrite option. For example, after selecting the protected operator backup location:

```sh
install -d -m 700 "$BACKUP_DIRECTORY"
bash backend/database/recovery-managed.sh backup "$BACKUP_DIRECTORY"
```

The command prints only the newly created archive path on success. Record its SHA-256 digest, source release commit/image, PostgreSQL/client versions, schema, backup UTC time, migration versions/checksums and synthetic application counts in a private manifest. Check stderr and the exit code before accepting a backup. Inspect its contents with pg_restore --list. Do not log rows or secrets.

pg_dump takes a consistent snapshot while application writes continue; writes after that snapshot are outside the recovery point. Coordinate a maintenance window when an exact cutover point is needed. Stop migration/provisioning changes during backup. Backup guard and dump use separate connections; they do not form one transaction.

Move accepted archives and manifests to separately authorized private, encrypted storage off the application host, and verify their digests after transfer. This repository does not provision backup storage, schedule uploads or establish retention. Until that storage and recurring restore practice exist, host loss can lose both the app and local backup. Set retention and backup frequency according to the acceptable data-loss window; no recovery-point or recovery-time target is claimed here.

## Restore and validate

1. Preserve the original source and its accepted backup. Obtain authorization for a separate target database; do not repoint the script at an existing user database.
2. Provision that dedicated target through provision-managed.sh, using distinct externally generated credentials. Do not start the application yet: Flyway startup would populate the empty schema and restoration would refuse it. Quiesce all other target operators/migrators.
3. Inspect archive provenance and SHA-256 against the private manifest. **A database dump is executable SQL. Only restore a trusted archive from the intended release/source.** TOC/schema checks and ordinary-role execution do not make malicious SQL safe. Inspect generated SQL privately using pg_restore --no-owner --no-privileges --no-comments --file when provenance is uncertain; do not restore it until reviewed.
4. Replace PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD/PGSSLROOTCERT with the target connection values. Keep the same ISSUNEXA_DB_SCHEMA, verify-full, and the target marked migrator. Run from the corresponding source release:

   ```sh
   bash backend/database/recovery-managed.sh restore "$TRUSTED_ARCHIVE"
   ```

5. Check successful exit. Compare the restored migration versions/checksums, synthetic row counts and sequence states with the manifest. Configure the backend with the target JDBC verify-full URL/currentSchema and distinct runtime/Flyway credentials, then start that same application release. Verify Flyway validation/no pending migrations and Hibernate validation, readiness, authenticated restored reads, and new ticket/comment/history writes without ID collisions. Readiness alone is not recovery acceptance.
6. Perform requester/staff access checks and runtime privilege denials before authorizing traffic. Keep the source and archive until the recovery is accepted. Upgrading the app is a separate reviewed step after same-release recovery succeeds.

The script parses the archive and generates all SQL in a private scratch directory **before connecting to the target**. It requires exactly the selected schema and Flyway history in the archive, then executes an empty-target/marker/identity/TLS guard, restored objects/data/sequence values and the current explicit grant callback in a single psql transaction. The schema creation TOC entry is excluded, so target provisioning metadata survives. The restore shares the provisioning advisory lock. An error stops execution and rolls back restored objects and grants. It uses no --clean, database creation/drop, trigger disabling, superuser or data adoption. Scratch SQL is deleted on exit; that is filesystem removal, not secure media erasure.

On failure, keep traffic stopped, preserve the original archive and a sanitized error, and inspect target state. Do not remove markers, reset Flyway, drop data or retry against a populated target to obtain success. Late failure should leave the target application schema empty; investigate a mismatch before any retry. Existing nonempty targets require a separately authorized recovery decision, not an override flag.

## Verification boundary

ManagedDatabasePrivilegesIntegrationTests exercises the actual wrapper against two disposable PostgreSQL18.6 TLS instances, using non-superuser migrators and generated trust roots. The application creates synthetic requester/agent accounts, ticket claim/status history and comments before backup; recovery compares rows, complete Flyway history and sequence state, restarts the app, verifies reads/new writes and runtime privilege denials. Negative cases cover corrupt archives, schema mismatch, nonempty/unmarked targets, TLS weakening, wrong identity and rollback after a late callback failure. The source database is preserved throughout.

This proves the local portable logical process. Actual provider permissions, real certificate chains, off-host archive storage/retention, suspend/wake, restore duration and hosted restart remain unverified and require separate authorization/evidence. Demo data expiry does not remove expired data from old backups; retention must address those archives too.

Primary references: [pg_dump consistent snapshots and trust boundary](https://www.postgresql.org/docs/18/app-pgdump.html), [pg_restore list selection and SQL generation](https://www.postgresql.org/docs/18/app-pgrestore.html), [psql transaction and error handling](https://www.postgresql.org/docs/18/app-psql.html).
