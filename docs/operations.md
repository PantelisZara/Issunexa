# Operational probes

Two anonymous GET-only probes return fixed JSON with `status` and no component names, configuration, credentials or exception details. They do not create a session and responses use `Cache-Control: no-store`. They are documented here separately from the interactive business OpenAPI contract. Other health paths and management endpoints remain protected.

| Endpoint | Meaning | Response |
| --- | --- | --- |
| `/api/health/live` | The application HTTP process is serving. Performs no database work. | 200, `{"status":"UP"}` |
| `/api/health/ready` | The runtime datasource can acquire a connection and execute `SELECT 1`. | 200 UP, or 503 `{"status":"DOWN"}` |

Use **liveness** for continuous hosting health polling. A managed database may suspend while the process remains live; this is intentional. Readiness is an on-demand database diagnostic or a bounded deployment check. Repeated readiness polling opens connections and can keep the database awake, consuming free-tier compute. Do not use an external uptime monitor to defeat provider idle suspension.

The public profile bounds pool acquisition to three seconds; the probe sets a two-second JDBC statement timeout. These are separate phases, not a promised end-to-end latency. A first connection can wake a managed database and fail while it starts; retry deliberately. The default local profile retains its ordinary datasource acquisition settings.

Flyway and Hibernate validation complete before normal application startup. Migration/authentication failure prevents startup; liveness does not override that behavior. A readiness success proves connectivity at that instant, not schema privilege completeness, future availability, concurrency capacity or backup integrity. Sessions and login budgets remain in memory and reset after process restart.

