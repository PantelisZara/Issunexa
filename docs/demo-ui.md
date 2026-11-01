# Local synthetic demo walkthrough

The same compiled React UI discovers demo mode through `GET /api/demo`. Normal mode retains password sign-in. With the `public,demo` Spring profiles, visitors start an isolated synthetic workspace without credentials and use the two fixed requester/agent actions. There is no registration, account selector or public administrator action.

## Run and verify locally

Build the ordinary root Docker artifact and activate `SPRING_PROFILES_ACTIVE=public,demo` only against a separately provisioned, synthetic-only database, with the existing [public HTTPS contract](public-https.md) and [database privilege contract](database.md). Do not enable it on a real user database or load the ordinary E2E account fixture. Profile activation does not publish a service or provision a hosting provider.

For an entirely disposable, loopback HTTP simulation of the actual artifact:

```sh
cd frontend
npm ci
npm run test:e2e:demo
```

Use Node 24.22.2 and Docker/Compose/Buildx. The runner generates private per-run database credentials, starts a fresh database and the root Java/React artifact under `public,demo`, skips `accounts.sql`, and removes only that invocation's resources. Its Compose overlays are `compose.e2e.yaml`, `compose.artifact.e2e.yaml` and `compose.demo.e2e.yaml`; no host ports are published. Chromium runs against localhost in the service network to exercise Secure-cookie behavior. This is local HTTP simulation, not verification of an external HTTPS edge.

The separate desktop and mobile projects use four workspace admissions total, below the actual five-per-source-per-minute limit. They cover two independent visitors, workflow persistence and cross-visitor read/write denial, session/CSRF rotation and replay, fixed-action validation, and mobile overflow. Existing Nginx and ordinary artifact suites remain separate required gates. CI adds the demo step after them.

## Recruiter walkthrough

1. Choose **Start synthetic demo**. Use invented text only; the workspace includes a synthetic sample ticket.
2. As requester, create a ticket and add a comment. Read its history.
3. Choose **Switch to agent**, open that ticket from the list, claim it, move it to In progress and add a response.
4. Choose **Switch to requester**, reopen the ticket and reload. The server's persisted role, comments and status remain authoritative.
5. Choose **End demo** to invalidate browser access. Another independent visitor has their own tickets and role pair.

Role changes discard protected forms, results and navigation query state. After every demo transition the client retires its old token, obtains fresh CSRF and reloads the persisted session. A failed or uncertain POST is never replayed automatically; recovery uses safe reads and explicit user actions. An expired persisted cookie can invalidate the first CSRF read; the client retries that safe read once to prepare a fresh anonymous session.

## Limits and lifecycle

The server supplies an absolute 30-minute expiry, displayed as a local date and time. The UI retires local access at that timestamp without polling; server-side expiry is authoritative. A 20-minute idle HTTP session timeout, logout or a lost cookie can end access earlier. Ending a demo does not immediately delete its database rows, and a new browser session cannot adopt the old workspace.

Limits include the seed: 8 tickets, 20 comments and 128 history entries per workspace; 50 retained workspaces, 100 HTTP sessions and admissions of 5/source/minute and 50 globally. Capacity messages require an explicit later retry; there are no background retries. See the [backend contract](demo-backend.md) for bounded cleanup and admission details. Cleanup runs on relevant activity rather than on a background timer, so expired physical rows can remain while idle, including in database backups.

This implementation provides local demo preparation. Actual hosted CI, independent review, built-artifact E2E execution, official in-app Browser desktop/narrow inspection and genuine screenshots must be recorded before claiming final acceptance. No live hosted URL, public deployment, production traffic or external HTTPS acceptance is claimed here.
