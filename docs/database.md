# Database and schema evolution

PostgreSQL stores application data; Flyway owns the schema and Hibernate validates mappings. The [persistence ADR](decisions/003-postgresql-flyway.md) records this accepted strategy. [application.yml](../backend/src/main/resources/application.yml) requires the runtime JDBC URL/credentials and separate Flyway credentials, sets `ddl-auto: validate`, and disables Open EntityManager in View.

## Database roles and provisioning

The normal Compose stack uses a dedicated Issunexa database and three distinct login identities:

| Identity | Configuration | Responsibilities |
| --- | --- | --- |
| Bootstrap administrator | `ISSUNEXA_POSTGRES_USER`, `ISSUNEXA_POSTGRES_PASSWORD`, `ISSUNEXA_POSTGRES_DB` | Official image initializes an empty cluster; administrator runs explicit role/ownership provisioning. These credentials are absent from the backend container. Retain the existing values when upgrading a volume. |
| Migration role | `ISSUNEXA_FLYWAY_USERNAME` (default `issunexa_migrator`), `ISSUNEXA_FLYWAY_PASSWORD` | Owns `public`, application tables/identity sequences and Flyway history; applies migrations and runtime grants. Not superuser, database owner, role administrator or database creator. |
| Runtime role | `ISSUNEXA_DB_USERNAME` (default `issunexa_runtime`), `ISSUNEXA_DB_PASSWORD` | JPA connections use this non-owner, non-superuser role for application queries. No membership in migration/admin roles. |

Set distinct, nonempty passwords outside source control. Migration/runtime names must be distinct lowercase identifiers (letters, digits, underscores, starting with a letter, at most 63 characters), outside the reserved `pg_` namespace and different from the administrator. Choose unused names for initial provisioning; an existing name is accepted only if marked by this bootstrap for this database. Roles are dedicated to one Issunexa database, not shared with unrelated applications. The administrator used for provisioning must be a superuser; customized databases/owners require a separately reviewed manual upgrade.

