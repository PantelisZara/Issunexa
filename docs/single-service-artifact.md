# Single-service artifact

The root [Dockerfile](../Dockerfile) packages the existing React UI and Java API in one Java 21 process. This prepares a same-origin deployment; it does not establish a safe public demo or a working hosted service. The normal [Nginx/Compose workflow](local-development.md) remains available.

## Build and runtime contract

From the repository root, with Docker/Buildx:

```sh
docker build -t issunexa:local .
```

Node 24.22.2 compiles the frontend; Maven packages the backend and compiled assets. Image construction skips tests. The final layered JRE image runs as UID/GID 10001 and contains no Node, npm, Maven, Java compiler or frontend source. The allowlisted [.dockerignore](../.dockerignore) excludes environment files, Git metadata, local dependencies, tests and original design files. Supply credentials at runtime; never add them to build arguments or the image.

Use the [public HTTPS contract](public-https.md), including `SPRING_PROFILES_ACTIVE=public`, a canonical `ISSUNEXA_PUBLIC_ORIGIN` and an explicitly selected ingress. The platform must enforce HTTPS and prevent direct access around that ingress. Java itself listens internally over HTTP. `PORT` selects its listener (default 8080). Configure `ISSUNEXA_DB_URL`, separate runtime and Flyway credentials, and provision database privileges before startup; the image does not bootstrap administrator privileges or create accounts. [Database configuration](database.md) describes the existing local bootstrap; managed provisioning remains separate work.

Known browser routes forward to the compiled index. Fingerprinted `/assets/` files use a one-year immutable cache; the index remains uncached. Missing assets, unknown routes and API errors do not turn into successful HTML. API authorization, CSRF and session handling remain enforced by the backend. Swagger/OpenAPI stay disabled in this profile.

## Bounded resources and idle connections

The public profile limits Tomcat to 20 worker threads, 100 connections and an accept queue of 20. Hikari permits three JDBC connections, zero minimum idle, a 60-second idle timeout and no keepalive queries. Idle connections can retire rather than preventing database suspension. Retirement includes Hikari's housekeeping delay; a new request reconnects. The PostgreSQL integration test exercises actual retirement and a persisted application read using a shorter test-only idle threshold. This does not prove managed-provider suspension or wake-up latency.

The image defaults to a 50% maximum heap, 128 MiB maximum metaspace, 32 MiB reserved code cache and 512 KiB thread stacks. Native memory still contributes to total usage. Test under a real container limit; these settings alone are not a capacity guarantee. Override JVM settings only with new measurements.

## Reproducible local verification

After `npm ci`, run from `frontend/` with Node 24, Docker/Compose/Buildx and a Linux cgroup v2 Docker runtime:

```sh
npm run test:e2e
npm run test:e2e:artifact
```

The first command preserves all nine Nginx journeys, including its two topology-specific throttling tests. Artifact mode reuses the same disposable runner, generated credentials, strict database bootstrap and synthetic fixtures. It builds the root image, starts Java at port 9090 under a 512 MiB memory limit with no additional swap, runs seven desktop/mobile journeys, then two artifact contract tests. It verifies UID 10001, absence of build tools, the effective memory limit and OOM state, and prints the process cgroup's peak memory. Each invocation removes only its own containers, network, database volume, temporary credentials and image tags. Build cache/base images remain.

No ports are published. The browser runner shares Java's network namespace so its localhost reaches the actual listener. Chromium's Secure-cookie exception for localhost permits this local HTTP simulation. This checks application behavior; it does not verify public TLS, DNS, platform proxy behavior or public cookie delivery. The separate backend security suite covers cookie flags, hostile forwarding metadata, CSRF, session rotation and logout.

The required E2E CI job executes both commands. [Testing details](testing.md) cover diagnostics and the other required suites. An official in-app Browser smoke test also exercised requester login/create/comment/reload/logout and agent claim/status/logout against a locally built artifact using synthetic data; no console errors were observed during that smoke test.

## Observed local result

On 2026-10-31, the portable artifact command completed seven application journeys and two serving-contract tests with an effective cgroup limit of 536,870,912 bytes. Peak cgroup memory across startup, migrations and those journeys was 404,205,568 bytes (385.5 MiB), with no OOM kill. This is one local observation, not a provider or concurrency benchmark. The process ran as UID 10001 without Node/npm/Maven/javac. No CPU quota was applied. A separate fresh local invocation reached the CSRF readiness contract in 37.8 seconds from Compose startup, including PostgreSQL initialization/provisioning and Java startup but excluding image builds; other verification ran concurrently. Its official Browser requester/staff smoke completed with a 331,075,584-byte peak (315.7 MiB). Neither observation predicts provider cold-start latency.

## Operational limits

The local 512 MiB exercise covers startup, migrations and representative single-browser activity. It does not establish concurrency capacity, provider CPU constraints, cold database wake-up, provider build compatibility, availability or uptime. Sessions and login budgets are in process memory and reset on restart. No multi-instance session storage is provided.

Startup fails when migration or database authentication fails; a static page response alone is not database readiness. The E2E runner waits for the application CSRF contract after startup and then exercises persisted workflows. A dedicated operational readiness endpoint, managed provisioning, isolated demo workspaces/quotas/expiration and verified backup/restore remain separate prerequisites to public deployment. No new cloud account, public endpoint or billable resource is created by this change.

[ADR 005](decisions/005-single-service-artifact.md) records alternatives and rollback.
