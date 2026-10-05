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

## Bounded login attempts

[LoginAttemptLimiter](../backend/src/main/java/io/github/panteliszara/issunexa/auth/LoginAttemptLimiter.java) uses two independent fixed windows in the single backend process. These conservative defaults suit the low-volume portfolio/local runtime: five account failures accommodate typing mistakes while limiting guesses; 30 source requests per minute allow normal sign-in use while bounding BCrypt work from one source. They are not a measured public-production capacity guarantee.

| Budget | Default | Configuration environment variable |
| --- | --- | --- |
| Account failed/in-flight verifications | 5 in 5 minutes | `ISSUNEXA_LOGIN_ACCOUNT_LIMIT`, `ISSUNEXA_LOGIN_ACCOUNT_WINDOW` |
| Source login POSTs, including successes, malformed bodies, invalid CSRF and account-throttled requests | 30 in 1 minute | `ISSUNEXA_LOGIN_SOURCE_LIMIT`, `ISSUNEXA_LOGIN_SOURCE_WINDOW` |
| Retained account identifiers | 2,048 | `ISSUNEXA_LOGIN_MAX_ACCOUNT_ENTRIES` |
| Retained source addresses | 512 | `ISSUNEXA_LOGIN_MAX_SOURCE_ENTRIES` |

The corresponding Spring properties are under `issunexa.login-throttle`. Limits/capacities must be positive; windows must be between one second and one hour. Compose forwards the settings above; defaults are also in [.env.example](../.env.example). No additional dependency or shared service is used.

Account keys use the same strip/lowercase normalization as database authentication. Existing and unknown identifiers consume identical budgets and receive the same generic `401` or `429` contract. The limiter does not query account existence. The unchanged [DaoAuthenticationProvider](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/dao-authentication-provider.html) and delegating BCrypt encoder perform credential verification; the unknown-user timing defense and generic credential errors remain enabled.

A window starts with its first admitted request and ends at its original deadline. The fifth failed verification still returns `401`; further account attempts receive `429` before lookup/BCrypt. Slots are reserved atomically before verification, so failures plus in-flight verifications cannot exceed the limit. BCrypt runs outside the limiter monitor. Successful authentication clears completed failures for that identifier, while retaining other in-flight reservations and the source request count. A completion from an expired window cannot change a replacement budget.

Rejected requests never extend deadlines. At expiry the next request starts a fresh budget automatically; no persisted account lock or manual unlock exists. Successful login removes an unused account entry. Other expired entries are removed lazily on the next access to their budget map, so idle state remains bounded by the capacities above. At capacity a new key is denied until the earliest retained deadline rather than evicting a live entry and allowing churn to bypass limits. Existing keys continue to use their budgets. Account input is already bounded by the login DTO's 254-character validation.

Both throttling paths return `429 application/problem+json`, `Cache-Control: no-store`, and a positive integer `Retry-After` delay in seconds, rounded up to the relevant deadline (or earliest capacity expiry). The body contains only `about:blank`, status/title, the fixed login instance and a generic message; it identifies neither the account nor which budget was exceeded. Admission after that delay remains subject to the other budget and intervening traffic. Since source admission precedes CSRF/body parsing, an exhausted source can receive `429` even with missing CSRF; otherwise normal `403` and validation `400` contracts remain. Throttling does not authenticate, rotate the session, or invalidate its CSRF token. Bootstrap, session reads and logout are outside the login budget.

The source filter identifies POST `/api/auth/login` with Spring Security's `PathPatternRequestMatcher` and the default parser shared by this application's MVC mappings. Matching uses the parsed application path rather than a raw servlet-path comparison: matrix parameters and encoded path segments cannot produce an unmetered login alias. Production `StrictHttpFirewall` also rejects semicolon/path-parameter requests before authentication. [Path integration tests](../backend/src/test/java/io/github/panteliszara/issunexa/auth/api/LoginPathThrottleIntegrationTests.java) permit semicolons only in their test configuration to prove actual MVC login mapping still shares the source budget and stops BCrypt at its threshold; the normal-chain and real-stack regressions separately verify production rejection. No production firewall allowance is added.

The frontend displays a fixed generic throttling message with validated delta-seconds when available, clears the submitted password, stays on the sign-in form and retains current CSRF metadata. Missing/malformed/excessive `Retry-After` uses a generic wait message. There is no credential replay, automatic retry or countdown-driven mutation; a user must explicitly submit again.

