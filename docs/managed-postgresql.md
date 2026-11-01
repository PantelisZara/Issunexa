# Managed PostgreSQL provisioning

This is a separate provisioning path for a **fresh dedicated database**, using its non-superuser owner with CREATEROLE. It prepares portable PostgreSQL permissions; no Neon account, service or actual provider connection is established by these instructions. The normal [local bootstrap](database.md#database-roles-and-provisioning) remains supported; V1–V9 migration bytes remain unchanged. [ADR 006](decisions/006-managed-postgresql.md) records the decision.

## Identities and boundary

| Identity | Ownership and access |
| --- | --- |
| Provider administrator | Owns the dedicated database and can create/administer ordinary roles. Credentials are used only by the explicit provisioning command, outside the deployed app. |
| Migrator | Ordinary SQL login; owns the dedicated application schema, Flyway history, tables and identity sequences. No elevated flags, database ownership or membership into another role. |
| Runtime | Ordinary SQL login; owns nothing. CONNECT, application-schema USAGE, the existing explicit table/sequence policy only. No memberships into other roles. |

The administrator is a member **of** each application role with ADMIN and SET enabled, INHERIT disabled (PostgreSQL may record separate grants). The transaction sets createrole_self_grant=set so creation establishes those options without an invalid self-ADMIN grant. This permits repeat password/settings operations and creation of a schema owned by the migrator. Neither application role is a member of the administrator or any provider role. NOINHERIT alone would not prevent SET ROLE, so membership direction/options are checked explicitly.

For the Neon candidate, create application roles through this SQL provisioner. Console/API/CLI-created roles receive neon_superuser membership and are rejected. The provider administrator can have provider memberships; it must still be the selected database's actual owner, have CREATEROLE and lack the PostgreSQL superuser flag. The scripts require PostgreSQL 16+; executable compatibility tests use 18.6.

## Before provisioning

Create a dedicated empty database through a separately authorized provider operation. Do not point this procedure at an existing local database, user database or shared service. It never imports data or adopts pre-existing names. Supply unused application role/schema names and retain the same names/administrator on reruns.

The provisioner rejects unrelated schemas and non-extension relations/functions/types outside the application schema, including unrelated objects hidden inside an extension namespace. Actual extension-owned objects and their implicit type dependents are allowed. Unknown provider customization must be reviewed rather than automatically permitted. It rejects unexpected grantees or runtime grant options/privileges on the application schema, tables, sequences and explicit type ACLs; those grants are preserved for manual review when the transaction fails. The managed callback checks this same namespace boundary even with no pending migration. It also rejects PUBLIC CREATE rights on user namespaces/database, elevated or colliding application roles, unexpected memberships/settings/ownership, unknown application objects and custom default privileges. It never alters provider namespaces. Database-wide PUBLIC temporary-table permission is revoked for this dedicated database.

Names must be lowercase letters/digits/underscores, start with a letter, contain at most 63 characters, and avoid pg_/neon_ prefixes. The application schema must differ from public/information_schema. Administrator/migrator/runtime identities must differ. Supply independently generated application passwords of at least 24 characters through a secret manager or a protected environment outside Git; the script checks length and distinctness, not randomness. Do not put passwords in URLs, shell arguments, traces, build arguments, images or frontend configuration.

Use a direct TCP endpoint, never a hostname containing -pooler, a filesystem socket or an abstract socket beginning with @. The shell entry point requires PGSSLMODE=verify-full and forces PGGSSENCMODE=disable: libpq must complete certificate and hostname verification on the actual provisioning connection before executing SQL. GSS encryption otherwise takes precedence over sslmode. Supply the trusted CA through PGSSLROOTCERT (or libpq's configured trust file). With libpq18, PGSSLROOTCERT=system uses system roots only when the operator environment actually contains a CA bundle. Minimal PostgreSQL client images may need a trusted public CA bundle mounted read-only; keep verify-full enabled.

Run provision-managed.sh, not its internal SQL file directly. Strict database/user identifiers and cleared PGOPTIONS/PGSERVICE/PGSERVICEFILE prevent connection-string or service overrides; psql -X prevents a startup file from changing the connection. The SQL guard checks database identity, ownership and privileges. It does not use pg_stat_ssl to infer client transport: that view describes the PostgreSQL backend connection, which can be plaintext behind a provider's TLS proxy even when the operator's connection is verified TLS. Client enforcement applies to direct PostgreSQL and provider proxies alike, without a skip flag, caller-supplied TLS assertion or separate preflight authorizing a later connection. Provider-internal transport remains a separate trust boundary; these scripts make no claim about its encryption. See [ADR 009](decisions/009-managed-client-tls.md).

## Provisioning order

Use psql 16+ and the repository's complete backend directory, because the SQL entry point includes the shared Flyway grant callback by relative path. Configure these **environment variables**, obtaining secrets through your approved secret storage:

| Variable | Meaning |
| --- | --- |
| PGHOST, PGPORT | Direct provider host and port; PGHOST is required, usual port is 5432. |
| PGDATABASE, PGUSER, PGPASSWORD | Dedicated target database, its owner administrator and administrator secret. |
| PGSSLMODE, PGSSLROOTCERT | verify-full and trusted root configuration. |
| ISSUNEXA_DB_SCHEMA | Dedicated application schema, for example issunexa. |
| ISSUNEXA_FLYWAY_USERNAME, ISSUNEXA_FLYWAY_PASSWORD | Dedicated migrator and externally generated secret. |
| ISSUNEXA_DB_USERNAME, ISSUNEXA_DB_PASSWORD | Dedicated runtime identity and externally generated secret. |

From the repository root, after those variables are supplied:

```sh
sh backend/database/provision-managed.sh
```

V10 deliberately adds `demo_workspaces`, its identity/index objects, the unique demo account role index, and the migration-owned zero-argument `expire_demo_workspaces()` function to the strict allowlist. Runtime Ticket UPDATE is restricted to the seven existing ORM-mutable columns (`title`, `description`, `status`, `priority`, `assignee_id`, `version`, `updated_at`); requester/category/creation/id cannot be changed. Only these runtime column UPDATE ACLs without grant option are allowed; unrelated/PUBLIC/outsider grants remain rejected. The callback removes legacy table UPDATE before applying the exact column grants. Runtime gets SELECT/INSERT on the workspace table and explicit EXECUTE on that expiry-only function, with PUBLIC execution revoked and no workspace UPDATE/DELETE. Routine ownership, signature, SECURITY DEFINER, plpgsql language, fixed search_path and ACL are checked on provisioning and every callback. Unknown functions and outsider/PUBLIC/grant-option execution remain rejected; see the [demo backend contract](demo-backend.md).

When adding tables, indexes, SQL functions or types, review the managed object allowlist and both provisioning paths alongside the callback's explicit runtime policy. Future objects receive no automatic runtime grants.

The command uses psql -X/ON_ERROR_STOP, an advisory transaction lock and one transaction. Roles/schema receive database-specific markers. All preflight checks run before persistent mutations; errors later in the shared callback/final verification also roll back role creation, password changes, settings and ACL changes. Provisioning creates no application table or row and never resets a sequence or migration history. Successful fresh provisioning is followed by application startup, where Flyway migrates as the migrator and Hibernate validates through runtime connections.

Set the backend's ISSUNEXA_DB_URL to the following shape, substituting the actual direct host/database/schema and a trusted CA file accessible to the Java process:

```text
jdbc:postgresql://DIRECT_HOST:5432/DATABASE?sslmode=verify-full&sslrootcert=/path/to/trusted-root.pem&currentSchema=issunexa
```

JDBC uses camel-case currentSchema to select the same application schema. Do not use a libpq connection string as a JDBC URL. Both the runtime datasource and Spring's separate Flyway datasource use this URL with their distinct ISSUNEXA_DB_* / ISSUNEXA_FLYWAY_* credentials. Do not give the backend PGUSER/PGPASSWORD or the provider owner credentials. Keep migrations on a direct endpoint: transaction pooling does not preserve arbitrary session settings/advisory-lock behavior.

libpq PGSSLROOTCERT and pgJDBC sslrootcert are independent trust settings; configure each client explicitly. Do not pass the libpq magic system value as a Java certificate path.

Both roles receive a database-scoped search_path for ordinary connections; settings apply on new login, not SET ROLE. Flyway and JPA are verified with currentSchema. The callback resolves the managed runtime identity only from the current schema's strictly validated marker, matching its migrator, runtime role markers and database owner. Missing or inconsistent managed metadata and unexpected application column grants fail closed, including when no versioned migration is pending; it never searches other schemas. Actual non-superuser PostgreSQL rejects persistent custom GUC defaults without separately granted parameter privileges, so this path does not require them. The local bootstrap retains its existing issunexa.runtime_role setting and public callback default. The provisioner's own transaction uses transient settings while calling the same grant policy. The provisioning procedure itself does not rewrite existing versioned migrations.

## Reruns and password rotation

Repeat the same command with the same database, administrator, schema and role names. It reapplies the exact existing grants, including when no migrations are pending. Unknown objects or changed ownership/role memberships require review; do not remove markers or force adoption. The provisioner does not manage unrelated roles, schemas or provider extensions.

For rotation, stop application work, keep administrator access, provide new independently generated migrator/runtime secrets to the provisioner, then update the backend secret environment and restart. Successful provisioning changes future login credentials; already-open sessions are not revoked. Verify Flyway validation/no pending migrations and an authenticated persisted application read/write before resuming. Obtain a verified provider backup before real installation changes. Never reset a volume or overwrite live data to reverse privilege changes.

## Executable evidence and actual-provider gates

From backend/, with Java 21 and Docker:

```sh
./mvnw --batch-mode --no-transfer-progress -Dtest=ManagedDatabasePrivilegesIntegrationTests,DatabasePrivilegesIntegrationTests test
```

The managed tests generate disposable TLS PostgreSQL containers. A fixture-only superuser creates a non-superuser database owner with CREATEROLE and observes verifier fingerprints/catalog state; the actual shell/SQL provisioner logs in as the non-superuser over verify-full TLS. Tests cover fresh Flyway/JPA and account/Ticket/comment/history writes, namespace/role separation, administrative/DDL/history/destructive denials, repeat provisioning, password rotation, preserved rows/sequences/checksums, role/schema collisions, elevations/memberships/ownership, unknown objects inside extension namespaces, preserved extension-owned provider objects, external table/column/function grants, role/PUBLIC application column grants, namespace settings/CREATE privileges and rollback after a late callback failure. Both the psql entry point and JDBC connections use verify-full with the generated certificate; public CA files copied for Java live in JUnit temporary storage. Provider certificate authentication is not inferred from this local trust fixture. A disposable Java TLS proxy additionally terminates the actual libpq connection and forwards plaintext PostgreSQL protocol upstream; one session verifies client TLS while its backend reports ssl=false. The original provisioning/rerun/password-rotation and full recovery workflows run with both direct TLS and this proxy. Transport failures (wrong/missing CA, mismatched hostname, no TLS, weaker mode, abstract socket and connection-string identifiers) preserve database snapshots. Inherited GSS/service overrides are neutralized, and a separate dump-handshake failure removes the partial archive. The strict bootstrap suite also verifies existing local behavior and the explicit demo privilege boundary. Full backend verification and hosted CI remain required before integration.

Actual Neon acceptance remains unexecuted: account/Free eligibility, database owner administration/membership behavior, real provider namespaces/default ACLs, generated-password policy, database-scoped search_path settings and marker-driven grants, runtime/migration TLS logins, privilege denials/reruns, direct-connection limits, suspend/wake-up, and backup/restore. These require later explicit account/resource authorization. Database provisioning alone does not make the unrestricted staff model safe for a public shared demo.

## Logical recovery

The [managed recovery procedure](managed-recovery.md) provides an operator-only backup and transactional restore into a separate fresh target, with actual disposable TLS integration coverage. It does not establish provider retention, external storage or actual hosted recovery.

## Primary references

- [Neon roles and SQL-created identities](https://neon.com/docs/manage/roles)
- [Neon database/schema/object privileges](https://neon.com/docs/manage/database-access)
- [Neon direct connections and pooling limitations](https://neon.com/docs/connect/connection-pooling)
- [PostgreSQL ALTER ROLE permissions and login defaults](https://www.postgresql.org/docs/18/sql-alterrole.html)
- [PostgreSQL role membership options](https://www.postgresql.org/docs/18/sql-grant.html)
- [libpq SSL connection parameters](https://www.postgresql.org/docs/18/libpq-connect.html)
- [pgJDBC connection properties](https://jdbc.postgresql.org/documentation/use/)
