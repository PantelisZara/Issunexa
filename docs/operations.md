# Operational probes

Two anonymous GET-only probes return fixed JSON with `status` and no component names, configuration, credentials or exception details. They do not create a session and responses use `Cache-Control: no-store`. They are documented here separately from the interactive business OpenAPI contract. Other health paths and management endpoints remain protected.

| Endpoint | Meaning | Response |
| --- | --- | --- |
| `/api/health/live` | The application HTTP process is serving. Performs no database work. | 200, `{"status":"UP"}` |
| `/api/health/ready` | The runtime datasource can acquire a connection and execute `SELECT 1`. | 200 UP, or 503 `{"status":"DOWN"}` |

Use **liveness** for continuous hosting health polling. A managed database may suspend while the process remains live; this is intentional. Readiness is an on-demand database diagnostic or a bounded deployment check. Repeated readiness polling opens connections and can keep the database awake, consuming free-tier compute. Do not use an external uptime monitor to defeat provider idle suspension.

The public profile bounds pool acquisition to three seconds; the probe sets a two-second JDBC statement timeout. These are separate phases, not a promised end-to-end latency. A first connection can wake a managed database and fail while it starts; retry deliberately. The default local profile retains its ordinary datasource acquisition settings.

Flyway and Hibernate validation complete before normal application startup. Migration/authentication failure prevents startup; liveness does not override that behavior. A readiness success proves connectivity at that instant, not schema privilege completeness, future availability, concurrency capacity or backup integrity. Sessions and login budgets remain in memory and reset after process restart.

Real-server/PostgreSQL tests deny new database logins after evicting idle connections, verify readiness503 within a bound, verify liveness200 without replenishing connections, restore logins and verify readiness recovery. The disposable E2E runner checks readiness and then the CSRF contract before fixtures/journeys. This is local failure/recovery evidence, not actual hosting acceptance. Provider HTTP-to-HTTPS behavior, cold starts/CPU limits, readiness alerts and backup/restore still require separate verification.
