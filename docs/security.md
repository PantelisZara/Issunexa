# Security model

This document describes implemented authentication and authorization boundaries. The [session ADR](decisions/002-session-authentication.md) records the accepted approach; [architecture](architecture.md) describes the local runtime.

## Authentication lifecycle

[SecurityConfiguration](../backend/src/main/java/io/github/panteliszara/issunexa/shared/security/SecurityConfiguration.java) uses database-backed authentication, an `HttpSessionSecurityContextRepository` and an `HttpSessionCsrfTokenRepository`. The JSON [authentication controller](../backend/src/main/java/io/github/panteliszara/issunexa/auth/api/AuthenticationController.java) delegates session/CSRF changes to the configured strategies and explicitly saves the security context.

| Step | Current contract |
| --- | --- |
| Bootstrap | Public `GET /api/auth/csrf` establishes/retrieves CSRF metadata; `GET /api/auth/session` requires authentication |
| Login | Public `POST /api/auth/login` accepts email/password but still requires the current session's CSRF token; success returns `204`, changes the session ID and invalidates the previous token |
| After login | Fetch fresh CSRF metadata and the authenticated account; both GET responses use `Cache-Control: no-store` |
| Mutations | Retain the cookie and send the token in the returned `headerName`; this includes Ticket writes and logout |
| Logout | Authenticated `POST /api/auth/logout` clears CSRF/security context and invalidates the session; the client prepares fresh anonymous CSRF state |

Form login, HTTP Basic and the built-in logout endpoint are disabled in favor of the REST controller. Accounts are provisioned through [UserAccountService](../backend/src/main/java/io/github/panteliszara/issunexa/user/UserAccountService.java); there is no public registration or password-recovery API. [DatabaseUserDetailsService](../backend/src/main/java/io/github/panteliszara/issunexa/shared/security/DatabaseUserDetailsService.java) loads normalized email, the password hash and role authorities. [PasswordEncodingConfiguration](../backend/src/main/java/io/github/panteliszara/issunexa/shared/security/PasswordEncodingConfiguration.java) supplies a delegating password encoder.

The [frontend session provider](../frontend/src/auth/AuthProvider.tsx) keeps CSRF/account state in memory and sends credentialed requests. It does not store JWTs or authentication state in local/session storage. No shared backend session store is configured, so session continuity across multiple backend instances is not provided by the current configuration.

## Authorization

All `/api/tickets/**` requests require authentication. The service resolves the actor from the principal; requester, author and assignee identities are not accepted from client-selected account fields.

| Operation | REQUESTER | AGENT / ADMIN |
| --- | --- | --- |
| Create Ticket | Allowed; actor becomes requester | Allowed; actor becomes requester |
| List/read Ticket | Own Tickets only | All Tickets |
| Comment/read comments or history | Only on visible Tickets | All Tickets |
| Claim or change status | Forbidden | Allowed within Ticket workflow rules |

[TicketService](../backend/src/main/java/io/github/panteliszara/issunexa/ticket/TicketService.java) applies ownership restrictions to reads/listing and method-level role checks to claim/status mutations. Staff mutations also check the current persisted role, so revocation takes effect even when a session retains its login-time authorities. Hidden, unowned historical and missing Tickets use the same `404` contract for requesters. Comment/history services reuse that visibility boundary. ADMIN currently shares Ticket staff capabilities with AGENT; a user-administration API is not implemented.

Missing authentication yields `401`; access denial or missing/invalid CSRF yields `403`. Invalid credentials have a generic `401` message. [SecurityProblemHandler](../backend/src/main/java/io/github/panteliszara/issunexa/shared/security/SecurityProblemHandler.java) and [ApiExceptionHandler](../backend/src/main/java/io/github/panteliszara/issunexa/shared/web/ApiExceptionHandler.java) produce Problem Details. CSRF runs before unsafe controller operations, so an anonymous unsafe request with invalid CSRF can receive `403` rather than `401`.

## Data and runtime boundaries

Response DTOs limit exposed account information: assignee/comment-author/history-actor summaries contain ID and display name, while the session response includes the current account's email and role. Ticket responses omit password hashes, requester entities and internal optimistic-lock versions.

The normal [Compose stack](../compose.yaml) uses local HTTP, a required database password and loopback backend/database bindings. [Nginx](../frontend/nginx.conf) forwards the existing session/CSRF contract and disables proxy caching for `/api`; it is not an authorization layer. Its static-file health check does not prove API readiness. E2E-only synthetic accounts and credentials belong to the [disposable test runner](../frontend/e2e/run-e2e.sh), not the normal application database.

Session cookies require CSRF coordination on browser writes, and client route guards cannot replace server authorization. These are consequences of the current design, not evidence of a completed production security assessment. See [authentication integration tests](../backend/src/test/java/io/github/panteliszara/issunexa/auth/api/AuthenticationIntegrationTests.java) and [Ticket authorization tests](../backend/src/test/java/io/github/panteliszara/issunexa/ticket/api/TicketAuthorizationIntegrationTests.java) for executable contracts.

[Dependency remediation](dependency-security-012.md) records the current Tomcat/Jackson maintenance overrides, exact resolved versions, every dependency advisory from the baseline audit, vendor cross-checks and reachability limits. Keep these families coherent through the managed version properties and repeat the documented verification when updating them.
