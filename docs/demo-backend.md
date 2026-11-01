# Isolated synthetic demo backend

The backend and [credential-free UI](demo-ui.md) provide an isolated synthetic demo verified locally through the built artifact. Independent review, backend tests, ordinary regressions and demo browser journeys are recorded in [testing.md](testing.md#verified-snapshot). Actual ingress/provider acceptance and a public hosted demo remain unverified. [ADR007](decisions/007-isolated-demo-workspaces.md) records the decision.

## Enable deliberately

Activate `public,demo` only with a dedicated, empty or consistent synthetic-only database, using the existing [public HTTPS contract](public-https.md) and [database provisioning](database.md). Existing ordinary accounts (including administrators), orphan tickets and incomplete synthetic account pairs reject startup. No data is converted or reset. There is no public registration or demo administrator. Normal profiles retain ordinary password login, roles and requester ownership; persisted demo accounts are rejected by password authentication in every profile. Demo mode rejects all password authentication.

Use strict local provisioning or the managed dedicated-schema procedure with separate runtime/migration credentials. V10 adds the workspace table, nullable user association, uniqueness/role constraints and expiry cleanup routine. V1–V9 are unchanged. Never enable demo on an existing user database. Configuration alone does not create public exposure or authorize a provider.

## API and session contract

| Operation | Behavior |
| --- | --- |
| `GET /api/demo` | Always available; returns `enabled`, `active`, `expiresAt`. Outside demo: false/false/null. Reads only an existing server session, creates no session, returns no workspace/account IDs or credentials; `Cache-Control: no-store`. |
| `GET /api/auth/csrf` | Existing session-bound CSRF bootstrap. Retain the secure cookie and returned header/token. |
| `POST /api/demo/start` | CSRF required. Creates one workspace if the session has none; otherwise resumes its live workspace as requester. Returns 204, no body. |
| `POST /api/demo/requester` | Authenticated bound session and CSRF required. Selects its persisted requester. Returns 204. |
| `POST /api/demo/agent` | Authenticated bound session and CSRF required. Selects its persisted AGENT. Returns 204. |
| `GET /api/auth/session` | Existing current account response; role reflects the persisted selected account. |
| `POST /api/auth/logout` | Existing CSRF-protected logout invalidates the session, its binding and CSRF state. |

Demo POSTs accept an empty body or empty JSON object, with no query string. Any supplied account/workspace/role/password selection is rejected. The only two switch actions are fixed routes. Start/resume/switch reuse the existing authentication strategy: rotate the session ID, retire CSRF, and explicitly save security context. Fetch a fresh CSRF token after every successful action. Old cookies cannot recover the rotated session, and old CSRF tokens cannot authorize subsequent unsafe requests. A lost cookie or logout cannot adopt an earlier workspace; its retained rows expire normally.

Workspace expiry is absolute 30 minutes according to PostgreSQL, independent of 20-minute idle HTTP session timeout and activity. Active metadata never extends it. Expired bound API requests receive 401 and session invalidation; service checks independently reject access even when expired rows remain. Cross-workspace or missing ticket IDs return 404, including staff claim/status/comment/history paths. Requester ownership and persisted-role rechecks remain enforced. Existing ticket text, pagination and transition limits still apply.

## Fixed resource bounds

| Resource | Hard maximum |
| --- | --- |
| Retained workspaces, including expired rows awaiting cleanup |50 |
| Accounts per workspace |2: one requester and one agent |
| Tickets per workspace, including the synthetic seed |8 |
| Comments across all workspace tickets |20 |
| History entries across all workspace tickets, including seed/create/claim/status |128 |
| Workspace lifetime |30 minutes absolute |
| HTTP sessions, including anonymous CSRF sessions |100 |
| New workspace admissions per verified source |5/minute |
| New workspace admissions globally |50/minute |
| Source budget map |128 keys maximum, no live-budget eviction |

The global PostgreSQL transaction advisory lock serializes retained admission and cleanup. A separate namespaced transaction advisory lock per persisted workspace serializes quota checks plus writes, and cleanup takes the same locks. This preserves runtime denial of workspace UPDATE. Quotas count committed rows after lock acquisition and include seeds. The history cap is needed because ordinary status transitions permit reopen cycles.

The embedded Tomcat manager synchronizes session creation before enforcing its cap; anonymous CSRF allocation cannot race past it. Sessions are not persisted to disk. Source admission reuses the existing trusted source resolver and ignores untrusted forwarding alternatives. Admission budgets are bounded in process memory and recover lazily on activity; the operational contract is one application process. Restart loses session and source budgets but cannot bypass persisted retention quotas. Generic 429 responses have no target/source identifiers, include `Retry-After: 60` and `Cache-Control: no-store`; the client must avoid automatic retry loops. Sustained abuse can temporarily deny new visitors within these bounds.

## Cleanup and privileges

`expire_demo_workspaces()` accepts no parameters and returns the number removed. Each call removes at most 5 expired synthetic workspaces and their tickets/comments/history/accounts. It refuses inconsistent references involving ordinary or another workspace's accounts rather than deleting those rows. Active and ordinary rows remain. Runtime cannot choose a target, extend expiry, delete arbitrary data, redefine the function or perform DDL. Runtime retains only SELECT/INSERT on the workspace table and explicit EXECUTE on this routine. Runtime Ticket UPDATE is limited to `title`, `description`, `status`, `priority`, `assignee_id`, `version` and `updated_at`. It cannot change requester, category, creation time or ID; immutable requester provenance prevents cleanup from reclassifying ordinary tickets. The existing ORM already marks the immutable fields accordingly. Both provisioners and the callback reject unexpected column ACLs and permit only those seven runtime UPDATE grants, without grant option. Legacy V9 table UPDATE is revoked before the narrow grant set is restored.

The function is migration-owned SECURITY DEFINER with `search_path=pg_catalog`, schema-qualified application references and revoked PUBLIC execution. Both strict and managed provisioning validate the deliberate table/index/routine allowlists, ownership, routine signature/security/language/search-path properties and ACL, including denial of outsider/PUBLIC/grant-option execution. The Flyway callback reapplies explicit runtime grants and rejects invalid routine state even with no migration pending. Unrelated routines remain rejected.

Cleanup runs during admission and at most once per minute on actual bound-session API activity. The exact liveness/readiness paths bypass demo session queries and cleanup; liveness remains database independent and readiness performs only its own bounded probe. Static resources also bypass the demo data filter. It never runs on a background database timer; idle data can remain physically present while the database sleeps. Expiry denial does not wait for deletion. Later activity clears bounded batches. An operator with the authorized runtime connection may invoke the same zero-argument routine repeatedly for expired data; no reset endpoint or arbitrary cutoff is provided. Do not manually delete live/user data as part of demo operation.

## Verification boundary

`DemoIntegrationTests` use real PostgreSQL and embedded HTTP sessions for two-visitor negative paths, forged selectors, correct-known-password rejection, old-cookie/CSRF replay, role switch/logout, absolute expiry before deletion, concurrent ticket/comment/history and retention quotas, source/global admission, startup guards, cleanup batches and anonymous-session saturation. Strict and managed privilege regressions additionally exercise real restricted runtime demo writes and cleanup, deny DELETE/UPDATE/DDL/signature misuse and preserve ordinary/active/inconsistently referenced rows. [PR19](https://github.com/PantelisZara/Issunexa/pull/19) records the 675-test integrated backend result and hosted gates; [PR #20](https://github.com/PantelisZara/Issunexa/pull/20) records UI and built-artifact browser acceptance. Future source changes still require matching affected gates.
