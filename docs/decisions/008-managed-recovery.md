# ADR 008: Fresh-target managed logical recovery

Status: accepted for implementation; actual provider recovery remains unverified.

## Problem and current behavior

Managed provisioning separates ordinary migrator/runtime identities and marks a dedicated schema. The application migrates and validates it, but a backup recommendation alone does not demonstrate recoverability. Existing provisioning must never adopt or erase an existing database.

## Options and decision

Provider snapshots depend on an authorized account and current retention/restore terms; none is provisioned. An in-place pg_restore --clean path would destroy existing state. A new backup dependency/service adds cost and operational work. Select the existing PostgreSQL18 tools: custom schema archive, separately provisioned empty target, and one guarded transaction applying restore SQL and the existing explicit runtime grants. Preserve target markers and ownership by omitting source schema creation, comments, owners and ACLs.

The operator uses only the marked ordinary migrator over verify-full on direct endpoints. Require trusted archive provenance: dumps are executable SQL, and schema filtering is not a sandbox. Privately staged SQL prevents a truncated live pipeline from committing. No application endpoint, credential in Git, provider account, paid resource, public exposure, reset or destructive override is added.

## Operations, measurement and rollback

The archive includes synthetic application data/password hashes, Flyway history and sequence state; private files and separately authorized encrypted off-host storage are required. Storage/scheduling/retention remain operator responsibilities, with no availability, RPO or RTO promise. Temporary disk must fit expanded SQL and the target must remain quiescent. Keep same schema name and application release, then validate migration checksums, JPA, permissions and real application reads/writes before traffic.

Measure correctness with actual non-superuser TLS scripts on two disposable PostgreSQL instances, exact row/history/sequence comparisons, app restart/new writes and negative target/identity/TLS/archive/late-error rollback tests. Preserve source and accepted backups. Roll back an unsuccessful attempt by its transaction; if the target is populated, stop for a separate decision. Revert the operator tooling through a normal reviewed change if needed; never rewrite migrations or erase existing data as rollback.
