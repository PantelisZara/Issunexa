# Local development

The [README](../README.md#run-locally) gives the shortest Docker path. This guide covers the current local HTTP environment, hot reload and explicit account provisioning. For existing data, follow the [database upgrade procedure](database.md#safe-existing-installation-upgrade) before changing credentials or ownership.

## Full-stack Docker environment

Install Git and Docker with a running daemon, modern Compose and Buildx. Initial builds need network access for base images and Maven/npm dependencies. Host Java, Node, Maven and PostgreSQL are unnecessary for building/running the Docker stack; the optional account procedure below additionally uses Java 21.

Copy the HTTPS clone URL from this repository's **Code** menu. Replace the placeholder below with that URL and run this one-line assignment:

```sh
ISSUNEXA_REPOSITORY_URL='PASTE_HTTPS_CLONE_URL_HERE'
```

Then paste this block as a whole in the same terminal. It creates a local directory named `Issunexa`, independent of the repository's name:

```sh
git clone "$ISSUNEXA_REPOSITORY_URL" Issunexa
cd Issunexa
cp .env.example .env
```

Edit `.env`, setting distinct, nonempty values for `ISSUNEXA_POSTGRES_PASSWORD`, `ISSUNEXA_FLYWAY_PASSWORD` and `ISSUNEXA_DB_PASSWORD`. Retain the default distinct usernames for a fresh database. Never commit `.env`, and copy the template only if no local file exists.

```sh
docker compose config --quiet
docker compose up --build -d
docker compose ps
docker compose logs database-bootstrap backend
curl --fail http://localhost:3000/api/auth/csrf
```

PostgreSQL must become healthy, bootstrap must succeed and the backend must finish startup before the last command succeeds. The frontend can serve its shell before the backend is ready; retry an early `502` after startup. The CSRF response contains session metadata, not a sign-in account. Do not publish tokens or cookies from it.

| Default address | Purpose |
| --- | --- |
| `http://localhost:3000` | Compiled React application through Nginx |
| `http://localhost:8080` | Direct backend access for local tools |
| `http://localhost:8080/swagger-ui.html` | Public Swagger UI; redirects to `/swagger-ui/index.html` |
| `http://localhost:8080/v3/api-docs` | Public generated OpenAPI JSON |
| `127.0.0.1:5432` | PostgreSQL for local tools using the configured database identity |

Only `/api` is proxied by frontend Nginx; open Swagger on the backend port. All host bindings default to loopback. To resolve occupied ports, change `ISSUNEXA_FRONTEND_PORT`, `ISSUNEXA_BACKEND_PORT` or `ISSUNEXA_POSTGRES_PORT` in `.env` and use the corresponding URLs. The Vite proxy expects backend port 8080 unless [vite.config.ts](../frontend/vite.config.ts) is deliberately adapted.

Nginx serves compiled assets and falls back to HTML for client routes, with no API cache. The backend and frontend run as non-root users; frontend runtime uses a read-only filesystem and temporary storage. Docker builds skip tests. PostgreSQL data is stored in the `postgres_data` volume; image initialization variables apply only to an empty volume.

## Local demo accounts

A new database has no users, public registration or password-recovery API. [UserAccountService.createUser](../backend/src/main/java/io/github/panteliszara/issunexa/user/UserAccountService.java) is the internal account-provisioning boundary: it normalizes email, hashes the password with the configured encoder and persists the chosen role.

For a fresh, dedicated local database, the following **Bash / Java 21** procedure creates two synthetic accounts through that service. It requires the Docker stack above, Java 21 (`java`) and the Maven Wrapper prerequisites below. It starts a temporary second backend context on an automatically allocated port and closes it after provisioning. Use it only against your own local database; rerunning the account creation with the same emails fails the uniqueness constraint. Do not apply the E2E SQL fixture to a normal database.

From the repository root, load your own trusted `.env` as shell assignments. Values containing shell-special characters must be quoted in that file. Do not source configuration from an untrusted source. If you changed the backend/database bind ports, the JDBC URL below uses the configured database port.

```bash
. ./.env
export ISSUNEXA_DB_USERNAME ISSUNEXA_DB_PASSWORD
export ISSUNEXA_FLYWAY_USERNAME ISSUNEXA_FLYWAY_PASSWORD
export ISSUNEXA_DB_URL="jdbc:postgresql://localhost:${ISSUNEXA_POSTGRES_PORT:-5432}/${ISSUNEXA_POSTGRES_DB:-issunexa}"
read -r -s -p 'Choose a local demo password: ' ISSUNEXA_DEMO_PASSWORD
printf '\n'
export ISSUNEXA_DEMO_PASSWORD
cd backend
./mvnw --batch-mode --no-transfer-progress -DskipTests compile dependency:build-classpath \
  -Dmdep.outputFile=target/runtime-classpath.txt -DincludeScope=runtime
demo_dir=$(mktemp -d)
cat > "$demo_dir/DemoAccounts.java" <<'JAVA'
import org.springframework.boot.builder.SpringApplicationBuilder;
import io.github.panteliszara.issunexa.IssunexaApplication;
import io.github.panteliszara.issunexa.user.*;

class DemoAccounts {
    public static void main(String[] args) {
        try (var context = new SpringApplicationBuilder(IssunexaApplication.class).run("--server.port=0")) {
            var users = context.getBean(UserAccountService.class);
            users.createUser("requester@demo.invalid", "Demo Requester", System.getenv("ISSUNEXA_DEMO_PASSWORD"), UserRole.REQUESTER);
            users.createUser("agent@demo.invalid", "Demo Agent", System.getenv("ISSUNEXA_DEMO_PASSWORD"), UserRole.AGENT);
            System.out.println("Created synthetic local demo accounts.");
        }
    }
}
JAVA
java --class-path "target/classes:$(cat target/runtime-classpath.txt)" "$demo_dir/DemoAccounts.java"
rm -- "$demo_dir/DemoAccounts.java"
rmdir -- "$demo_dir"
unset ISSUNEXA_DEMO_PASSWORD
cd ..
```

Use the chosen password with `requester@demo.invalid` or `agent@demo.invalid` on the sign-in page. These reserved-domain identities are local examples. Requester tickets start empty; create them in the UI, then sign in as the agent to claim/change status/comment. No demo password is supplied or stored in source control. This procedure is an explicit development operation, not a user-management feature.

## Vite hot reload

Use Node **24.21.0** to match CI (the package supports Node 24 from 24.15.0). From the repository root after `.env` setup:

```sh
docker compose up --build -d postgres backend
```

This also runs the bootstrap dependency. In another terminal, from the repository root:

```sh
cd frontend
npm ci
npm run dev
```

Open the URL printed by Vite, normally `http://localhost:5173`. Relative `/api` requests are proxied to `http://localhost:8080`; no CORS configuration is needed. Static serving alone does not establish backend readiness. Stop Vite with `Ctrl+C`. `npm run build` type-checks and creates `dist/`; `npm run dev` remains the separate development workflow.

## Host-run backend

Use Java 21. On Linux/macOS the Maven Wrapper needs a POSIX shell, `curl` or `wget`, and `unzip`; Windows uses PowerShell and `mvnw.cmd`. No global Maven installation is required; the wrapper supplies Maven 3.9.16.

### Runtime database configuration

Provision the [database roles](database.md#database-roles-and-provisioning) first. The backend requires all five variables, without default credentials:

| Variable | Purpose |
| --- | --- |
| `ISSUNEXA_DB_URL` | Runtime JDBC URL, e.g. `jdbc:postgresql://localhost:5432/issunexa` |
| `ISSUNEXA_DB_USERNAME` / `ISSUNEXA_DB_PASSWORD` | Restricted runtime connection |
| `ISSUNEXA_FLYWAY_USERNAME` / `ISSUNEXA_FLYWAY_PASSWORD` | Separate migration connection to the same database |

The trusted `.env` loading and JDBC export in the demo procedure provide these values for a local Compose database. Stop the Docker backend first if it occupies port 8080:

```sh
docker compose stop backend
cd backend
./mvnw --version
./mvnw spring-boot:run
```

The backend uses port 8080 by default. Stop it with `Ctrl+C`. Alternatively, after building a JAR:

```sh
./mvnw --batch-mode --no-transfer-progress -DskipTests package
java -jar target/issunexa-0.0.1-SNAPSHOT.jar
```

These build/run commands are not verification substitutes. See [testing](testing.md) for Maven `verify` and Docker/Testcontainers requirements. Generated JARs, classpath files, dependencies and frontend output stay ignored.

## Shutdown and data

From the repository root:

```sh
docker compose logs -f backend frontend
docker compose down
```

Stop log following with `Ctrl+C`. Normal shutdown retains the database volume. **`docker compose down -v` deletes that project's database volume and its data**; use it only for an intentionally disposable installation, never as an upgrade step. Changing PostgreSQL initialization passwords in `.env` does not update an existing administrator password. Keep backup/upgrade procedures in [database.md](database.md).
