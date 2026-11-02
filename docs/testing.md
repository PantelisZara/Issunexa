# Testing and continuous integration

Verification follows three boundaries: backend contracts, client behavior and browser journeys through the real stack. These are executable checks rather than claims about percentage coverage or production reliability.

## Backend

With Java 21 and Docker accessible to Testcontainers, from the repository root:

```sh
cd backend
./mvnw --batch-mode --no-transfer-progress verify
```

The [Maven Wrapper](../backend/.mvn/wrapper/maven-wrapper.properties) supplies Maven 3.9.16. Maven's test phase runs the JUnit suite; `verify` also packages the executable application. Tests do not require local runtime database credentials.

- Entity/service unit tests cover workflow invariants and transaction coordination; MVC slices cover binding, validation and response contracts. Ticket MVC slices isolate security filters.
- Full-context tests exercise actual password verification, the security chain, session-ID/CSRF rotation, logout, ownership and persisted-role checks.
- PostgreSQL 18.6 Testcontainers tests cover persistence, constraints, V1–V10 migration behavior, database privilege separation, concurrent Ticket updates, comments/history and generated OpenAPI.
- Managed provisioning tests use real TLS PostgreSQL and a non-superuser database owner to verify ordinary SQL migrator/runtime roles, grants, negative privileges, reruns and transactional rollback. Logical recovery tests restore trusted data/history/sequences to a separate empty target with different role names, restart the app and prove new writes plus failure rollback.
- Demo tests exercise bound requester/agent isolation, expiry before physical deletion, startup rejection of ordinary data, quota concurrency, bounded sessions/admissions and the narrowly granted cleanup routine. Immutable requester columns prevent cleanup from adopting ordinary tickets.
- Real-server health probes verify database login failure/recovery, session-free minimal responses and database-independent liveness.
- The public-profile JDBC pool test retires all idle connections and reconnects for persisted application work against PostgreSQL.
- Login budget tests use an injected clock for recovery/concurrency/bounds; integration tests exercise path/proxy trust and authentication contracts.