Real-server/PostgreSQL tests deny new database logins after evicting idle connections, verify readiness503 within a bound, verify liveness200 without replenishing connections, restore logins and verify readiness recovery. The disposable E2E runner checks readiness and then the CSRF contract before fixtures/journeys. These are local failure/recovery tests. Actual provider HTTPS, natural-idle startup and fresh-target managed recovery observations are recorded separately in [hosted acceptance](testing.md#hosted-demo-acceptance); quota-exhaustion and alert behavior are not inferred from these observations.

## Release and provider acceptance

Status on 2026-11-02: the [live synthetic demo](https://issunexa-demo.onrender.com) serves the pinned public revision [f4a5c0c9da05f37b56e28dfdcd0b8aa9bc80000f](https://github.com/PantelisZara/Issunexa/commit/f4a5c0c9da05f37b56e28dfdcd0b8aa9bc80000f). Use invented data only. [Hosted acceptance](testing.md#hosted-demo-acceptance) records completed HTTPS, natural 30-minute expiry, restart/persistence and resource observations. Local [screenshots](screenshots/README.md) and the [manual walkthrough](demo-ui.md#explore-the-demo-in-your-browser) remain available.

### Zero-cost deployment and limits

The deployment uses one Render Free Docker web service (512 MB; hosted JVM limits reported 512 MiB memory and memory plus swap, with a 0.15 CPU quota versus the creation form's 0.1 CPU) and one Neon Free PostgreSQL project with one 0.25 CU compute and two databases for the demo and fresh-target recovery. Both Free plans were checked; observed Render service/build charges, month-to-date total and projection were zero. No payment method was supplied during setup. Render showed no card; Neon did not independently expose saved-card absence. At the 2026-11-02 usage snapshot, Neon reported 0.25 CU-hours since November 1, 40.48 MB storage, 15.45 MB history and 1.09 MB network usage. These are dated cost/usage observations, not future billing or capacity guarantees. No subscription, paid upgrade, purchased domain or billing-enabled fallback is allowed. Recheck actual plans, quotas and account scope before any new provider operation; stop at a payment-method or unexpected-billing gate.

Terms checked 2026-11-02; recheck before use:

| Candidate | Relevant limits and failure behavior |
| --- | --- |
| [Render compute](https://render.com/docs/compute-plans) | Free web service: 0.1 CPU, 512 MB. |
| [Render Free](https://render.com/docs/free) | 750 shared running hours/month; 15-minute idle sleep, approximate one-minute platform wake, ephemeral disk. Without a payment method, bandwidth exhaustion suspends services and exhausted build allowance blocks new builds. Heavy service-initiated external traffic, including database access, can cause suspension; the threshold is unpublished and documented restoration requires paid compute. That recovery is outside this project's budget. |
| [Neon Free](https://neon.com/pricing), [plan enforcement](https://neon.com/docs/introduction/plans) | No card; 1 GB/project, 100 CU-hours/month, 5 GB egress, 5-minute idle suspension. 100 CU-hours covers 400 active hours at 0.25 CU, so continuously waking the database exceeds that allowance. Compute/egress exhaustion suspends it. Storage-cap failures can include deletes: leave headroom before cleanup needs it. Six-hour/1 GB change history and one manual snapshot are not a verified backup strategy. |

Published limits do not establish remaining account quotas. Render Free PostgreSQL expires after 30 days and is excluded; persistence uses Neon. One hosted startup after natural idle took 97.801 seconds, longer than the approximate platform wake quoted above. This does not bound future startup time or separately measure database wake time. Arbitrary provider suspension, cold-start delay and quota outages remain possible; there is no uptime or free-service guarantee.

### Deployment configuration

Pin a reviewed Git commit with all required checks successful; build the root [Dockerfile](../Dockerfile) from that revision and record the resulting image digest. Do not deploy from a moving branch without checking the actual built revision. Use one process, no persistent container disk and only synthetic data.

| Setting | Required value or source |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `public,demo` against the dedicated synthetic-only database |
| `PORT` | Hosting-assigned listener; Java binds the internal HTTP port |
| `ISSUNEXA_PUBLIC_ORIGIN` | Exact canonical HTTPS `onrender.com` origin assigned to the selected service |
| `ISSUNEXA_PUBLIC_INGRESS` | `peer` is a safe shared-budget fallback; `render-free` only after the actual ingress overwrite/no-alternate-entry contract is verified |
| `ISSUNEXA_LOGIN_TRUSTED_PROXY` | Unset; do not reuse the local Nginx trust setting |
| `ISSUNEXA_DB_URL` | Direct JDBC PostgreSQL URL with `sslmode=verify-full`, an accessible trusted CA file and `currentSchema` matching the provisioned schema |
| `ISSUNEXA_DB_USERNAME` / `ISSUNEXA_DB_PASSWORD` | Ordinary SQL runtime role and private generated secret |
| `ISSUNEXA_FLYWAY_USERNAME` / `ISSUNEXA_FLYWAY_PASSWORD` | Distinct ordinary migrator and private generated secret |
| Host health path | `/api/health/live`; bounded on-demand readiness separately, with no background DB keepalive |

Use the [managed SQL provisioner](managed-postgresql.md) before app startup. The provider owner/admin stays on the trusted operator side; do not put it in runtime variables. Provisioning/recovery use libpq trust; Java uses independent pgJDBC trust configuration. The deployed image's existing public `ISRG_Root_X1.pem` was readable by UID 10001 and worked with `sslmode=verify-full`; no root runtime or extra secret-file delivery was needed. An OS/Java trust store still does not automatically satisfy an arbitrary `sslrootcert` path. [PR #24](https://github.com/PantelisZara/Issunexa/pull/24) enforces certificate/hostname validation in each actual operator connection, including through a provider TLS proxy; backend `pg_stat_ssl` is not client TLS evidence. Keep verification enabled for both clients and revalidate when the endpoint, certificate chain or image changes. Avoid connection-string passwords, shell arguments/tracing, build arguments, frontend variables or logged environment dumps.

The [HTTPS contract](public-https.md), [demo boundaries](demo-backend.md), [managed provisioning](managed-postgresql.md) and [logical recovery](managed-recovery.md) remain authoritative. Keep provider setup small; no extra identity, object-storage or notification service is required.

### Acceptance sequence for future releases

1. Record account/workspace/project/region, actual Free status, no payment method and available quotas privately. Stop at any card, paid-plan or unexpected-billing gate.
2. Create only the authorized dedicated database and ordinary SQL roles. Verify direct-host TLS/CA, schema/role markers, migration/runtime ownership and negative privilege checks, provisioning rerun and preserved data. Unknown provider namespaces/default grants require review rather than weakening the scripts.
3. Deploy the pinned root artifact with the settings above. Verify startup migrations/validation, effective memory/CPU, no owner credentials, readiness and [liveness](#operational-probes). Render's [HTTP health checks](https://render.com/docs/health-checks) require 2xx/3xx within 5 seconds and allow 15 minutes for a new deployment; prove the actual platform behavior rather than inferring it from a local run.
4. Inspect the actual public HTTP redirect/HTTPS origin, cookie flags and host-only scope, CSRF/session rotation/replay/logout, protected writes and disabled explorers. Use official Browser QA for the full two-visitor requester/agent isolation and reload walkthrough; use controlled public-network checks for forwarded-header spoofing and source attribution. Never publish an IP/credential diagnostic endpoint.
5. Let the app and database idle naturally; measure real sleep/wake and persisted data after restart. Confirm Hikari retires idle connections and hosting liveness does not keep the DB awake. Readiness checks and cleanup must not become scheduled keepalives. Observe compute, egress and physical storage with ample headroom; logical row quotas alone are insufficient.
6. Exercise the [trusted fresh-target restore](managed-recovery.md) with separately authorized private archive storage and target resources. The current local archive/fresh-target managed restore was verified; off-host storage and archive retention remain absent. Record the source/digest/checksums/counts, restored reads/new writes/privilege denials and retained source. Establish retention for expired demo data in archives; never restore over an existing database or reset a volume to obtain green checks.
7. Publish a live URL only with actual public acceptance evidence and disclosed cold starts/quotas. If a gate fails, keep the local showcase available, preserve sanitized diagnostics and request the smallest needed decision. No paid rescue, disabled security/test, data reset or protection bypass.

### Release rollback

Retain the previous accepted revision/image and private backup manifest. Stop new traffic when integrity is uncertain. A compatible artifact rollback logs out in-memory sessions; do not reverse Flyway history or blindly downgrade against an incompatible newer schema. Preserve the database and recover only into a separately provisioned empty target using the matching release. If free quotas or provider policy suspend service, accept the outage and use the documented showcase until an authorized safe option exists.
