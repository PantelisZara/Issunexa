# ADR 005: same-origin Java artifact

Status: accepted for local implementation; hosted deployment and safe public-demo acceptance remain pending.

## Problem and current behavior

Local Compose deploys Nginx, Java and PostgreSQL separately. The selected free-host candidate offers a single low-memory web service. Keeping the React UI and REST API on one origin preserves the existing cookie/CSRF model without requiring a second host or CORS.

## Decision and alternatives

Add a root multistage Dockerfile that compiles the existing React application and packages its assets into Spring Boot's classpath static resources. Retain the existing local Compose/Nginx workflow. The final image uses the existing Java 21, layered-jar and non-root conventions, and contains no Node/npm tooling.

Under the public profile, explicitly allow only known browser routes, asset namespaces and the manifest. Forward known SPA routes to index.html; unknown API and missing assets remain errors. Do not add a catch-all HTML fallback. Authorization stays on the existing API services, while client route guards retain their presentation role.

Rejected alternatives: separate free frontend/backend hosts introduce cross-origin session complexity; running Nginx and Java in the same container needs process supervision and extra memory; copying the Docker-DNS Nginx setup to a provider does not establish a correct ingress contract. Replacing React or splitting the backend would add unrelated work.

## Security and operations

Reuse ADR004's explicit HTTPS/ingress contract. A compiled UI is not safe public demo tenancy: unrestricted staff accounts remain unsuitable for sharing. The image accepts the host PORT, limits worker threads and the JDBC pool, and caps JVM allocations for a 512 MB container. The public JDBC pool has at most three connections, zero minimum idle connections, a 60-second idle timeout and no keepalive queries. Idle retirement allows a managed database to suspend; new application work reconnects. A real PostgreSQL test verifies retirement and reconnect with a shorter test-only idle threshold. Provider suspension and wake-up still require hosted verification.

Those settings require measurement; they are not a demonstrated capacity guarantee.

Use an allowlisted root Docker build context so local environment files, Git metadata, installed dependencies and original design files cannot become build inputs. Supply database credentials only at runtime. API readiness must be checked through the application, not inferred from a static asset response.

## Resources, cost and correctness

No new production dependency or service is introduced. Measure cold startup and representative requester/staff workflows under an actual 512 MB limit with a disposable PostgreSQL database and the existing least-privilege bootstrap. Verify the built UI, known-route refresh, 404 boundaries and cookie/CSRF behavior through real browser journeys. Keep local-loopback simulations distinct from actual TLS/provider verification.

Run affected backend/frontend gates and the original Compose E2E suite as well as artifact-specific journeys. Preserve the original Nginx-specific throttle tests; they describe a different topology and must not be weakened to make the artifact green.

## Rollback

Continue using the unchanged local Compose stack, or revert this focused PR through normal review. Remove any public ingress before deactivating the public profile. No data migration or database reset is part of this change.
