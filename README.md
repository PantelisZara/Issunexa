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

The integration tests share a disposable PostgreSQL 18.6 container and verify Ticket persistence, timestamps and database enum constraints against the Flyway-created schema. Each test rolls back its data changes. `@ServiceConnection` supplies connection details automatically; runtime database environment variables are not needed for tests. Testcontainers stops and removes the container after the suite.

Service unit tests and MVC controller slice tests run without PostgreSQL. `package` compiles the application, runs the full test suite and creates an executable JAR. The full suite requires the container runtime and fails if it is unavailable.

## Ticket API

| Method | Path | Successful response |
| --- | --- | --- |
| POST | `/api/tickets` | `201 Created`, Ticket JSON and a `Location` header |
| GET | `/api/tickets/{id}` | `200 OK` and Ticket JSON |
| GET | `/api/tickets` | `200 OK`, Ticket content and page metadata |

Creation accepts `title`, `description` and `priority` (`LOW`, `MEDIUM`, `HIGH` or `URGENT`). Title and description must not be blank; title is limited to 255 characters and priority is required. New Tickets start as `OPEN`.

Listing accepts zero-based `page` (default `0`) and `size` (default `20`, range `1`–`100`). For example, `GET /api/tickets?page=2&size=10` retrieves the third page. Empty pages return an empty `content` array with page metadata.

Invalid input returns `400`; a missing Ticket returns `404`. Errors use `application/problem+json`, with field details for validation failures.

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

The application uses Spring Boot's default HTTP port, `8080`. A request to `/` returns HTTP 404. Stop the application with `Ctrl+C`.

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
