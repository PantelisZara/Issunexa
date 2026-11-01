# ADR 009: verify managed transport in each libpq client

Status: Accepted for implementation; actual provider deployment acceptance remains required.

## Problem and observed behavior

Neon's direct public endpoint accepted an operator connection with libpq18 verify-full and an explicit trusted CA, while pg_stat_ssl.ssl was false for the current PostgreSQL backend. Its account-selected database was empty and the non-superuser owner passed ownership/CREATEROLE checks. Provisioning and recovery SQL guards used that backend view as a client TLS requirement and would abort. Deployment stopped before provisioning or public exposure. This observation does not establish the provider's internal proxy-to-compute encryption.

## Options

- Keep the backend TLS predicate: safe refusal, but incompatible with this observed provider topology; it also cannot prove client hostname or CA verification.
- Add a proxy skip flag or caller-supplied TLS boolean: rejected because it asserts transport rather than enforcing it.
- Probe TLS separately then allow a later connection: rejected because a preflight does not constrain the operation's connection.
- Parse human-readable psql connection output or add a new client implementation: unnecessary complexity when libpq already enforces the required handshake.
- Require verified TLS in every constrained libpq client: selected.

## Decision and security implications

The supported entry points are provision-managed.sh and recovery-managed.sh with trusted operator tools and environment. PGSSLMODE must be verify-full; each wrapper forces and exports PGGSSENCMODE=disable so GSS encryption cannot take precedence over certificate verification. Direct TCP hosts are required; filesystem sockets, abstract sockets beginning with @, lists and pooler endpoints are rejected. Strict database/user identifiers and cleared service/PGOPTIONS variables prevent connection-string overrides; psql -X prevents startup-file reconnection.

Every actual psql connection, including the transactional restore, must complete libpq certificate/hostname verification before executing any statement. The separate pg_dump connection inherits the same mandatory settings and performs its own handshake. SQL guards retain version, database, role, ownership, namespace, ACL, empty-target and transactional rollback checks. They no longer use pg_stat_ssl as evidence about the external client hop. No bypass flag or TLS assertion is introduced. Internal SQL files are not independent operator entry points.

This establishes the operator-to-configured-endpoint boundary, including a provider's authenticated TLS proxy. It does not attest to the provider-internal hop or replace provider security assessment. Java application connections still require their separate pgJDBC verify-full/trusted-CA configuration.

## Operations, cost and rollback

No production dependency, provider resource, paid service, background query or new runtime process is added. A minimal client image without CA roots needs an explicit trusted read-only CA mount. Credentials stay in the protected operator environment, outside command arguments, output, Git and application artifacts. Normal archive-path output remains unchanged.

A failure leaves provisioning rolled back or a restore target empty, with the original source preserved. Reverting this operator-only change restores the old refusal through a TLS proxy; it does not justify disabling certificate verification or replacing database contents. App deployment remains gated on actual provider provisioning, migration/runtime least privilege, recovery and external HTTPS acceptance.

## Verification

Retain existing direct TLS provisioning/rotation/privilege and trusted recovery coverage. Add a real disposable TLS-terminating proxy whose PostgreSQL backend reports ssl=false, and verify provisioning, rerun and recovery. Test wrong/missing CA, hostname mismatch, plaintext servers, weaker TLS settings, abstract sockets and connection-string/service/GSS override attempts. Failed transport must preserve catalog/data snapshots and leave no accepted backup. Complete affected backend verification and independent review before integration; actual Neon acceptance follows the reviewed change.

## Primary sources

- [libpq sslmode, GSS precedence and socket behavior](https://www.postgresql.org/docs/18/libpq-connect.html)
- [libpq certificate and hostname verification](https://www.postgresql.org/docs/18/libpq-ssl.html)
- [PostgreSQL backend SSL statistics](https://www.postgresql.org/docs/18/monitoring-stats.html#MONITORING-PG-STAT-SSL-VIEW)
- [Neon secure connections and proxy boundary](https://neon.com/docs/security/security-overview)
