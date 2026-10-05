# ADR 001: Modular monolith

**Status:** Accepted — retrospective record of the current implementation.

This records an existing decision embodied in the repository. No historical decision date, meeting or alternatives evaluation is asserted.

## Context

Ticket operations depend on persisted accounts, visibility rules and lifecycle history. A Ticket mutation and its history entry share a transaction. The backend currently has one Spring Boot entry point, Maven project and deployable Java application.

## Decision

Keep business capabilities in one backend application, organized into `auth`, `user`, `ticket` (including comments/history) and `shared` packages. The React/Nginx client and PostgreSQL are separate runtime components; business packages communicate through in-process calls rather than microservice APIs.

## Consequences

- Ticket/history changes can use ordinary database transactions and local service calls.
- Business changes share one application build and deployment lifecycle.
- Packages share a context and schema; separation relies on code conventions rather than enforced module isolation.
- Individual business packages cannot be deployed or scaled independently in the current design.

These are consequences of the implemented structure, not a record of evaluated or rejected architectures.

## Evidence

- [Application entry point](../../backend/src/main/java/io/github/panteliszara/issunexa/IssunexaApplication.java), [Maven build](../../backend/pom.xml) and [backend image](../../backend/Dockerfile).
- [TicketService](../../backend/src/main/java/io/github/panteliszara/issunexa/ticket/TicketService.java) coordinates accounts, Tickets and history within service transactions.
- [Architecture](../architecture.md) and [backend boundaries](../backend.md).
