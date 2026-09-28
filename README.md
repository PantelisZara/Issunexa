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

## Prerequisites

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

Creation accepts `title`, `description` and `priority` (`LOW`, `MEDIUM`, `HIGH` or `URGENT`). Title and description must not be blank; title is limited to 255 characters and priority is required. New Tickets start as `OPEN`.

AGENT and ADMIN can claim an unassigned Ticket for themselves with no request body. The authenticated account becomes the assignee; clients cannot select another account. Claiming preserves requester and status. Any repeated claim returns `409`, including a repeat by the same staff account. Ticket responses contain `assignee: null` when unassigned, or an assignee summary with only `id` and `displayName`.

Status changes accept only `status`, for example `PATCH /api/tickets/42/status` with `{"status":"IN_PROGRESS"}`. The allowed transitions are:

- `OPEN → IN_PROGRESS`
- `IN_PROGRESS → RESOLVED`
- `RESOLVED → IN_PROGRESS`
- `RESOLVED → CLOSED`

`CLOSED` is terminal in the current version. All other transitions, including requests for the current status, return `409 Conflict` Problem Details. Missing Tickets return `404`; missing/null status or an unsupported status value returns `400`.

Listing accepts zero-based `page` (default `0`) and `size` (default `20`, range `1`–`100`). For example, `GET /api/tickets?page=2&size=10` retrieves the third page. Empty pages return an empty `content` array with page metadata.

Optional listing filters are `status` (`OPEN`, `IN_PROGRESS`, `RESOLVED`, `CLOSED`) and `priority` (`LOW`, `MEDIUM`, `HIGH`, `URGENT`). Each accepts a single, case-sensitive value. Both filters use AND semantics: `GET /api/tickets?status=OPEN&priority=HIGH&page=0&size=10` returns only open, high-priority Tickets. Filtering occurs before pagination; omitting both filters leaves status and priority unrestricted.

Sorting accepts `sortBy` (`createdAt`, `updatedAt`, `title`) and `direction` (`asc`, `desc`), defaulting to `createdAt` and `desc`. Each accepts one case-sensitive value. For example, `GET /api/tickets?sortBy=title&direction=asc` orders by title ascending, then ID ascending. The ID tie-breaker always uses the selected direction.

Optional `q` performs case-insensitive substring search in title or description. A supplied value must contain non-whitespace text and be at most 100 characters before trimming; surrounding whitespace is trimmed. `%`, `_` and `\` match literally. Search combines with status and priority using AND, for example `GET /api/tickets?q=login&status=OPEN&priority=HIGH`.

Invalid input returns `400`; a missing Ticket returns `404`. Errors use `application/problem+json`, with field details for validation failures.

Comments are append-only in the current version. Authenticated users can create and list comments on Tickets they may access: REQUESTER on owned Tickets only, and AGENT/ADMIN on all Tickets, including historical Tickets without a requester. Hidden and missing Tickets return the same `404`. Closed Tickets can receive comments; adding a comment leaves the Ticket's status, requester, assignee, version and timestamps unchanged.

Comment creation accepts only `{"body":"Comment text"}`: nonblank plain text of at most 4000 characters before trimming. Outer whitespace is stripped; internal whitespace and newlines are preserved. The authenticated account is always the author. Responses contain `id`, `body`, `createdAt` and an `author` summary with only `id` and `displayName`.

Comment listing supports zero-based `page` (default `0`) and `size` (default `20`, range `1`–`100`), ordered by `createdAt ASC`, then `id ASC`. It returns `content`, `page`, `size`, `totalElements`, `totalPages`, `first` and `last`. There are no comment editing, deletion, search or sorting options.

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
├── README.md
└── backend/
    ├── .mvn/wrapper/     # Maven Wrapper configuration
    ├── mvnw              # Linux/macOS wrapper
    ├── mvnw.cmd          # Windows wrapper
    ├── pom.xml           # Backend build and dependencies
    └── src/
        ├── main/         # Application, Ticket API/model and migrations
        └── test/         # Service, MVC and PostgreSQL tests
```
