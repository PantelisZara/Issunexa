# ADR 003: PostgreSQL with Flyway-owned schema evolution

**Status:** Accepted — retrospective record of the current implementation.

This records an existing decision embodied in the repository. No historical decision date, meeting or alternatives evaluation is asserted.

## Context

Tickets, accounts, comments and lifecycle entries have relational identities and foreign-key relationships. The implementation requires constraints, transactional Ticket/history writes and schema changes that apply to both existing and fresh databases.

## Decision

Use PostgreSQL for durable application data, Spring Data JPA for persistence access and versioned Flyway SQL for schema evolution. Hibernate validates mappings rather than creating/updating the schema. Integration tests exercise PostgreSQL through Testcontainers; E2E runs use a separate disposable PostgreSQL volume.

## Consequences

- SQL migrations define the schema and explicit transformations for existing data, including role/category defaults and nullable historical ownership.
- Foreign keys, checks and unique constraints complement application validation.
- Ticket and lifecycle writes share transactions; optimistic locking detects competing Ticket changes.
- Migration SQL and tests are tied to PostgreSQL behavior rather than an interchangeable in-memory test database.
- Schema evolution requires maintaining migrations and entity mappings together; durable local data requires managing its volume lifecycle separately from containers.

## Evidence

- [Dependencies](../../backend/pom.xml), [configuration](../../backend/src/main/resources/application.yml) and [versioned migrations](../../backend/src/main/resources/db/migration/).
- [Ticket mapping](../../backend/src/main/java/io/github/panteliszara/issunexa/ticket/Ticket.java) and [Compose persistence](../../compose.yaml).
- [Database responsibilities](../database.md) and [architecture](../architecture.md).
