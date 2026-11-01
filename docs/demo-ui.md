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

The [verified source snapshot](testing.md#verified-snapshot) records independent review, actual built-artifact tests, official in-app Browser desktop/narrow smoke and genuine captures; hosted results are linked from PR #20. No live hosted URL, public deployment, production traffic or external HTTPS acceptance is claimed here.

## Explore the demo in your browser

The automated test command above closes its stack. For interactive exploration, use Bash and Docker/Compose/Buildx in a fresh public clone. No host Java, Node or PostgreSQL is needed. Use a new shell without inherited `ISSUNEXA_*` overrides. This deliberately creates a **new synthetic-only project and volume**; never reuse an existing installation's project or `.env`.

From the repository root, create private temporary configuration:

```sh
DEMO_CONFIG_DIRECTORY=$(mktemp -d "${TMPDIR:-/tmp}/issunexa-demo.XXXXXXXX")
DEMO_PROJECT="issunexa-demo-$(date +%s)-$$"
cp .env.example "$DEMO_CONFIG_DIRECTORY/environment"
chmod 600 "$DEMO_CONFIG_DIRECTORY/environment"
```

Edit that temporary `environment` file and fill its three password fields with distinct new nonempty local passwords. Do not reuse existing secrets. Keep the variables in this terminal for the commands and cleanup below. Create the loopback-only overlay:

```sh
cat > "$DEMO_CONFIG_DIRECTORY/compose.yaml" <<'YAML'
services:
  postgres:
    ports: !reset []
  frontend:
    profiles: [ordinary-nginx]
  backend:
    build:
      context: .
      dockerfile: Dockerfile
    ports: !override
      - "127.0.0.1::9090"
    mem_limit: 512m
    memswap_limit: 512m
    environment:
      SPRING_PROFILES_ACTIVE: public,demo
      PORT: "9090"
      ISSUNEXA_PUBLIC_ORIGIN: https://demo.example.invalid
      ISSUNEXA_PUBLIC_INGRESS: peer
      ISSUNEXA_LOGIN_TRUSTED_PROXY: ""
YAML
DEMO_COMPOSE=(docker compose --project-directory "$PWD"
  --env-file "$DEMO_CONFIG_DIRECTORY/environment" -p "$DEMO_PROJECT"
  -f compose.yaml -f "$DEMO_CONFIG_DIRECTORY/compose.yaml")
"${DEMO_COMPOSE[@]}" config --quiet
"${DEMO_COMPOSE[@]}" up --build -d postgres backend
"${DEMO_COMPOSE[@]}" logs --tail=30 backend
"${DEMO_COMPOSE[@]}" port backend 9090
```

The last command prints `127.0.0.1:PORT`. Once Java has started, open `http://127.0.0.1:PORT` in Chromium and follow the walkthrough. The fake canonical HTTPS origin is used only for this loopback HTTP simulation; Chromium permits Secure cookies on loopback. It does not establish real TLS, and the binding must stay loopback-only. A fresh database has no ordinary users: **Start synthetic demo** creates its own isolated pair and seed. Other local browsers/devices are not an external hosting substitute.

Finish with the same terminal variables. This removes **only this newly created disposable synthetic project and its data**, so keep it separate from installations you want to preserve:

```sh
"${DEMO_COMPOSE[@]}" down -v --remove-orphans
docker image rm "$DEMO_PROJECT-backend:latest"
rm -- "$DEMO_CONFIG_DIRECTORY/environment" "$DEMO_CONFIG_DIRECTORY/compose.yaml"
rmdir -- "$DEMO_CONFIG_DIRECTORY"
```

The loopback artifact, fresh database and cleanup procedure were exercised locally on 2026-11-01; see [capture context](screenshots/README.md). Expired rows and session loss follow the same demo contract as automated verification. Keep generated configuration out of Git and never point this setup at real data.
