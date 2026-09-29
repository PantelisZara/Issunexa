# Issunexa

Issunexa is an Issue & Service Management / Help Desk platform being developed as a professional portfolio project.

The current backend supports creating and retrieving Tickets through a REST API, backed by PostgreSQL persistence.

## Backend baseline

- Java 21
- Spring Boot 4.1.1
- Maven 3.9.16, provided by the included Maven Wrapper
- Spring MVC and Spring Boot test support
- Spring Data JPA / Hibernate, PostgreSQL and Flyway
- Testcontainers with the official `postgres:18.6` image for integration tests

Flyway owns schema creation; its first migration creates the `tickets` table. Tickets can be saved and loaded through a Spring Data JPA repository. Hibernate validates the schema (`ddl-auto=validate`), and Open EntityManager in View is disabled.

Ticket updates use JPA optimistic locking. Conflicting updates return `409` Problem Details so clients can reload and retry.

## Local Docker environment

Requires Git and Docker with modern `docker compose` and Buildx support (included with Docker Desktop). No host Java, Maven or PostgreSQL installation is needed for this workflow. On Linux, ensure your Docker installation includes the Compose and Buildx CLI plugins. Initial builds need internet access for images and Maven dependencies.

From the repository root:

```sh
cp .env.example .env
```

Set `ISSUNEXA_POSTGRES_PASSWORD` in `.env` to your own local database password, then run:

```sh
docker compose up --build
```

An absent or empty password stops Compose with a configuration error. `.env` is ignored; keep it local. The backend is built from source using the Maven Wrapper and runs as a non-root user on Java 21. Image construction skips tests; the normal Maven and CI workflows still run the full suite.

PostgreSQL 18.6 must pass its health check before the backend starts. The backend connects through the Compose service name `postgres`, applies Flyway V1–V9, and validates the schema with Hibernate. Database data lives in the `postgres_data` named volume mounted at `/var/lib/postgresql`, the PostgreSQL 18 volume layout.

Both published ports bind to `127.0.0.1`. With the default backend port:

- Backend base URL: `http://localhost:8080` (there is no homepage; API routes require authentication as described below).
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`.
- Swagger UI: `http://localhost:8080/swagger-ui.html`.

Set `ISSUNEXA_BACKEND_PORT` or `ISSUNEXA_POSTGRES_PORT` in `.env` if the default `8080` or `5432` is occupied. Use the configured backend port in URLs. Optional local database tools can connect to `127.0.0.1` on the configured PostgreSQL port, using the database/user settings and password from `.env`.

Useful commands:

```sh
docker compose up --build
docker compose up -d --build
docker compose logs -f backend
docker compose ps
docker compose down
```

`docker compose down` removes the containers and network but preserves database data. **`docker compose down -v` also deletes the named PostgreSQL volume and all local database data.** PostgreSQL initialization settings apply only to an empty volume; editing the password in `.env` does not change an existing database user's password.

## Frontend development

The React frontend lives in `frontend/`. Use **Node 24 LTS, version 24.15.0 or newer within Node 24**, and npm. The current foundation provides a minimal shell, root/not-found routing and a JSON API boundary; authentication and Ticket screens are not implemented yet. Official logo assets will be integrated when supplied; the shell uses plain text branding.

Start the backend on port **8080** using the Docker workflow above or the Maven workflow below. Then, in another terminal:

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

The build uses stable TypeScript 7 through the `@typescript/native` npm alias. ESLint needs the older compiler API, so `typescript` aliases Microsoft's `@typescript/typescript6` compatibility package, following the [official side-by-side guidance](https://devblogs.microsoft.com/typescript/announcing-typescript-7-0/#running-side-by-side-with-typescript-60). The `tsc` build command still runs TypeScript 7.

API calls use `src/api/apiRequest.ts` with relative `/api/...` paths and session credentials. JSON is returned as `unknown` unless the caller supplies a narrowing decoder; empty success responses return `undefined`. HTTP failures become `ApiError` with optional structured Problem Details. Callers can supply headers for future CSRF support, but no authentication workflow is implemented. No frontend environment variables are needed; never put secrets in browser-visible `VITE_` variables.

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
├── compose.yaml          # Backend and PostgreSQL local environment
├── frontend/             # React, TypeScript, Vite and frontend tests
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
