# Issunexa

Issunexa is an Issue & Service Management / Help Desk platform being developed as a professional portfolio project.

The current backend supports creating and retrieving Tickets through a REST API, backed by PostgreSQL persistence.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the Issue → branch → PR → CI → review → merge workflow, branch and commit conventions, and verification expectations.

## Engineering documentation

Start with the [architecture overview](docs/architecture.md) for component boundaries, request flow, backend/frontend/security/database guides, and retrospective records of the accepted engineering decisions.

## Backend baseline

- Java 21
- Spring Boot 4.1.1
- Maven 3.9.16, provided by the included Maven Wrapper
- Spring MVC and Spring Boot test support
- Spring Data JPA / Hibernate, PostgreSQL and Flyway
- Testcontainers with the official `postgres:18.6` image for integration tests

Flyway owns schema creation; its first migration creates the `tickets` table. Tickets can be saved and loaded through a Spring Data JPA repository. Hibernate validates the schema (`ddl-auto=validate`), and Open EntityManager in View is disabled.

Ticket updates use JPA optimistic locking. Conflicting updates return `409` Problem Details so clients can reload and retry.

## Full-stack Docker environment

Requires Git and Docker with modern `docker compose` and Buildx support (included with Docker Desktop). No host Node, Java, Maven or PostgreSQL installation is needed. On Linux, ensure the Compose and Buildx CLI plugins are installed. Initial builds need internet access for images and npm/Maven dependencies.

From the repository root, create `.env` only if it does not already exist:

```sh
cp .env.example .env
# Set ISSUNEXA_POSTGRES_PASSWORD in .env to your own local database password.
docker compose up --build -d
docker compose ps
```

Open **http://localhost:3000** with the default frontend settings. The Docker frontend serves the production React build; Vite does not need to run. Use an internally provisioned account, as described under Authentication; the stack does not seed demo users.

An absent or empty database password stops Compose with a configuration error. `.env` is ignored; preserve existing local credentials. Frontend builds use Node 24.21.0 and `npm ci` with the lockfile. The runtime contains only compiled frontend files and official Alpine Nginx, running as a non-root user with a read-only filesystem and temporary writable storage. The backend uses the Maven Wrapper and a non-root Java 21 runtime. Image construction skips tests; the normal Maven and CI workflows still run the full suite.

The browser uses one frontend origin: **browser → frontend Nginx → `/api/...` → `backend:8080` → PostgreSQL**. Nginx preserves relative API paths, session cookies and CSRF headers without CORS or cookie rewriting. React Router routes fall back to `index.html`; missing static assets return `404`. Vite fingerprinted assets receive long-lived caching; HTML and unversioned public files require revalidation. API responses are not cached by Nginx.

PostgreSQL 18.6 must pass its health check before the backend starts. The backend connects through the Compose service name `postgres`, applies Flyway V1–V9, and validates the schema with Hibernate. Data lives in the `postgres_data` named volume at `/var/lib/postgresql`, the PostgreSQL 18 volume layout.

The frontend can start before the backend and resolves it through Docker DNS on API requests. Its healthcheck verifies static serving only; it does **not** establish backend readiness. The backend has no dedicated health endpoint. Wait for successful backend startup in the logs and verify the existing safe endpoint through the frontend:

```sh
curl --fail http://localhost:3000/api/auth/csrf
```

An early API request may receive `502` while the backend starts; retry once it is ready. Both backend and PostgreSQL host ports remain bound to `127.0.0.1`. With default settings:

- Frontend: `http://localhost:3000`.
- Backend API / local development tools: `http://localhost:8080`.
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`.
- Swagger UI: `http://localhost:8080/swagger-ui.html`.
- Local PostgreSQL tools: `127.0.0.1:5432`, with the database/user/password from `.env`.

Set `ISSUNEXA_FRONTEND_PORT`, `ISSUNEXA_BACKEND_PORT` or `ISSUNEXA_POSTGRES_PORT` in `.env` if a default port is occupied. Use the configured port in URLs. The frontend defaults to `ISSUNEXA_FRONTEND_BIND_ADDRESS=127.0.0.1` for local-only access.

### Optional iPhone access over Tailscale

