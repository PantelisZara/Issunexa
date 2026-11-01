# ADR 006: isolated managed PostgreSQL provisioning

Status: accepted for portable PostgreSQL implementation; actual-provider acceptance pending.

## Problem

The local bootstrap intentionally requires a superuser and rejects customized/shared schemas. Managed PostgreSQL providers do not expose that identity. Relaxing those guards would weaken an existing installation's safety contract.

## Decision

Add an explicit managed provisioner for a fresh dedicated database owned by a non-superuser administrator with CREATEROLE. Create ordinary SQL login roles, permit only the provisioner's administrative membership into them, and give the migrator ownership of a dedicated application schema. Runtime owns nothing and retains the existing deliberate table/sequence grants. Use database-specific role/schema markers, one advisory-locked transaction and fail-closed validation; existing application data is never adopted or relocated.

Select the schema with database-scoped search_path defaults and JDBC currentSchema. The managed callback validates only the current schema and its paired role markers. Generalize the existing grant callback with a public default, preserving the local bootstrap and V1–V9 bytes/checksums. Use a direct TLS endpoint for provisioning and application migration sessions. Administrator credentials stay outside the application.

Neon is the candidate provider. Its console/API/CLI-created roles inherit neon_superuser; application roles must instead be created with SQL. PostgreSQL CREATEROLE requires ADMIN OPTION to alter existing ordinary roles; schema ownership authorization also requires SET permission into the migrator. The administrator membership points into the application roles, never the reverse. Use createrole_self_grant=set so PostgreSQL creates the SET membership alongside its implicit ADMIN grant. The real non-superuser test also established that persistent custom GUC defaults require separate parameter privileges; managed provisioning therefore uses standard search_path plus validated markers. The local superuser bootstrap retains its existing custom GUC setting.

## Alternatives and consequences

Keeping application tables in public would couple grants to provider-owned objects. A separate schema keeps the ownership boundary explicit. Changing versioned migrations, using the provider owner as runtime, or granting blanket future-table privileges would break the existing contract. Database-wide TEMP privileges are revoked only for the explicitly selected dedicated database; unrelated namespaces are not altered, and insecure CREATE privileges cause rejection.

The same backend process still holds Flyway credentials at startup. Runtime connection restrictions do not isolate those secrets from a fully compromised process. Real provider administration, TLS, suspend/wake-up and backup/restore behavior require later authorized verification; a disposable non-superuser PostgreSQL test proves SQL compatibility only. No account, deployment or billable resource is created here.

## Verification and rollback

Run the actual scripts with a non-superuser database owner in disposable PostgreSQL, exercise Flyway/JPA, normal writes, negative runtime privileges, repeat provisioning/password rotation and failure snapshots. Keep the strict bootstrap suite unchanged. Provisioning failures roll back all mutations. The local Compose workflow remains available. Reversing a managed installation's role/schema ownership requires a reviewed administrator operation rather than a data reset.

## Primary sources

- https://neon.com/docs/manage/roles
- https://neon.com/docs/connect/connection-pooling
- https://www.postgresql.org/docs/18/sql-alterrole.html
- https://www.postgresql.org/docs/18/sql-grant.html
- https://jdbc.postgresql.org/documentation/use/

- https://www.postgresql.org/docs/18/runtime-config-client.html#GUC-CREATEROLE-SELF-GRANT
