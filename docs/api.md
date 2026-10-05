# API guide

The running backend publishes [OpenAPI JSON](http://localhost:8080/v3/api-docs) and [Swagger UI](http://localhost:8080/swagger-ui.html). Use the configured backend port if it differs. Swagger is served directly by the backend, not through the frontend SPA. See [local setup](local-development.md) and the [security model](security.md).

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

Bounded login attempts can return generic `429` Problem Details with `Retry-After`; source admission runs before CSRF/body parsing. See [limits, recovery and proxy trust](security.md#bounded-login-attempts).

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