Tailscale is optional and is not an Issunexa dependency. For browser access from an iPhone on the same tailnet, set `ISSUNEXA_FRONTEND_BIND_ADDRESS` in the ignored `.env` to the PC's Tailscale IPv4 address, then run `docker compose up --build -d` to recreate the frontend binding. Open `http://<PC-Tailscale-address>:<frontend-port>` on the iPhone; tailnet access rules must permit the connection. The PC can use that same URL while bound to Tailscale.

Only the frontend needs this binding. Mobile requests reach Nginx, then the backend and database through the Compose network; keep backend and PostgreSQL bound to loopback. Do not commit a machine-specific address. Restore `ISSUNEXA_FRONTEND_BIND_ADDRESS=127.0.0.1` and rerun Compose to return to local-only access.

Useful commands:

```sh
docker compose up --build
docker compose up --build -d
docker compose logs -f backend frontend
docker compose ps
docker compose down
```

`docker compose down` preserves database data. **`docker compose down -v` also deletes the named PostgreSQL volume and all local database data.** PostgreSQL initialization settings apply only to an empty volume; editing the password in `.env` does not change an existing database user's password.

## Frontend development

The React frontend lives in `frontend/`. Use **Node 24 LTS, version 24.15.0 or newer within Node 24**, and npm. It provides session login, logout, reload restoration, and a protected Ticket workspace at `/app/tickets`; `/app` redirects there. Authenticated users can create Tickets at `/app/tickets/new` and view details at `/app/tickets/:id`. The branded application header keeps Tickets navigation, account identity and sign-out available across Ticket pages.

The interface uses a system-font stack and a small CSS token set in `src/index.css`: neutral surfaces, the supplied purple accent, shared fields/actions and explicit feedback colors. Ticket-specific layouts live in `src/tickets/tickets.css`. The same native Ticket table adapts into labelled rows on narrow screens, without a second data path. Comments and lifecycle history sit alongside each other where space permits and stack on smaller screens. Focus indicators, labelled controls, textual status/priority values and reduced-motion styles are shared across pages.

Original supplied branding is preserved in `frontend/branding/`; browser assets are unchanged copies in `frontend/public/branding/`. The full SVG wordmark appears on login and the desktop application header; the supplied X mark is used in compact headers and the favicon. See [the branding inventory](frontend/branding/README.md) for variant selection and actual icon dimensions. The web manifest supplies app icons; it does not add offline behavior.

For the separate Vite development workflow, start the backend on port **8080** with `docker compose up --build -d postgres backend` from the repository root, or use the Maven workflow below. Then, in another terminal:

```sh
cd frontend
npm ci
npm run dev
```

Open the local URL printed by Vite (normally `http://localhost:5173`). Vite forwards relative `/api` requests to `http://localhost:8080`; no backend CORS changes are needed. This proxy is for development only. The frontend shell can load without the backend, but API requests require it.

Verification commands, from `frontend/`:

```sh
npm run lint
npm test
npm run build
```

`npm test` runs Vitest once; `npm run test:watch` watches for changes. The production build type-checks before writing `dist/`. Commit the npm lockfile when reviewing changes; generated dependencies, build output and coverage stay ignored.

Routed UI tests should wait for page-specific content and scope ambiguous assertions to the Ticket page. Authentication bootstrap, the account shell and route diagnostics can expose overlapping status roles or text.

### Continuous integration

Pushes and pull requests targeting `master`, plus manual runs, independently execute three GitHub Actions workflows:

- `Backend CI`: Java 21, Maven `verify`, and Spring Boot/PostgreSQL integration tests with Testcontainers.
- `Frontend CI`: Node 24.21.0, `npm ci`, lint, E2E TypeScript checks, Vitest and the production build; no backend services are required.
- `E2E CI`: Node 24.21.0 and the same local `npm run test:e2e` command, exercising Chromium journeys against the real Dockerized Nginx frontend, Spring Boot backend and PostgreSQL.

E2E failures upload the Playwright HTML report, traces and screenshots when available as `playwright-e2e-failure` in the GitHub Actions run, retained for seven days. The E2E script owns cleanup of its disposable Docker resources.

### End-to-end tests

Vitest covers frontend components and integration within the frontend. Playwright covers seven real full-stack browser journeys through the production Nginx frontend, `/api` proxy, Spring Boot and PostgreSQL: authentication/session, requester creation/comments/history, ownership isolation, agent workflow, a representative admin action, query-state preservation, and mobile usability.

