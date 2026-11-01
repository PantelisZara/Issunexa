# ADR 007: Expiring isolated synthetic demo workspaces

Status: accepted for the backend foundation; public exposure remains unverified.

## Problem and current behavior

Ordinary requester ownership is enforced, but persisted staff roles can access all tickets. Shared recruiter accounts would expose visitors to each other. Account registration and demo lifecycle controls do not exist. Status transitions can cycle, so limiting tickets alone does not bound history.

## Decision

Opt-in `demo` requires `public` and a dedicated database containing only consistent synthetic workspaces. Each visitor session binds to one persisted workspace with exactly one REQUESTER and one AGENT, never ADMIN. The server generates identities and passwords; password authentication rejects demo accounts in every profile. Fixed CSRF-protected start/requester/agent endpoints reuse session rotation, context persistence and CSRF retirement. Workspace identifiers never come from the client.

Every ticket access resolves the persisted actor and absolute workspace expiry. Requester ownership remains; staff are restricted by the requester's workspace. Comment/history reuse that boundary. PostgreSQL transaction advisory locks serialize admission globally and writes within each workspace, without granting runtime UPDATE on workspace lifetime columns. Cleanup uses the same locks. Hard limits are 50 retained workspaces, 2 accounts, 8 tickets, 20 comments and 128 history entries per workspace, including seed rows, and 30-minute absolute expiry. The history cap bounds repeated status cycles without changing ordinary workflow.

Admission has bounded source/global in-memory budgets using the existing verified source resolver. A synchronized embedded Tomcat manager caps all sessions, including anonymous CSRF sessions, at 100. Generic exhaustion responses contain no identifiers. This deployment contract supports one application process; PostgreSQL quotas remain serialized across connections.

A migration-owned zero-argument SECURITY DEFINER cleanup function removes at most 5 expired, internally consistent synthetic workspaces per call using the database clock. Its search_path is fixed to pg_catalog and all application objects are schema qualified. PUBLIC execution is revoked; runtime receives only explicit EXECUTE, never DELETE/DDL or workspace UPDATE. Strict and managed provisioning validate this exact routine shape, ownership and ACL. Ticket requester/category/creation/ID columns are immutable at the SQL privilege boundary, matching the existing ORM mappings: runtime UPDATE is limited to title, description, status, priority, assignee_id, version and updated_at. This prevents cleanup provenance from being reassigned on ordinary legacy tickets. Expected column grants are explicitly validated; unknown/PUBLIC/outsider/grant-option column ACLs are rejected. Legacy table UPDATE from a V9 database is stripped by the callback before these narrow grants are applied. Cleanup runs on start and bounded request activity, with no periodic database polling. Expiry denial is independent of cleanup.

## Alternatives and implications

Shared accounts fail visitor isolation. Per-visitor databases, schemas or services add unnecessary infrastructure and operating cost. Scheduled resets could delete active work and prevent database sleep. Runtime DELETE would unnecessarily enlarge the trust boundary. The selected design uses existing Spring/JPA/JDBC/PostgreSQL features and no new production dependency or paid service. Idle expired data may remain until later activity; total retained storage stays bounded. Restart loses sessions and local admission budgets; retained workspaces expire normally and are never adopted by another visitor.

## Measurement and rollback

Real PostgreSQL and HTTP tests cover cross-visitor access on every path, forged selections, password bypass, CSRF/session replay/logout, expiry, concurrent quotas, session exhaustion, cleanup preservation and privilege denials. Existing ordinary authorization and strict/managed provisioning tests remain required. Full backend, UI/E2E, official Browser, hosted CI, independent review and eventual provider-edge acceptance are separate gates.

Disable demo and stop the dedicated demo process to roll back behavior. Additive V10 preserves V1–V9 and ordinary rows; no down migration or automatic data destruction is provided. Never repurpose a database containing ordinary accounts for demo mode.
