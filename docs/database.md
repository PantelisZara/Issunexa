# Database and schema evolution

PostgreSQL stores application data; Flyway owns the schema and Hibernate validates mappings. The [persistence ADR](decisions/003-postgresql-flyway.md) records this accepted strategy. [application.yml](../backend/src/main/resources/application.yml) requires `ISSUNEXA_DB_URL`, `ISSUNEXA_DB_USERNAME` and `ISSUNEXA_DB_PASSWORD`, sets `ddl-auto: validate`, and disables Open EntityManager in View.

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