Use Node 24 for project/npm tooling and a running Docker daemon with Compose and Buildx (modern Compose supporting the overlay's `!reset` tag). After `npm ci`, run from `frontend/`:

```sh
npm run test:e2e
```

This command builds the existing application services plus a test runner based on the [official Playwright Docker image](https://playwright.dev/docs/docker), with the exact installed Playwright version (`1.63.0`, `mcr.microsoft.com/playwright:v1.63.0-noble`). Node 24.21.0 is supplied to the runner. No host browser libraries or system installation are needed. Each invocation uses a unique `issunexa-e2e-*` Compose project, a generated temporary database password, a fresh PostgreSQL volume, and four explicitly synthetic accounts. The normal `.env` and normal database volume are unused. No service ports are published; browser traffic stays on the isolated Compose network and targets Nginx. Readiness checks validate `/api/auth/csrf` through Nginx before provisioning accounts and running tests. Tickets are created through the UI.

The suite uses one worker, zero retries, desktop Chromium and one Pixel 7 Chromium mobile-emulation test. Tests have independent browser sessions and unique Ticket names. `npm run typecheck:e2e` checks the test sources. `npm run test:e2e:headed` runs the same disposable workflow with headed Chromium under a virtual display inside Docker; inspect failures through the artifacts rather than expecting a host desktop window. Playwright CLI options can be forwarded, for example `npm run test:e2e -- --project=chromium-desktop`.

The HTML report is written to `frontend/e2e-artifacts/playwright-report/`; failed tests retain screenshots and traces under `frontend/e2e-artifacts/test-results/`. The parent is ignored and mounted writable so Playwright can recreate its output directories, including on NTFS. Reports/results are replaced by subsequent test runs. Inspect the report with `npm exec -- playwright show-report e2e-artifacts/playwright-report`, or a trace with `npm exec -- playwright show-trace <trace.zip>`. Failure prints recent service logs, preserves the failing process exit code, and still removes that invocation's containers, network, database volume, temporary credentials and project image tags. Docker build cache and downloaded base images remain available. Interrupted runs also attempt cleanup.

This coverage is Chromium only. Mobile emulation is not physical iPhone/Safari testing. There are no Firefox, WebKit or screenshot-baseline tests.

The build uses stable TypeScript 7 through the `@typescript/native` npm alias. ESLint needs the older compiler API, so `typescript` aliases Microsoft's `@typescript/typescript6` compatibility package, following the [official side-by-side guidance](https://devblogs.microsoft.com/typescript/announcing-typescript-7-0/#running-side-by-side-with-typescript-60). The `tsc` build command still runs TypeScript 7.

API calls use `src/api/apiRequest.ts` with relative `/api/...` paths and session credentials. JSON is returned as `unknown` unless the caller supplies a narrowing decoder; empty success responses return `undefined`. HTTP failures become `ApiError` with optional structured Problem Details. Authentication decoders validate the CSRF and current-session responses. No frontend environment variables are needed; never put secrets in browser-visible `VITE_` variables.

The root routes through protected `/app`; anonymous users reach `/login`. Bootstrap fetches CSRF before probing the session. Login obtains fresh CSRF before loading the authenticated account; logout invalidates the backend session before preparing fresh anonymous CSRF. Tokens and account state stay in memory, and the browser manages its session cookie. A failed post-login refresh offers a session retry without replaying credentials; a failed refresh after successful logout keeps the user signed out. Use an internally provisioned account: registration, password reset and demo accounts are not provided.

The Ticket workspace uses `GET /api/tickets` with runtime validation of Ticket fields, nullable assignees and explicit page metadata. Applied search, status, priority, category, sort, page and size live in the URL, so links and browser navigation restore the list controls. Search runs on explicit submission; filter, search, sort and size changes reset the zero-based page to `0`. Clear filters restores all defaults and clears search. Invalid or repeated URL parameters fall back to supported defaults before requests; unsupported URL keys are ignored. The backend owns visibility, filtering, sorting and totals; React displays the returned page without reprocessing Tickets. Loading, empty and safe error states include recovery controls. A Ticket request returning `401` clears the authenticated account and stale CSRF metadata, redirects to login and reacquires CSRF on the next explicit login attempt.

Creation sends only title, description, priority and category to `POST /api/tickets`, using the current in-memory CSRF token and server-supplied header name. Title is required with a 255-character limit; description is required without an additional client length limit. The server controls initial status, identity, requester, assignee and timestamps. Fields remain available after errors; backend field validation is mapped to safe frontend messages. A `403` prepares CSRF refresh for the next explicit submit, without automatically replaying the POST. After a decoded success, the server-provided Ticket ID selects the detail route, which loads fresh data through `GET /api/tickets/{id}`. Uncertain creation outcomes ask the user to check the list before retrying.

Details display title, description, status, priority, category, assignee and created/updated timestamps. Missing and inaccessible Tickets share the same `404` state; the frontend never guesses ownership. Loading and safe error states support Retry, and superseded or abandoned requests cannot update the active page. List-to-detail and list-to-create navigation preserve the list URL in React Router state for Back/Cancel links; directly opened pages fall back to `/app/tickets`.

AGENT and ADMIN see self-claim for unassigned Tickets and status actions matching the existing backend transitions. REQUESTER sees no workflow controls; backend authorization remains authoritative for every request. Claim sends no body, and status changes send only `status`, using the current session CSRF metadata. Successful responses are runtime-decoded and replace the displayed Ticket without fabricating assignment, status or timestamps. Versioning is internal to the backend; the API exposes no client version field. A `409` blocks actions until an explicit Refresh loads current details. Uncertain outcomes also require refresh. The backend uses the same generic `403` for authorization and CSRF failures: the UI shows a safe message and refreshes CSRF only on the next explicit action, never automatically replaying a mutation. Active `401` responses expire the frontend session. No reassignment or Ticket edit/delete controls are implemented.

Ticket details include separate paginated Comments and Lifecycle history sections with independent loading, refresh and recovery controls. Users who can access a Ticket can append plain text comments of up to 4,000 characters in any Ticket status. Comments show author, body and creation time, oldest first; they cannot be edited or deleted. A confirmed save displays server data and opens the newest comments page. Failed submissions retain the draft; uncertain outcomes require refreshing and checking comments before another explicit submission.

Lifecycle history displays recorded creation, status-change and claim events with their actors and timestamps, newest first. Successful claim/status actions refresh history without resetting the comment draft. Comments are separate from lifecycle events. Earlier Ticket activity may not have been recorded; the UI does not invent missing history.

## Non-Docker prerequisites

- JDK 21. Set `JAVA_HOME` to its installation directory and put its `bin` directory on `PATH`.
- Internet access for the initial Maven, dependency and container image downloads.
- A running Docker-compatible container runtime accessible to Testcontainers for both `test` and `package`.
- An external PostgreSQL database for normal application startup.
- On Linux/macOS: a POSIX shell, `curl` or `wget`, and `unzip`.
- On Windows: PowerShell; use `mvnw.cmd` instead of `./mvnw` in the commands below.

A globally installed Maven is not required. Check that `./mvnw --version` reports Maven 3.9.16 and Java 21 before building.

## Build, test and run

Run from the repository root:

```sh
cd backend
./mvnw --version
./mvnw test
./mvnw package
```

The integration tests use disposable PostgreSQL 18.6 containers to verify persistence, database constraints, generated API documentation and session authentication against the Flyway-created schema. Security integration tests use real accounts, password verification and the full filter chain to check login, session fixation protection, CSRF rotation and logout. Assignment and concurrency tests use committed transactions in isolated test databases; other persistence tests roll back their data changes. `@ServiceConnection` supplies connection details automatically; runtime database environment variables are not needed for tests. Testcontainers stops and removes the containers after the tests.

Service unit tests and MVC controller slice tests run without PostgreSQL. Ticket MVC slices isolate security filters to focus on binding, validation and HTTP contracts; full-context security tests cover authentication and CSRF separately. `package` compiles the application, runs the full test suite and creates an executable JAR. `./mvnw verify` also runs the complete suite, including security integration tests, in CI. The full suite requires the container runtime and fails if it is unavailable.

## Authentication

Authentication uses email, password and an HTTP session cookie. Accounts are provisioned internally; there is no registration API.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/auth/csrf` | Public endpoint returning `token` and `headerName`; responses must not be cached |
| GET | `/api/auth/session` | Authenticated account's `id`, canonical `email`, `displayName` and persisted `role`; not cached |
| POST | `/api/auth/login` | Accepts JSON `email` and `password`; returns `204` on success |
| POST | `/api/auth/logout` | Invalidates the authenticated session; returns `204` on success |

Retain the session cookie when fetching a CSRF token and send the token in the returned header name on login. After successful login, retain the updated session cookie and fetch a fresh CSRF token. Unsafe requests, including Ticket creation, comments, claims, status changes and logout, require that token. Fetch a new token again after logout before another login.

All Ticket endpoints require an authenticated session. GET requests do not require CSRF. Missing authentication returns `401` Problem Details; missing or invalid CSRF returns `403` Problem Details, including on login. Invalid credentials return the same generic `401` response for unknown emails and incorrect passwords. On unsafe requests, CSRF validation runs before the authentication requirement.

New Tickets belong to the authenticated account; clients cannot select a requester.

| Role | Create | List/get | Change status | Claim unassigned |
| --- | --- | --- | --- | --- |
| REQUESTER | Allowed | Own Tickets only | Forbidden (`403`) | Forbidden (`403`) |
| AGENT | Allowed | All Tickets | Allowed | Allowed |
| ADMIN | Allowed | All Tickets | Allowed | Allowed |

For REQUESTER, other users' Tickets and historical Tickets without a requester are absent from listings and return the same `404` as missing Tickets. AGENT and ADMIN can view and update the status of historical Tickets. Listing totals reflect only visible, matching Tickets. ADMIN-specific User administration is not implemented yet.

## Ticket API

When the application is running locally, OpenAPI JSON is available at `/v3/api-docs`. Swagger UI is available at `/swagger-ui.html`, which redirects to `/swagger-ui/index.html`.

| Method | Path | Successful response |
| --- | --- | --- |
| POST | `/api/tickets` | `201 Created`, Ticket JSON and a `Location` header |
| GET | `/api/tickets/{id}` | `200 OK` and Ticket JSON |
| GET | `/api/tickets` | `200 OK`, Ticket content and page metadata |
| PATCH | `/api/tickets/{id}/status` | `200 OK` and the updated Ticket JSON |
| POST | `/api/tickets/{id}/claim` | `200 OK` and the updated Ticket JSON |
| POST | `/api/tickets/{ticketId}/comments` | `201 Created` and Comment JSON |
| GET | `/api/tickets/{ticketId}/comments` | `200 OK`, Comment content and page metadata |
| GET | `/api/tickets/{ticketId}/history` | `200 OK`, lifecycle history and page metadata |

Creation accepts `title`, `description`, `priority` (`LOW`, `MEDIUM`, `HIGH` or `URGENT`) and `category`. Title and description must not be blank; title is limited to 255 characters, and priority and category are required. New Tickets start as `OPEN`.

Category is a controlled classification selected at creation: `INCIDENT`, `SERVICE_REQUEST`, `ACCESS_REQUEST` or `OTHER`. It is returned in every Ticket response and cannot be changed. Pre-V9 Tickets migrate to `OTHER` because their original category is unknown. There is no category administration API.

AGENT and ADMIN can claim an unassigned Ticket for themselves with no request body. The authenticated account becomes the assignee; clients cannot select another account. Claiming preserves requester and status. Any repeated claim returns `409`, including a repeat by the same staff account. Ticket responses contain `assignee: null` when unassigned, or an assignee summary with only `id` and `displayName`.

Status changes accept only `status`, for example `PATCH /api/tickets/42/status` with `{"status":"IN_PROGRESS"}`. The allowed transitions are:

- `OPEN → IN_PROGRESS`
- `IN_PROGRESS → RESOLVED`
- `RESOLVED → IN_PROGRESS`
- `RESOLVED → CLOSED`

`CLOSED` is terminal in the current version. All other transitions, including requests for the current status, return `409 Conflict` Problem Details. Missing Tickets return `404`; missing/null status or an unsupported status value returns `400`.

Listing accepts zero-based `page` (default `0`) and `size` (default `20`, range `1`–`100`). For example, `GET /api/tickets?page=2&size=10` retrieves the third page. Empty pages return an empty `content` array with page metadata.

Optional listing filters are `status` (`OPEN`, `IN_PROGRESS`, `RESOLVED`, `CLOSED`), `priority` (`LOW`, `MEDIUM`, `HIGH`, `URGENT`) and `category` (the four values above). Each accepts a single, case-sensitive value. Filters use AND semantics: `GET /api/tickets?category=INCIDENT&status=OPEN&priority=HIGH&page=0&size=10` returns only matching visible Tickets. Filtering occurs before pagination; omitted filters impose no restriction.

Sorting accepts `sortBy` (`createdAt`, `updatedAt`, `title`) and `direction` (`asc`, `desc`), defaulting to `createdAt` and `desc`. Each accepts one case-sensitive value. For example, `GET /api/tickets?sortBy=title&direction=asc` orders by title ascending, then ID ascending. The ID tie-breaker always uses the selected direction.

Optional `q` performs case-insensitive substring search in title or description. A supplied value must contain non-whitespace text and be at most 100 characters before trimming; surrounding whitespace is trimmed. `%`, `_` and `\` match literally. Search combines with status, priority and category using AND, for example `GET /api/tickets?q=login&category=INCIDENT&status=OPEN&priority=HIGH`.

Invalid input returns `400`; a missing Ticket returns `404`. Errors use `application/problem+json`, with field details for validation failures.

Comments are append-only in the current version. Authenticated users can create and list comments on Tickets they may access: REQUESTER on owned Tickets only, and AGENT/ADMIN on all Tickets, including historical Tickets without a requester. Hidden and missing Tickets return the same `404`. Closed Tickets can receive comments; adding a comment leaves the Ticket's status, requester, assignee, version and timestamps unchanged.

Comment creation accepts only `{"body":"Comment text"}`: nonblank plain text of at most 4000 characters before trimming. Outer whitespace is stripped; internal whitespace and newlines are preserved. The authenticated account is always the author. Responses contain `id`, `body`, `createdAt` and an `author` summary with only `id` and `displayName`.

Comment listing supports zero-based `page` (default `0`) and `size` (default `20`, range `1`–`100`), ordered by `createdAt ASC`, then `id ASC`. It returns `content`, `page`, `size`, `totalElements`, `totalPages`, `first` and `last`. There are no comment editing, deletion, search or sorting options.

## Ticket lifecycle history

Successful Ticket creation, status changes and self-claims record `TICKET_CREATED`, `STATUS_CHANGED` and `ASSIGNEE_CLAIMED` entries in the same transaction as the Ticket operation. Entries contain structured event data and the authenticated actor; actor and assignee summaries expose only `id` and `displayName`. Failed operations leave no history entry. History is append-only, with no manual creation, edit or delete API. Comments remain a separate resource and do not create lifecycle entries.

`GET /api/tickets/{ticketId}/history` follows normal Ticket visibility: REQUESTER can view owned Tickets only; AGENT/ADMIN can view all Tickets, including historical Tickets without a requester. Hidden and missing Tickets return the same `404`. History is ordered newest-first by `createdAt DESC`, then `id DESC`, with zero-based `page` (default `0`) and `size` (default `20`, range `1`–`100`). There are no history sort, filter or search controls.

History recording begins with V8. Earlier activity is not backfilled: existing Tickets may have incomplete history or no creation entry. This is lifecycle history, not a complete record of activity before V8.

## Runtime database configuration

Before starting the application, provide these environment variables for an existing PostgreSQL database:

| Variable | Purpose |
| --- | --- |
| `ISSUNEXA_DB_URL` | JDBC URL, for example `jdbc:postgresql://localhost:5432/issunexa` |
| `ISSUNEXA_DB_USERNAME` | Database username |
| `ISSUNEXA_DB_PASSWORD` | Database password |

There are no default credentials. Keep local credentials outside source control.

From `backend/`, run:

```sh
./mvnw spring-boot:run
```

Or run the packaged application:

```sh
java -jar target/issunexa-0.0.1-SNAPSHOT.jar
```

The application uses Spring Boot's default HTTP port, `8080`. OpenAPI and Swagger UI remain publicly accessible. Stop the application with `Ctrl+C`.

## Repository structure

```text
.
├── .editorconfig          # Shared formatting rules
├── .gitignore            # Generated and local files
├── .env.example          # Local Compose configuration template; no password
├── compose.yaml          # Frontend, backend and PostgreSQL local environment
├── frontend/             # React/Vite, tests, Dockerfile, .dockerignore and nginx.conf
├── README.md
└── backend/
    ├── Dockerfile        # Maven build and non-root Java 21 runtime
    ├── .dockerignore     # Excludes local files from the build context
    ├── .mvn/wrapper/     # Maven Wrapper configuration
    ├── mvnw              # Linux/macOS wrapper
    ├── mvnw.cmd          # Windows wrapper
    ├── pom.xml           # Backend build and dependencies
    └── src/
        ├── main/         # Application, Ticket API/model and migrations
        └── test/         # Service, MVC and PostgreSQL tests
```