See [backend test sources](../backend/src/test/java/io/github/panteliszara/issunexa/) and the [database verification details](database.md#executable-verification). Tests use disposable databases; they do not reset the normal Compose volume. The full suite fails without an accessible Docker runtime.

## Frontend

With Node 24.22.2, from the repository root:

```sh
cd frontend
npm ci
npm run lint
npm run typecheck:e2e
npm test
npm run build
```

[Vitest and Testing Library tests](../frontend/src/) cover auth bootstrap/login/logout, demo metadata/transitions/expiry/quotas and safe recovery without POST replay, runtime response decoding, URL query state, ticket creation/detail, comments/history, workflow controls and explicit recovery from API failures. These tests use controlled API responses and do not prove backend behavior. `npm test` runs once; `npm run test:watch` is the interactive alternative. The build type-checks before producing static files. E2E sources have a separate TypeScript check.

## Full-stack browser journeys

After `npm ci`, with Node 24 and Docker/Compose/Buildx available, run from `frontend/`:

```sh
npm run test:e2e
```

The [runner](../frontend/e2e/run-e2e.sh) builds real Nginx/React and Spring Boot images plus an official Playwright Docker runner matching the locked Playwright version. Modern Compose must support the overlay's `!reset` tag. No host browser-library installation is required.

Each invocation creates a unique Compose project, generated database credentials and a fresh volume. It excludes the normal `.env`, clears inherited login-limit overrides, verifies `/api/auth/csrf` through Nginx and provisions five synthetic accounts after migration. No service ports are published. Tickets are created through the UI; the runner removes its own containers, network, volume, temporary credentials and image tags on completion or failure. Docker build cache/base images remain.

The nine journeys cover session redirects/reload/logout; requester creation/comments/history; requester isolation; agent claim/status/comment persistence; a representative ADMIN staff action; filter/return navigation; mobile usability; and two login-throttling/source-trust journeys. See [journeys](../frontend/e2e/journeys.spec.ts), [mobile](../frontend/e2e/mobile.spec.ts) and [throttling](../frontend/e2e/auth-throttle.spec.ts).

[Configuration](../frontend/playwright.config.ts) uses one worker, zero retries, desktop Chromium and Pixel 7 Chromium emulation. There is no Firefox, WebKit, physical iPhone/Safari or screenshot-baseline coverage. `npm run test:e2e:headed` uses a virtual display inside Docker, not a host desktop window. CLI options can be forwarded, e.g. `npm run test:e2e -- --project=chromium-desktop`.

The ignored `frontend/e2e-artifacts/` directory contains the HTML report and retained failure traces/screenshots; later runs replace outputs. From `frontend/`:

```sh
npm exec -- playwright show-report e2e-artifacts/playwright-report
```

For a retained trace, use `npm exec -- playwright show-trace` followed by its actual ZIP path. Failures print recent service logs, preserve the failing exit status and still attempt cleanup. A cleanup failure also makes the command fail.

## Single-service artifact

Run `npm run test:e2e:artifact` from `frontend/` to verify the root Java/React image under a 512 MiB memory limit. It reuses the disposable runner and seven desktop/mobile journeys, then checks compiled assets, known-route refresh, cache/security headers and API/missing-asset boundaries in two artifact-specific tests. The Nginx throttle tests remain in the original nine-test command. See [artifact verification and limits](single-service-artifact.md) for the localhost Secure-cookie simulation, cgroup v2 requirement and runtime measurements.

## Isolated synthetic demo

Run `npm run test:e2e:demo` from `frontend/` after `npm ci`. It builds the same root artifact with `public,demo`, private per-run credentials and a fresh database, skips ordinary account fixtures and publishes no host ports. The three desktop/mobile tests use four total admissions to remain below the real source budget. They prove independent visitor workflows, cross-workspace read/write denial, role/reload persistence, cookie/CSRF rotation and replay rejection, fixed-action validation and narrow-screen usability. This command cleans up after itself; use the [manual loopback setup](demo-ui.md#explore-the-demo-in-your-browser) for interactive exploration.

## GitHub Actions

| Workflow | Actual checks |
| --- | --- |
| [Backend CI](../.github/workflows/backend-ci.yml) | Java 21, Maven `verify`, including PostgreSQL/Testcontainers tests |
| [Frontend CI](../.github/workflows/frontend-ci.yml) | Node 24.22.2, `npm ci`, lint, E2E type checks, Vitest and production build |
| [E2E CI](../.github/workflows/e2e-ci.yml) | Node 24.22.2, install/type checks, Docker compatibility and all three local commands: `npm run test:e2e`, `npm run test:e2e:artifact` and `npm run test:e2e:demo` |

All three run independently on pushes to `master`, PRs targeting `master` and manual dispatch. E2E failures upload available HTML reports, traces and screenshots as `playwright-e2e-failure`, retained seven days. Docker image construction skips tests; a successful image build does not imply the suites passed.

[CONTRIBUTING.md](../CONTRIBUTING.md) requires relevant local verification and green CI before reviewed merge. These contribution conventions do not themselves configure branch protection. Use this repository's **Actions** tab for actual workflow results; no static passing badge is used as evidence.

## Verified snapshot

On 2026-11-01, the source at [51d1b08](https://github.com/PantelisZara/Issunexa/commit/51d1b08edd07f86e40e55794800820d5ac5b83a9) passed local lint/E2E types, 424 Vitest tests and production build, plus 9 Nginx, 7 ordinary artifact journeys, 2 artifact contracts and 3 demo browser tests. The unchanged backend had 675 passing Maven tests. [PR #20](https://github.com/PantelisZara/Issunexa/pull/20) records GitHub Actions verification for that snapshot; use its actual checks and subsequent master runs rather than treating these counts as evidence for future changes.

Official in-app Browser QA additionally exercised requester create/comment, agent claim/status/response, requester reload and end/denied prior access. A 390 px viewport showed no horizontal overflow and console errors were absent during that smoke. [Capture provenance](screenshots/README.md) describes the genuine local screenshots. This is representative local evidence, not a load test, browser-matrix guarantee or external HTTPS acceptance.

## Hosted demo acceptance

Status on 2026-11-02: the [public synthetic demo](https://issunexa-demo.onrender.com) runs [f4a5c0c9da05f37b56e28dfdcd0b8aa9bc80000f](https://github.com/PantelisZara/Issunexa/commit/f4a5c0c9da05f37b56e28dfdcd0b8aa9bc80000f), including the [managed client TLS correction in PR #24](https://github.com/PantelisZara/Issunexa/pull/24). The following observations concern that deployed revision, not future deployments:

- External checks verified the HTTP-to-HTTPS redirect, certificate/hostname validation, session-free liveness/readiness responses, Secure host-only cookies and protected administrative/explorer paths.
- All 70 HTTPS workflow checks passed: requester/agent work, persisted ticket/comment/history reads, independent-visitor isolation, role restrictions, session/CSRF rotation and replay rejection, logout, and ticket/comment quotas. Separate admission checks observed throttling with `Retry-After`; forwarded-header spoofing did not reset the budget.
- Official in-app Browser desktop and mobile checks exercised the requester/agent walkthrough and narrow-screen usability. These are representative checks, not physical-device, browser-matrix or load-test coverage. The existing [screenshots](screenshots/README.md) remain genuine local captures.
- Actual managed database provisioning and privilege checks, a local logical backup and restoration into a separate fresh managed target were verified. The source database was retained. Client certificate verification covers the configured provider endpoint; it does not attest to provider-internal transport.
- Hosted JVM diagnostics reported cgroup v2 limits of 512 MiB for memory and memory plus swap, and a CPU quota of 0.15 CPU. Render's creation form advertised 0.1 CPU. The original Java memory flags and `public,demo` profiles remained intact; all ten migrations were current. These are limits, not measured peak usage. Hosted peak-memory graphs were unavailable on Free without a paid upgrade; none was attempted.
- After service restart, the ticket created during the public Browser walkthrough retained its `IN_PROGRESS` status, two comments and three history entries. The database contained only synthetic demo users, and one observed runtime connection was within the pool limit of three.
- Application startup observations were 97.801 seconds after natural idle, 110.598 seconds after explicit restart and 100.301 seconds on the same-revision diagnostic rollout, which reached Live. These observations do not bound future startup time or separately measure database wake time.
- Neon naturally suspended while the application remained live. One external liveness read returned `200` without a session cookie and left the database suspended; the next legitimate ticket read recovered the persisted workflow after database wake.
- The 2026-11-02 Neon snapshot showed one Free project, one 0.25 CU compute and two databases, with 0.25 CU-hours used since November 1, 40.48 MB storage, 15.45 MB history and 1.09 MB network usage. This is a point-in-time observation, not a capacity guarantee or quota-exhaustion test.

A naturally elapsed 30-minute workspace test kept its original deadline across role switching and repeated start. Protected reads near 12 and 24 minutes succeeded without extending it; after the deadline, the protected read and session returned `401`, metadata was inactive, and a new visitor could not adopt the expired ticket (`404`). The Browser UI also retired access at its displayed deadline. No clock or database timestamps were overridden. These were bounded acceptance reads, with no recurring keepalive.

Archives currently remain local; off-host backup storage and retention for expired synthetic data are not established. Free-tier sleep, quotas and suspension can interrupt access. Sessions and throttle budgets are process-local, so process restarts end existing sessions. See [provider configuration and operating limits](operations.md#release-and-provider-acceptance). No production reliability or usage claim follows from this acceptance.
