# Product screenshots

## Current isolated synthetic demo

Captured on 2026-11-01 from the compiled Java/React root artifact at [51d1b08](https://github.com/PantelisZara/Issunexa/commit/51d1b08edd07f86e40e55794800820d5ac5b83a9), using the official in-app Browser and a fresh synthetic-only PostgreSQL database under `public,demo`. The source matches the reviewed UI snapshot; capture occurred before the commit was created. The container ran as UID 10001 under 512 MiB. These full-page images are unedited UI captures without browser chrome, tokens, credentials or private records.

The requester created the invented access request and comment through the UI. Its bound agent claimed it, changed Open to In progress and replied. Returning to requester and reloading preserved assignment/status/comments/history and removed staff actions. End demo then blocked the old ticket. Console errors were absent during the smoke. This was loopback HTTP simulation, not a public HTTPS deployment. Desktop viewport width 1280 px; mobile viewport 390 × 844 px, document 375 px wide, with no horizontal overflow.

### Start without credentials

![Synthetic demo entry explaining private workspace, lifetime and quotas](demo-entry.png)

### Agent workflow

![Synthetic agent detail with persisted status, comments and lifecycle history](demo-agent-desktop.png)

### Requester on a narrow screen

<img src="demo-requester-mobile.png" alt="Requester view at 390 px after reload, showing the persisted agent response and history without staff controls" width="390">

Follow the [recruiter walkthrough](../demo-ui.md#recruiter-walkthrough) or [manual loopback setup](../demo-ui.md#explore-the-demo-in-your-browser) to explore it. Automated tests use a separate disposable runner with no published ports.

---

## Historical ordinary-mode captures

These captures show the application built from the implementation at `8ccc563`, the `master` baseline used for these captures. Documentation changes do not alter the UI. Images were captured on 2026-10-05 from the compiled React application served by Nginx, connected to the real Spring Boot backend and a fresh PostgreSQL database.

The supplied Issunexa SVG wordmark and purple X identity are rendered by the application itself; see the [branding inventory](../../frontend/branding/README.md). These are page captures at a desktop breakpoint (1280-pixel viewport width), without browser chrome or development overlays. The detail capture is clipped to the workflow/conversation/history area; no page content was edited.

Only synthetic data appears: `Demo Requester`, `Demo Agent` and three training/demo tickets. The accounts were explicitly provisioned through the existing internal account service in a disposable local database. Tickets, comments, the claim and the status change were created through the actual UI; the detail was reloaded to confirm persisted state. No password, cookie, token, private host, customer data or deployment claim is shown. Dates are local capture times, not evidence of production activity.

### Ticket workspace

The staff view shows server-returned tickets, priority/category, assignment and search/filter/sort/pagination controls. The incident is assigned and in progress; the other examples remain open and unassigned.

![Actual ticket workspace showing three synthetic tickets and query controls](ticket-workspace.jpg)

### Ticket detail and staff workflow

The incident shows a real self-claim and `OPEN → IN_PROGRESS` transition, requester/agent comments and structured lifecycle history. The remaining staff action is resolution.

![Actual synthetic incident detail showing staff workflow, conversation and lifecycle history](ticket-detail.jpg)

### Sign in

The empty sign-in form uses the official wordmark. No credentials are entered in this capture.

![Actual Issunexa sign-in screen with empty email and password fields](login.jpg)

To explore these views locally, follow [setup and explicit demo-account provisioning](../local-development.md). A normal fresh database has no seeded users or tickets. These images illustrate currently implemented behavior, not a hosted demo or adoption metrics.