[Compose](../compose.yaml) runs [bootstrap.sh](../backend/database/bootstrap.sh) and [bootstrap.sql](../backend/database/bootstrap.sql) in a one-shot `database-bootstrap` service after PostgreSQL becomes healthy. The backend depends on successful completion. Provisioning creates only roles, transfers existing known schema/table ownership (including attached identity sequences), sets passwords and applies privileges in one transaction. It uses an advisory lock and is repeatable; it never creates/resets application tables or rewrites data. User-defined schemas outside `public`, unknown public relations/functions, unexpected owners, unmarked role collisions, elevated role flags or memberships fail before any changes commit. The schema check runs before persistent provisioning mutations, including database-wide PUBLIC temporary-privilege revocation; it allows `information_schema` and PostgreSQL system/internal/temporary schemas in the [reserved `pg_` namespace](https://www.postgresql.org/docs/18/sql-createschema.html). It does not attempt to repair a shared or customized database automatically.

Migration connections have a database-specific `issunexa.runtime_role` session setting established by bootstrap. [afterMigrate__runtime_privileges.sql](../backend/src/main/resources/db/migration/afterMigrate__runtime_privileges.sql) uses it to apply explicit grants after every Flyway `migrate()`, including when no versions are pending. Plain migration tests without provisioned roles have no such setting and require no grants. Bootstrap includes this same SQL in its administrative transaction, so existing runtime grants are restored before commit. The callback does not alter Flyway history or V1–V9 checksums. When adding tables or SQL functions, update the bootstrap expected-object allowlist and review the runtime grants deliberately; there are no blanket grants on future tables.

Runtime receives `CONNECT`, schema `USAGE`, table `SELECT`/`INSERT` on `users`, `tickets`, `ticket_comments` and `ticket_history_entries`, and `UPDATE` only on `tickets`. Required identity sequences allow `USAGE`/`SELECT` but not `setval`. The current application provisions accounts, mutates Tickets, and appends comments/history; it provides no account update or delete operation. Runtime deliberately cannot create roles/databases/schemas/tables, alter/drop objects, truncate/delete rows, update accounts or append-only records, access Flyway history, or assume the migration role. `PUBLIC` schema grants and database temporary-table privileges are revoked for this dedicated database; `PUBLIC` database connection permission is unchanged. Admin retains administrative access. Both migration and runtime lack superuser, role/database creation, replication and RLS bypass flags.

Spring Boot's [separate Flyway datasource](https://docs.spring.io/spring-boot/how-to/data-initialization.html) uses `spring.flyway.user/password` with the same database URL, while JPA uses `spring.datasource.username/password`. Hibernate still validates, never creates or updates the schema. Migration credentials remain available to the backend process for startup Flyway; this separation restricts runtime connections, not a fully compromised process that can read its environment.

### Fresh initialization

Copy `.env.example` only if `.env` is absent; fill all three password fields and retain the default distinct role names. From the repository root:

```sh
docker compose config --quiet
docker compose up --build -d
docker compose logs database-bootstrap backend
```

On a fresh disposable stack, PostgreSQL initializes, bootstrap provisions roles, then Flyway applies V1–V9 and the callback grants runtime access before Hibernate validates. No demo users are seeded. Normal `docker compose down` preserves the database. Never use `down -v` to upgrade an existing installation.

### Safe existing-installation upgrade

Do not replace the existing `.env` or change `ISSUNEXA_POSTGRES_DB/USER/PASSWORD` to the new runtime identity. PostgreSQL image initialization variables do not modify an initialized volume. Add only the migration/runtime username/password fields from `.env.example`, using new dedicated names and passwords. Confirm this is the normal existing project/volume and record a verified backup outside the repository before changing ownership.

Stop application writes and make a custom-format dump using the existing administrator (replace the output path with your own secure location):

```sh
docker compose stop backend frontend
umask 077
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > /safe/path/outside/repository/issunexa-before-privileges.dump
docker compose config --quiet
docker compose run --rm database-bootstrap
docker compose up --build -d
docker compose logs database-bootstrap backend
```

Verify successful Flyway validation/migration and Hibernate startup; check existing account/Ticket data and frontend-origin `/api/auth/csrf` as described in the README. The procedure transfers ownership of the known public tables and Flyway history without rewriting rows, constraints, checksums or identity counters. Attached sequences follow their table owner. Bootstrap applies the same grant SQL as the migration callback within its transaction, so a repeat run preserves existing runtime access even if the backend is already running. Keep application writes stopped during the initial ownership upgrade. Repeating the same step is safe; password changes to the new roles are applied by provisioning, while changing the original admin password still requires a separate administrator operation.

If provisioning fails, its transaction rolls back and the existing objects/data remain intact. Resolve the reported collision/unsupported customization before starting the backend; do not force ownership changes or reset the volume. Keep the backup and existing administrator access until the upgrade is independently reviewed. A privilege rollback, if needed, should be reviewed and applied through that administrator; do not restore a dump over live data merely to reverse ownership.

For a host-run backend, first use the same administrative provisioning against the target database (`PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` and the four migration/runtime variables), then run `sh backend/database/bootstrap.sh`. Set the five backend variables listed in [README](../README.md#runtime-database-configuration). Never run provisioning against an unrelated database or pass passwords as command-line arguments.

### Executable verification

[DatabasePrivilegesIntegrationTests](../backend/src/test/java/io/github/panteliszara/issunexa/shared/database/DatabasePrivilegesIntegrationTests.java) uses the real provisioning scripts and PostgreSQL 18.6. It verifies fresh startup, restricted JPA persistence/identity access, denial of administrative/DDL/history/destructive privileges, repeat provisioning with no pending migration, and existing V8/V9 databases with rows/checksums preserved, including a V8-to-V9 upgrade and V9 validation with no pending migration. It also checks role collisions and unexpected shared-schema objects fail without side effects. Non-public schema regressions cover empty/populated schemas and absent/existing Issunexa roles, verifying unchanged schema/table state, database ACLs, PUBLIC temporary privilege, role attributes/password verifiers and per-database role settings. Fresh bootstrap also runs with live PostgreSQL temporary/TOAST schemas present while retaining the runtime temporary-table restriction. Full-stack E2E uses the same bootstrap and restricted runtime connection with independently generated disposable admin/migration/runtime passwords. No verification uses or resets the normal volume.

## Ownership and migrations

Versioned SQL is under [backend/src/main/resources/db/migration](../backend/src/main/resources/db/migration/). With the Flyway dependencies in [pom.xml](../backend/pom.xml), backend startup applies the versioned migrations before JPA schema validation. Schema evolution is represented by migration files, not automatic Hibernate DDL creation.

| Migration | Current responsibility |
| --- | --- |
| [V1](../backend/src/main/resources/db/migration/V1__create_tickets_table.sql) | Tickets, identity key, timestamps and status/priority constraints |
| [V2](../backend/src/main/resources/db/migration/V2__create_users_table.sql) | Accounts, unique normalized email and password hashes |
| [V3](../backend/src/main/resources/db/migration/V3__add_user_role.sql) | Persisted roles; existing accounts receive REQUESTER |
| [V4](../backend/src/main/resources/db/migration/V4__add_ticket_requester.sql) | Nullable requester reference for historical Tickets |
| [V5](../backend/src/main/resources/db/migration/V5__index_ticket_requester.sql) | Requester lookup index |
| [V6](../backend/src/main/resources/db/migration/V6__add_ticket_assignee_and_version.sql) | Staff assignee reference and optimistic-lock version |
| [V7](../backend/src/main/resources/db/migration/V7__create_ticket_comments.sql) | Comments, foreign keys, body constraints and ordered-list index |
| [V8](../backend/src/main/resources/db/migration/V8__create_ticket_history.sql) | Lifecycle entries, event-shape constraints and ordered-list index |
| [V9](../backend/src/main/resources/db/migration/V9__add_ticket_category.sql) | Controlled category; existing Tickets receive OTHER |

## Relationships and invariants

- `users`: account identity, canonical email, display name, password hash and one of REQUESTER/AGENT/ADMIN.
- `tickets`: requester and optional assignee reference users; status, priority and category use constrained string values. New application Tickets require a requester even though the historical schema permits null requester references.
- `ticket_comments`: required Ticket and author references, nonblank text up to 4,000 characters, and creation time. Comments are append-only through the API and remain separate from Ticket lifecycle events.
- `ticket_history_entries`: required Ticket and actor references; event type determines previous/new status or claimed assignee fields. History starts with V8; older activity is not backfilled.

Identity columns generate IDs. SQL constraints enforce relationships and supported values; services/entities enforce workflow rules. Timestamps are represented by PostgreSQL `TIMESTAMPTZ` and Java `Instant`. Relevant mappings are [Ticket](../backend/src/main/java/io/github/panteliszara/issunexa/ticket/Ticket.java), [TicketComment](../backend/src/main/java/io/github/panteliszara/issunexa/ticket/comment/TicketComment.java) and [TicketHistoryEntry](../backend/src/main/java/io/github/panteliszara/issunexa/ticket/history/TicketHistoryEntry.java).

[TicketService](../backend/src/main/java/io/github/panteliszara/issunexa/ticket/TicketService.java) writes Ticket mutations and their history entry in one transaction. JPA `@Version` detects competing Ticket updates; the API maps optimistic-lock failures to `409`, and callers refresh before retrying. Versions remain internal to persistence rather than client-supplied concurrency tokens.

## Local data and verification boundaries

[Compose](../compose.yaml) uses `postgres:18.6` and the `postgres_data` volume mounted at `/var/lib/postgresql`. Normal container shutdown preserves that volume; removing the volume deletes its database data. Credentials and initialization instructions remain in the [README](../README.md#full-stack-docker-environment).

Backend integration tests use disposable PostgreSQL containers with Testcontainers. Full-stack E2E uses a separate Compose project/volume and generated credentials, with synthetic users inserted after startup migrations. [run-e2e.sh](../frontend/e2e/run-e2e.sh) excludes the normal `.env` and removes its own database volume afterward.

This provides database-specific constraint and migration coverage but requires Docker/PostgreSQL for integration verification. The repository's local volume is not a documented production backup, retention or disaster-recovery arrangement.
