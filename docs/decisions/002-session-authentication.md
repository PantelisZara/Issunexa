# ADR 002: Server-side sessions with CSRF protection

**Status:** Accepted — retrospective record of the current implementation.

This records an existing decision embodied in the repository. No historical decision date, meeting or alternatives evaluation is asserted.

## Context

The browser client calls relative API URLs through Nginx or the Vite proxy. It needs authenticated account identity and protection for writes performed with browser-managed session cookies.

## Decision

Use database-backed login and an HTTP-session security context. The browser retains the session cookie; the frontend holds CSRF metadata and account state in memory. It obtains a CSRF token before login, refreshes it after login/logout, and sends the server-specified header on unsafe requests. Browser-persisted JWTs and bearer-token authentication are not part of this implementation.

## Consequences

- Reloads restore client identity through CSRF/session requests rather than persisted browser authentication state.
- Login changes the session ID and invalidates the prior CSRF token; logout invalidates the session.
- Browser mutations require cookie/token coordination, including explicit recovery when session state changes.
- Authentication is stateful. No shared session store is configured for continuity across backend instances.
- Client route guards provide presentation behavior; ownership and staff permissions remain server responsibilities.

## Evidence

- [SecurityConfiguration](../../backend/src/main/java/io/github/panteliszara/issunexa/shared/security/SecurityConfiguration.java) and [AuthenticationController](../../backend/src/main/java/io/github/panteliszara/issunexa/auth/api/AuthenticationController.java).
- [AuthProvider](../../frontend/src/auth/AuthProvider.tsx), [auth API](../../frontend/src/auth/authApi.ts) and [credentialed request helper](../../frontend/src/api/apiRequest.ts).
- [Security model](../security.md) and [frontend strategy](../frontend.md).
