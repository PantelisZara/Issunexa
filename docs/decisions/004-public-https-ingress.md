# ADR 004: explicit public HTTPS and ingress metadata

Status: accepted for application preparation; public deployment and provider-edge verification remain pending.

## Problem and current behavior

Local Compose uses HTTP, Nginx and a separate backend. Forwarded headers are ignored globally; login source attribution accepts Nginx's single overwritten X-Real-IP only from its configured TCP peer. Sessions and CSRF already rotate at login and invalidate at logout. Public TLS termination needs secure cookies and correct external metadata without allowing a visitor to forge the source address, scheme or host.

## Options and decision

Keep the local contract unchanged. Add an opt-in public profile with one configured canonical HTTPS origin, Secure/HttpOnly/SameSite=Lax host-only cookies, cookie-only tracking and a 20-minute idle timeout. The dedicated ingress must enforce HTTPS and block direct public access to the Java listener.

Set Tomcat's external scheme, secure flag, host and port statically from that validated origin. Retain forward-headers-strategy=none. Validate the effective forwarding/session/explorer settings at startup so an environment or profile override cannot silently weaken these invariants. Existing APIs use relative paths and JSON errors rather than login redirects. Disable OpenAPI/Swagger in this profile; no management endpoint is added.

Choose ingress explicitly:

- PEER: use the actual socket peer. Behind an unknown proxy, this produces shared source budgets and must not be described as accurate visitor attribution.
- RENDER_FREE: accept exactly one valid numeric CF-Connecting-IP for login source attribution, and otherwise fall back to the peer. Ignore all other forwarding headers. Require an onrender.com origin and no simultaneous Docker trusted proxy.

The Render mode relies on an infrastructure contract: [Render says Cloudflare overwrites CF-Connecting-IP on every public web-service request](https://render.com/articles/host-pocketbase-on-render), and [Free services cannot receive private-network requests](https://render.com/docs/private-network). It is not safe for an arbitrary host, a directly reachable backend, or a different tier/topology. A configured hostname alone does not prove that contract. [Render terminates TLS and redirects HTTP at its edge](https://render.com/docs/web-services). [Tomcat's fixed connector metadata](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html) avoids interpreting client-supplied forwarding chains.

## Rejected alternatives and security implications

Global FRAMEWORK/NATIVE forwarding and broad private-IP trust would affect more than source attribution and require an authenticated proxy identity we have not established. Leftmost X-Forwarded-For can contain caller input; Render's current guidance explicitly warns against it. Always trusting CF-Connecting-IP on local or generic deployments would also be unsafe. Shared public requester/staff accounts are outside this decision and remain prohibited.

Static metadata does not encrypt the internal listener. The TLS ingress and direct-access restriction are mandatory. The header parser cannot prove the provider overwrote a request; live edge probes are still required. NAT and [Cloudflare Worker source semantics](https://developers.cloudflare.com/fundamentals/reference/http-headers/#cf-connecting-ip) can share source budgets. Existing per-account budgets remain independent.

## Operations, resources and cost

No new dependency, account, service or financial commitment is introduced. The profile can run in the existing Java application. Credentials and canonical origin belong in deployment configuration. Incorrect origin fails configuration startup. A process restart loses sessions and process-local throttle state; single-instance limits remain. Public demo tenancy, lifecycle quotas, memory limits, database provisioning and readiness are separate prerequisites.

## Correctness and acceptance

Use real embedded Tomcat/PostgreSQL tests for emitted cookie flags, fixed metadata despite hostile Host/forwarding headers, CSRF/session rotation and logout revocation. Test invalid configuration, missing/malformed/duplicate source headers, IPv4/IPv6, fallback and unchanged direct/Docker trust. Full backend and existing full-stack suites must pass.

Before public acceptance, verify the actual HTTP-to-HTTPS edge redirect, cookies/HSTS/CSRF in Browser, spoofed forwarding headers through the public URL and independent source networks. Local tests establish the application contract, not Render deployment success. Keep deployment blocked if the documented topology cannot be confirmed.

## Rollback

Deactivate the public profile only after removing public ingress; the default local HTTP stack remains available. Roll back the scoped PR through normal review if needed. Never expose the local profile publicly as a workaround, weaken CSRF, or trust broader headers to make a test pass.
