# Frontend

The browser client uses React, React Router, TypeScript and Vite. [App](../frontend/src/app/App.tsx) defines login and protected Ticket routes; [main.tsx](../frontend/src/main.tsx) mounts the application in `BrowserRouter`. [Architecture](architecture.md) describes Nginx and development proxy placement.

## Ownership boundaries

| Area | Responsibility |
| --- | --- |
| `src/app`, `src/pages`, `src/components` | Route composition, application shell and shared UI |
| `src/auth` | Session bootstrap, current-account state, CSRF metadata and protected-route presentation |
| `src/api` | Credentialed relative API requests and HTTP error representation |
| `src/tickets` | Ticket pages, request/response decoding, query normalization, forms and workflow controls |

These are client code boundaries under [frontend/src](../frontend/src/), not separate applications. Backend authorization remains authoritative even when the UI hides a control or redirects to login.

## API and session strategy

[apiRequest](../frontend/src/api/apiRequest.ts) sends relative `/api/...` requests with `credentials: 'include'`. It handles empty/204 responses and raises `ApiError` for unsuccessful HTTP responses, retaining validated Problem Details when available. JSON begins as `unknown`; [auth decoders](../frontend/src/auth/authApi.ts) and [Ticket decoders](../frontend/src/tickets/ticketDecoders.ts) validate external data before it becomes application state.

[AuthProvider](../frontend/src/auth/AuthProvider.tsx) fetches CSRF metadata before checking the session, sequences login/logout recovery and keeps account/CSRF state in memory. The browser manages the session cookie. Reloading reconstructs client state by asking the backend; credentials or bearer tokens are not persisted in browser storage. See [security](security.md) for the server lifecycle and authorization model.

Ticket mutations use the server-provided CSRF header. A relevant `401` expires the client session. Mutation failure handling prepares explicit recovery; it does not automatically replay an uncertain write. A `409` on a Ticket workflow action requires refreshing the detail before another action. [TicketWorkflow](../frontend/src/tickets/TicketWorkflow.tsx) coordinates this with the detail page.

## Ticket list and navigation state

[TicketListPage](../frontend/src/tickets/TicketListPage.tsx) derives applied search, filters, sorting and pagination from URL parameters via [readTicketQuery / serializeTicketQuery](../frontend/src/tickets/ticketQuery.ts). Invalid/repeated values fall back to supported defaults. Typing changes the draft field; Search submission applies `q`. Search/filter/sort/page-size changes reset `page` to zero. Rapid updates compose from pending URL edits until router state commits; browser navigation restores the committed URL state.

The backend supplies filtered content, order and totals. The client neither infers Ticket ownership nor recalculates those totals. List-to-detail/create navigation carries the originating list URL in router state for Back/Cancel; a direct entry falls back to `/app/tickets` through [ticketNavigation](../frontend/src/tickets/ticketNavigation.ts).

Loads use abortable requests and ignore superseded responses. URL state makes applied queries shareable and restorable, while draft form fields remain local; the two have different lifetimes. Runtime decoding also means malformed responses become recoverable errors rather than silently trusted typed objects.

## Build and verification

From the repository root, using Node 24.21.0 to match CI:

```sh
cd frontend
npm ci
npm run lint
npm run typecheck:e2e
npm test
npm run build
```

These commands are defined in [package.json](../frontend/package.json). The build type-checks and writes static assets to `dist/`; the [Nginx configuration](../frontend/nginx.conf) serves SPA routes with an HTML fallback while missing static files return `404`. Vitest covers client behavior; `npm run test:e2e` exercises real full-stack Chromium journeys. Setup prerequisites and report inspection remain in the [README](../README.md#end-to-end-tests).
