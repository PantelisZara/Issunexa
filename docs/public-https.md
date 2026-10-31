# Public HTTPS preparation

This is an application security contract, **not a deployment completion claim**. The normal Compose stack remains local HTTP. Do not expose it publicly or activate a hosted demo before isolated synthetic workspaces, quotas, lifecycle cleanup, database provisioning and resource limits are implemented and verified. Creating accounts or exposing a new service also requires explicit maintainer authorization.

## One external origin

The opt-in Spring profile is named **public**. It requires ISSUNEXA_PUBLIC_ORIGIN to be a canonical HTTPS origin, for example https://your-service.onrender.com. No user information, query, fragment, application subpath or non-443 port is accepted.

Keep the existing runtime and Flyway database credentials separate and outside Git/images/logs. The profile does not provision a database or accept an administrator password. It is not automatically enabled by the local Compose configuration.

For a prepared deployment, set SPRING_PROFILES_ACTIVE=public and select one ingress contract using ISSUNEXA_PUBLIC_INGRESS:

| Value | Trust and limitation |
| --- | --- |
| peer (default) | Actual TCP source. Behind an unknown proxy, visitors share its throttle budget. This is safe fallback, not accurate visitor attribution. |
| render-free | Only for a Render Free public web service on its onrender.com origin, after verifying the documented Cloudflare overwrite and lack of alternate ingress. One numeric CF-Connecting-IP is accepted for login throttling. |

Do not set ISSUNEXA_LOGIN_TRUSTED_PROXY in this profile. It is the separate Docker Nginx contract. Other proxy tiers, custom domains and direct public access require a separately reviewed contract rather than an invented trusted range.

## Cookies, metadata and headers

Public sessions use Secure, HttpOnly, host-only cookies with path /, SameSite=Lax, cookie-only tracking and at most 20 minutes of idle time. CSRF remains session-bound, including login/logout. Session IDs and tokens rotate at login; logout revokes the server-side session. Browser/API requests remain same-origin and relative; no cross-origin CORS setting is introduced.

The ingress must terminate TLS, redirect public HTTP to HTTPS and prevent direct access around it. The Java listener remains internally HTTP. Tomcat's external scheme/secure/host/port are fixed from configuration, so Spring can emit its secure-request headers without trusting visitor-supplied forwarding metadata. The profile does not itself implement a TLS listener.

Global forwarding remains NONE; effective overrides that weaken forwarding/session settings or re-enable the API explorer fail startup. X-Forwarded-For, Forwarded, X-Forwarded-Host, X-Forwarded-Proto and X-Real-IP cannot change the public-mode external metadata. In Render mode only CF-Connecting-IP is considered for the login source, subject to a single valid literal; missing, duplicate, list, hostname, port, scoped or oversized values fall back to the actual peer.

OpenAPI/Swagger is disabled in this profile. No management endpoint is added. Throttles and sessions remain process-local; restart logs out visitors and multiple independent backend instances are not supported by this contract. NAT and Cloudflare Workers can share source identities.

## Verification boundary

Real embedded-Tomcat/PostgreSQL tests exercise emitted cookie flags, session/CSRF rotation, logout revocation, URL-session rejection, hostile Host/forwarding headers, fixed external metadata and disabled explorers. Resolver tests cover direct/Docker behavior and strict Render-mode parsing. Configuration tests cover invalid origins and forbidden overrides.

Before declaring a public deployment verified, test the actual public URL with the official in-app Browser: HTTP redirect, HTTPS cookie/HSTS behavior, sign-in/reload/logout and protected writes. Probe forged CF-Connecting-IP/X-Real-IP/forwarding chains and confirm they cannot select attacker-controlled throttle identities. Verify distinct budgets from independent public source networks, with IPv6 where available. Do not add a public IP diagnostic endpoint to perform those checks. Local tests do not prove the provider's current topology.

The [ADR](decisions/004-public-https-ingress.md) records alternatives, primary provider documentation, cost/operational implications and rollback.

The [single-service artifact](single-service-artifact.md) packages the existing UI and API behind this contract and documents local resource and browser verification. Public-edge acceptance remains required.