### Source and proxy trust

Standalone backend/Vite development defaults to the TCP peer (`request.getRemoteAddr()`); no forwarded header is trusted. Vite's proxied clients therefore share its peer budget. `server.forward-headers-strategy: none` preserves that peer for the login-specific trust check.

The normal [Compose configuration](../compose.yaml) explicitly sets `ISSUNEXA_LOGIN_TRUSTED_PROXY=frontend`. [LoginSourceResolver](../backend/src/main/java/io/github/panteliszara/issunexa/auth/LoginSourceResolver.java) accepts one numeric `X-Real-IP` only if the actual TCP peer matches an address resolved for that configured service name. This uses the existing Docker service DNS/network boundary, whose service names and membership must remain controlled by the local operator. Do not configure a client-controlled name, a public gateway, or a broad private-address trust range. Untrusted peers' `X-Real-IP`, `X-Forwarded-For` and `Forwarded` headers have no effect.

[Nginx](../frontend/nginx.conf) overwrites `X-Real-IP` with its socket peer `$remote_addr` and replaces `X-Forwarded-For` rather than preserving a supplied chain, following the [Nginx proxy header contract](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_set_header). No Nginx real-IP rewriting is configured. Header lists, duplicate fields, names, ports and scoped/invalid addresses are rejected; IPv4/IPv6 literals are canonicalized. Missing/malformed headers or DNS lookup failure fall back to a shared proxy-peer budget. Service address resolution follows the JVM's DNS cache: container replacement can temporarily aggregate requests until resolution refreshes. Direct loopback backend requests cannot assert an arbitrary frontend source.

### Limitations

State is process-local and resets on backend restart. Multiple backend instances would have independent budgets and sessions; this policy supports the documented single-instance runtime. Fixed windows allow bursts around boundaries. NAT clients (and any extra proxy placed before Nginx) share the address Nginx actually sees. Capacity exhaustion or sustained distributed targeting can temporarily deny legitimate sign-ins; denied requests cannot prolong a window, but an attacker can consume new windows repeatedly. These bounded windows avoid a permanent account lock, not all denial of service. This is login-abuse control, not general API/network capacity protection. Before public deployment, reassess traffic capacity, TLS, proxy topology and operational ownership rather than widening header trust.

Deterministic [limiter tests](../backend/src/test/java/io/github/panteliszara/issunexa/auth/LoginAttemptLimiterTests.java), [source resolver tests](../backend/src/test/java/io/github/panteliszara/issunexa/auth/LoginSourceResolverTests.java) and [authentication integration tests](../backend/src/test/java/io/github/panteliszara/issunexa/auth/api/AuthenticationIntegrationTests.java) cover thresholds, clock-driven recovery, success/concurrency, bounds and session/CSRF contracts without sleeps. [Frontend tests](../frontend/src/app/App.test.tsx) cover explicit recovery and safe messages; the [real-stack throttling journey](../frontend/e2e/auth-throttle.spec.ts) exercises both existing and unknown identifiers through Nginx.

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

[Database privilege separation](database.md#database-roles-and-provisioning) gives bootstrap administration, Flyway schema ownership and JPA runtime traffic distinct roles. The backend receives migration/runtime credentials for separate connections, never the bootstrap administrator's password. Runtime cannot administer roles/databases, modify schema, truncate/delete data or access Flyway history; grants preserve normal account creation, Ticket mutations and append-only comments/history. This limits a compromised runtime connection; a process compromise can still expose startup migration credentials. Existing installations need the documented non-destructive provisioning step, not a volume reset.

Session cookies require CSRF coordination on browser writes, and client route guards cannot replace server authorization. These are consequences of the current design, not evidence of a completed production security assessment. See [authentication integration tests](../backend/src/test/java/io/github/panteliszara/issunexa/auth/api/AuthenticationIntegrationTests.java) and [Ticket authorization tests](../backend/src/test/java/io/github/panteliszara/issunexa/ticket/api/TicketAuthorizationIntegrationTests.java) for executable contracts.

[Dependency remediation](dependency-security-012.md) records the current Tomcat/Jackson maintenance overrides, exact resolved versions, every dependency advisory from the baseline audit, vendor cross-checks and reachability limits. Keep these families coherent through the managed version properties and repeat the documented verification when updating them.
