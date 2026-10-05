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
- PostgreSQL 18.6 Testcontainers tests cover persistence, constraints, V1–V9 migration behavior, database privilege separation, concurrent Ticket updates, comments/history and generated OpenAPI.
- Login budget tests use an injected clock for recovery/concurrency/bounds; integration tests exercise path/proxy trust and authentication contracts.

See [backend test sources](../backend/src/test/java/io/github/panteliszara/issunexa/) and the [database verification details](database.md#executable-verification). Tests use disposable databases; they do not reset the normal Compose volume. The full suite fails without an accessible Docker runtime.

## Frontend

With Node 24.21.0, from the repository root:

```sh
cd frontend
npm ci
npm run lint
npm run typecheck:e2e
npm test
npm run build
```

[Vitest and Testing Library tests](../frontend/src/) cover auth bootstrap/login/logout, runtime response decoding, URL query state, ticket creation/detail, comments/history, workflow controls and explicit recovery from API failures. These tests use controlled API responses and do not prove backend behavior. `npm test` runs once; `npm run test:watch` is the interactive alternative. The build type-checks before producing static files. E2E sources have a separate TypeScript check.

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

## GitHub Actions

| Workflow | Actual checks |
| --- | --- |
| [Backend CI](../.github/workflows/backend-ci.yml) | Java 21, Maven `verify`, including PostgreSQL/Testcontainers tests |
| [Frontend CI](../.github/workflows/frontend-ci.yml) | Node 24.21.0, `npm ci`, lint, E2E type checks, Vitest and production build |
| [E2E CI](../.github/workflows/e2e-ci.yml) | Node 24.21.0, install/type checks, Docker compatibility and the local `npm run test:e2e` command |

All three run independently on pushes to `master`, PRs targeting `master` and manual dispatch. E2E failures upload available HTML reports, traces and screenshots as `playwright-e2e-failure`, retained seven days. Docker image construction skips tests; a successful image build does not imply the suites passed.

[CONTRIBUTING.md](../CONTRIBUTING.md) requires relevant local verification and green CI before reviewed merge. These contribution conventions do not themselves configure branch protection. Use this repository's **Actions** tab for actual workflow results; no static passing badge is used as evidence.
