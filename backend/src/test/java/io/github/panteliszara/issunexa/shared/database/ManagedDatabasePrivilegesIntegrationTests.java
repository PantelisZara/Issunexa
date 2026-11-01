package io.github.panteliszara.issunexa.shared.database;

import io.github.panteliszara.issunexa.IssunexaApplication;
import io.github.panteliszara.issunexa.ticket.TicketCategory;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketRepository;
import io.github.panteliszara.issunexa.ticket.TicketService;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import io.github.panteliszara.issunexa.ticket.comment.TicketCommentService;
import io.github.panteliszara.issunexa.user.UserAccountService;
import io.github.panteliszara.issunexa.user.UserRole;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.images.builder.Transferable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedDatabasePrivilegesIntegrationTests {

    @TempDir
    static Path certificates;

    private static final String OWNER = "managed_owner";
    private static final String MIGRATOR = "managed_migrator";
    private static final String RUNTIME = "managed_runtime";
    private static final String SCHEMA = "issunexa_managed";
    // Synthetic disposable credentials only. No provider credentials or user volumes.
    private static final String OWNER_PASSWORD = "synthetic-managed-owner-password";
    private static final String MIGRATION_PASSWORD = "synthetic-managed-migrator-password";
    private static final String RUNTIME_PASSWORD = "synthetic-managed-runtime-password";
    private static final String ROTATED_MIGRATION = "rotated-synthetic-migrator-password";
    private static final String ROTATED_RUNTIME = "rotated-synthetic-runtime-password";

    @Test
    void nonSuperuserProvisionsFreshSchemaAndPreservesDataThroughRerunsAndRotation() throws Exception {
        try (var postgres = postgres()) {
            postgres.start();
            prepareOwner(postgres);
            provisionSuccessfully(postgres, MIGRATION_PASSWORD, RUNTIME_PASSWORD);
            JdbcTemplate observer = jdbc(postgres, postgres.getUsername(), postgres.getPassword());
            assertThat(observer.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = ?", Boolean.class, OWNER)).isFalse();
            try (var context = application(postgres, MIGRATION_PASSWORD, RUNTIME_PASSWORD)) {
                JdbcTemplate runtime = context.getBean(JdbcTemplate.class);
                assertPrivileges(postgres, runtime, MIGRATION_PASSWORD);
                var migration = new JdbcTemplate(context.getBean(Flyway.class).getConfiguration().getDataSource());
                assertThat(migration.queryForObject("SELECT current_user", String.class)).isEqualTo(MIGRATOR);
                DemoPrivilegeAssertions.verify(migration, runtime);
                DemoPrivilegeAssertions.verifyRuntimeWorkflows(context);
                assertThat(migration.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
                assertThat(context.getBean(EntityManagerFactory.class).getProperties())
                        .containsEntry("hibernate.hbm2ddl.auto", "validate");
                var account = context.getBean(UserAccountService.class).createUser("managed@example.test", "Managed",
                        "synthetic-account-password", UserRole.REQUESTER);
                var tickets = context.getBean(TicketService.class);
                var ticket = tickets.createTicket("Managed persistence", "Dedicated schema", TicketPriority.HIGH,
                        TicketCategory.INCIDENT, account.getEmail());
                ticket.changeStatus(TicketStatus.IN_PROGRESS);
                context.getBean(TicketRepository.class).saveAndFlush(ticket);
                context.getBean(TicketCommentService.class).addComment(ticket.getId(), account.getEmail(), "Managed comment");
                assertThat(tickets.getTicket(ticket.getId(), account.getEmail()).getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
                assertThat(runtime.queryForObject("SELECT count(*) FROM ticket_comments", Long.class)).isEqualTo(1);
                assertThat(runtime.queryForObject("SELECT count(*) FROM ticket_history_entries", Long.class)).isEqualTo(1);
            }
            var rows = rows(observer);
            var history = observer.queryForList("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank");
            var sequences = sequences(observer);
            provisionSuccessfully(postgres, MIGRATION_PASSWORD, RUNTIME_PASSWORD);
            assertThat(rows(observer)).isEqualTo(rows);
            assertThat(sequences(observer)).isEqualTo(sequences);
            provisionSuccessfully(postgres, ROTATED_MIGRATION, ROTATED_RUNTIME);
            assertThat(rows(observer)).isEqualTo(rows);
            assertThat(sequences(observer)).isEqualTo(sequences);
            assertThat(observer.queryForList("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank"))
                    .isEqualTo(history);
            assertThatThrownBy(() -> jdbc(postgres, RUNTIME, RUNTIME_PASSWORD).queryForObject("SELECT 1", Integer.class))
                    .rootCause().isInstanceOfSatisfying(PSQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("28P01"));
            assertThatThrownBy(() -> jdbc(postgres, MIGRATOR, MIGRATION_PASSWORD).queryForObject("SELECT 1", Integer.class))
                    .rootCause().isInstanceOfSatisfying(PSQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("28P01"));
            try (var context = application(postgres, ROTATED_MIGRATION, ROTATED_RUNTIME)) {
                assertThat(context.getBean(Flyway.class).info().pending()).isEmpty();
                assertPrivileges(postgres, context.getBean(JdbcTemplate.class), ROTATED_MIGRATION);
                assertThat(rows(context.getBean(JdbcTemplate.class))).isEqualTo(rows);
                assertThat(observer.queryForList("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank"))
                        .isEqualTo(history);
            }
            assertThat(observer.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname = 'public'", Long.class)).isZero();
            observer.execute("CREATE TABLE public.users (id bigint, email text)");
            observer.update("INSERT INTO public.users VALUES (-1, 'decoy@example.test')");
            Flyway.configure().dataSource(url(postgres), MIGRATOR, ROTATED_MIGRATION).load().migrate();
            JdbcTemplate runtime = jdbc(postgres, RUNTIME, ROTATED_RUNTIME);
            assertThat(runtime.queryForList("SELECT email FROM users", String.class)).containsExactly("managed@example.test");
            assertDenied(runtime, "SELECT * FROM public.users");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"elevation", "outgoing-membership", "incoming-membership", "ownership", "unknown-table",
            "unknown-function", "unknown-type", "external-unowned-table", "external-unowned-function", "public-create", "role-settings", "external-table-grant", "external-column-grant", "external-function-grant",
            "application-column-grant", "application-public-column-grant", "default-grant", "late-callback-error",
            "cleanup-public-execute", "cleanup-search-path", "cleanup-null-search-path", "cleanup-runtime-owner", "cleanup-grant-option", "cleanup-outsider-execute",
            "ticket-column-requester", "ticket-column-public", "ticket-column-outsider", "ticket-column-select", "ticket-column-grant-option"})
    void rejectsUnexpectedStateWithoutChangingPasswordsSettingsAclsOrData(String scenario) throws Exception {
        try (var postgres = postgres()) {
            postgres.start();
            prepareOwner(postgres);
            provisionSuccessfully(postgres, MIGRATION_PASSWORD, RUNTIME_PASSWORD);
            Flyway.configure().dataSource(url(postgres), MIGRATOR, MIGRATION_PASSWORD).load().migrate();
            JdbcTemplate observer = jdbc(postgres, postgres.getUsername(), postgres.getPassword());
            JdbcTemplate migrator = jdbc(postgres, MIGRATOR, MIGRATION_PASSWORD);
            jdbc(postgres, RUNTIME, RUNTIME_PASSWORD).update("""
                    INSERT INTO users (email, display_name, password_hash, role, created_at, updated_at)
                    VALUES ('preserved@example.test', 'Preserved', 'synthetic-hash', 'REQUESTER', now(), now())
                    """);
            switch (scenario) {
                case "elevation" -> observer.execute("ALTER ROLE managed_runtime CREATEDB");
                case "outgoing-membership" -> {
                    observer.execute("CREATE ROLE unrelated");
                    observer.execute("GRANT unrelated TO managed_runtime");
                }
                case "incoming-membership" -> {
                    observer.execute("CREATE ROLE unrelated");
                    observer.execute("GRANT managed_runtime TO unrelated");
                }
                case "ownership" -> observer.execute("ALTER TABLE issunexa_managed.tickets OWNER TO managed_owner");
                case "unknown-table" -> migrator.execute("CREATE TABLE unexpected (value text)");
                case "ticket-column-requester" -> migrator.execute("GRANT UPDATE (requester_id) ON tickets TO managed_runtime");
                case "ticket-column-public" -> migrator.execute("GRANT UPDATE (title) ON tickets TO PUBLIC");
                case "ticket-column-select" -> migrator.execute("GRANT SELECT (title) ON tickets TO managed_runtime");
                case "ticket-column-grant-option" -> migrator.execute("GRANT UPDATE (title) ON tickets TO managed_runtime WITH GRANT OPTION");
                case "ticket-column-outsider" -> {
                    observer.execute("CREATE ROLE unrelated");
                    migrator.execute("GRANT UPDATE (title) ON tickets TO unrelated");
                }
                case "cleanup-public-execute" -> migrator.execute("GRANT EXECUTE ON FUNCTION expire_demo_workspaces() TO PUBLIC");
                case "cleanup-null-search-path" -> migrator.execute("ALTER FUNCTION expire_demo_workspaces() RESET ALL");
                case "cleanup-runtime-owner" -> observer.execute("ALTER FUNCTION issunexa_managed.expire_demo_workspaces() OWNER TO managed_runtime");
                case "cleanup-search-path" -> migrator.execute("ALTER FUNCTION expire_demo_workspaces() SET search_path = public");
                case "cleanup-grant-option" -> migrator.execute("GRANT EXECUTE ON FUNCTION expire_demo_workspaces() TO managed_runtime WITH GRANT OPTION");
                case "cleanup-outsider-execute" -> {
                    observer.execute("CREATE ROLE unrelated");
                    migrator.execute("GRANT EXECUTE ON FUNCTION expire_demo_workspaces() TO unrelated");
                }
                case "unknown-function" -> migrator.execute("CREATE FUNCTION unexpected() RETURNS integer LANGUAGE sql AS 'SELECT 1'");
                case "unknown-type" -> migrator.execute("CREATE TYPE unexpected AS ENUM ('value')");
                case "external-unowned-table" -> {
                    observer.execute("CREATE SCHEMA extensions");
                    observer.execute("CREATE EXTENSION hstore WITH SCHEMA extensions");
                    observer.execute("CREATE TABLE extensions.unrelated (value text)");
                    observer.update("INSERT INTO extensions.unrelated VALUES ('preserved external data')");
                }
                case "external-unowned-function" -> {
                    observer.execute("CREATE SCHEMA extensions");
                    observer.execute("CREATE EXTENSION hstore WITH SCHEMA extensions");
                    observer.execute("CREATE FUNCTION extensions.unrelated() RETURNS integer LANGUAGE sql AS 'SELECT 1'");
                }
                case "public-create" -> observer.execute("GRANT CREATE ON SCHEMA public TO PUBLIC");
                case "role-settings" -> observer.execute("ALTER ROLE managed_runtime SET search_path = public");
                case "external-table-grant" -> {
                    observer.execute("CREATE SCHEMA extensions");
                    observer.execute("CREATE EXTENSION hstore WITH SCHEMA extensions");
                    observer.execute("CREATE TABLE extensions.provider_data (value text)");
                    observer.execute("ALTER EXTENSION hstore ADD TABLE extensions.provider_data");
                    observer.execute("GRANT SELECT ON extensions.provider_data TO managed_runtime");
                }
                case "external-column-grant" -> {
                    observer.execute("CREATE SCHEMA extensions");
                    observer.execute("CREATE EXTENSION hstore WITH SCHEMA extensions");
                    observer.execute("CREATE TABLE extensions.provider_data (value text)");
                    observer.execute("ALTER EXTENSION hstore ADD TABLE extensions.provider_data");
                    observer.execute("GRANT SELECT (value) ON extensions.provider_data TO managed_runtime");
                }
                case "external-function-grant" -> {
                    observer.execute("CREATE SCHEMA extensions");
                    observer.execute("CREATE EXTENSION hstore WITH SCHEMA extensions");
                    observer.execute("CREATE FUNCTION extensions.provider_function() RETURNS integer LANGUAGE sql AS 'SELECT 1'");
                    observer.execute("ALTER EXTENSION hstore ADD FUNCTION extensions.provider_function()");
                    observer.execute("GRANT EXECUTE ON FUNCTION extensions.provider_function() TO managed_runtime");
                }
                case "application-column-grant" -> observer.execute("GRANT UPDATE (display_name) ON issunexa_managed.users TO managed_runtime");
                case "application-public-column-grant" -> observer.execute("GRANT UPDATE (display_name) ON issunexa_managed.users TO PUBLIC");
                case "default-grant" -> migrator.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA issunexa_managed GRANT SELECT ON TABLES TO managed_runtime");
                case "late-callback-error" -> migrator.execute("ALTER TABLE tickets RENAME COLUMN id TO renamed_id");
                default -> throw new IllegalArgumentException(scenario);
            }
            var before = snapshot(observer);
            if (scenario.startsWith("cleanup-")) {
                assertThatThrownBy(() -> Flyway.configure().dataSource(url(postgres), MIGRATOR, MIGRATION_PASSWORD).load().migrate())
                        .rootCause().isInstanceOfSatisfying(PSQLException.class,
                                error -> assertThat(error.getMessage()).contains("Unexpected demo routine"));
                assertThat(snapshot(observer)).as("no-pending callback: " + scenario).isEqualTo(before);
            }
            if (scenario.startsWith("ticket-column-")) {
                assertThatThrownBy(() -> Flyway.configure().dataSource(url(postgres), MIGRATOR, MIGRATION_PASSWORD).load().migrate())
                        .rootCause().isInstanceOfSatisfying(PSQLException.class,
                                error -> assertThat(error.getMessage()).contains("column grants"));
                assertThat(snapshot(observer)).as("no-pending callback: " + scenario).isEqualTo(before);
            }
            var result = provision(postgres, ROTATED_MIGRATION, ROTATED_RUNTIME, Map.of());
            assertThat(result.getExitCode()).as(scenario).isNotZero();
            assertThat(result.getStderr()).as(scenario).doesNotContain(ROTATED_MIGRATION, ROTATED_RUNTIME);
            if (scenario.startsWith("external-") && scenario.endsWith("grant")) {
                assertThat(result.getStderr()).contains("Unexpected explicit application grants");
            }
            if (scenario.equals("external-unowned-table")) {
                assertThat(observer.queryForList("SELECT value FROM extensions.unrelated", String.class))
                        .containsExactly("preserved external data");
            }
            if (scenario.equals("late-callback-error")) {
                assertThat(result.getStderr()).contains("column", "does not exist");
            }
            assertThat(snapshot(observer)).as(scenario).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"role-collision", "schema-collision", "public-data", "unknown-schema", "invalid-identifier",
            "insecure-tls", "pooled-host", "wrong-owner", "superuser"})
    void refusesUnsafeInitialTargetsWithoutMutations(String scenario) throws Exception {
        try (var postgres = postgres()) {
            postgres.start();
            prepareOwner(postgres);
            JdbcTemplate observer = jdbc(postgres, postgres.getUsername(), postgres.getPassword());
            Map<String, String> overrides = switch (scenario) {
                case "role-collision" -> { observer.execute("CREATE ROLE managed_runtime LOGIN"); yield Map.of(); }
                case "schema-collision" -> { observer.execute("CREATE SCHEMA issunexa_managed"); yield Map.of(); }
                case "public-data" -> {
                    observer.execute("CREATE TABLE public.unrelated (value text)");
                    observer.update("INSERT INTO public.unrelated VALUES ('preserved')");
                    yield Map.of();
                }
                case "unknown-schema" -> { observer.execute("CREATE SCHEMA unrelated"); yield Map.of(); }
                case "invalid-identifier" -> Map.of("ISSUNEXA_DB_SCHEMA", "invalid;schema");
                case "insecure-tls" -> Map.of("PGSSLMODE", "require");
                case "pooled-host" -> Map.of("PGHOST", "ep-example-pooler.example.invalid");
                case "wrong-owner" -> {
                    observer.execute("CREATE ROLE other_owner LOGIN CREATEROLE PASSWORD 'synthetic-managed-owner-password'");
                    yield Map.of("PGUSER", "other_owner");
                }
                case "superuser" -> Map.of("PGUSER", postgres.getUsername(), "PGPASSWORD", postgres.getPassword());
                default -> throw new IllegalArgumentException(scenario);
            };
            var before = snapshot(observer);
            var result = provision(postgres, MIGRATION_PASSWORD, RUNTIME_PASSWORD, overrides);
            assertThat(result.getExitCode()).as(scenario).isNotZero();
            assertThat(result.getStdout() + result.getStderr()).doesNotContain(MIGRATION_PASSWORD, RUNTIME_PASSWORD);
            assertThat(snapshot(observer)).as(scenario).isEqualTo(before);
            if (scenario.equals("public-data")) {
                assertThat(observer.queryForList("SELECT value FROM public.unrelated", String.class)).containsExactly("preserved");
            }
        }
    }

    @Test
    void permitsExtensionOwnedProviderNamespaceWithoutChangingItsObjects() throws Exception {
        try (var postgres = postgres()) {
            postgres.start();
            prepareOwner(postgres);
            JdbcTemplate observer = jdbc(postgres, postgres.getUsername(), postgres.getPassword());
            observer.execute("CREATE SCHEMA extensions");
            observer.execute("CREATE EXTENSION hstore WITH SCHEMA extensions");
            observer.execute("CREATE TABLE extensions.provider_data (value text)");
            observer.execute("ALTER EXTENSION hstore ADD TABLE extensions.provider_data");
            observer.update("INSERT INTO extensions.provider_data VALUES ('provider-owned')");
            var before = observer.queryForList("SELECT c.oid, c.relname, c.relowner, c.relacl::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'extensions' ORDER BY c.oid");
            provisionSuccessfully(postgres, MIGRATION_PASSWORD, RUNTIME_PASSWORD);
            assertThat(observer.queryForList("SELECT c.oid, c.relname, c.relowner, c.relacl::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'extensions' ORDER BY c.oid")).isEqualTo(before);
            assertThat(observer.queryForList("SELECT value FROM extensions.provider_data", String.class)).containsExactly("provider-owned");
            assertDenied(jdbc(postgres, RUNTIME, RUNTIME_PASSWORD), "SELECT * FROM extensions.provider_data");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema-create", "table-select", "sequence-usage", "type-usage"})
    void rejectsOutsiderNamespaceGrantsBeforeProvisioningOrNoPendingCallbackChanges(String scenario) throws Exception {
        try (var postgres = postgres()) {
            postgres.start();
            prepareOwner(postgres);
            provisionSuccessfully(postgres, MIGRATION_PASSWORD, RUNTIME_PASSWORD);
            Flyway.configure().dataSource(url(postgres), MIGRATOR, MIGRATION_PASSWORD).load().migrate();
            JdbcTemplate observer = jdbc(postgres, postgres.getUsername(), postgres.getPassword());
            jdbc(postgres, RUNTIME, RUNTIME_PASSWORD).update("""
                    INSERT INTO users (email, display_name, password_hash, role, created_at, updated_at)
                    VALUES ('preserved@example.test', 'Preserved', 'synthetic-hash', 'REQUESTER', now(), now())
                    """);
            observer.execute("CREATE ROLE unrelated");
            switch (scenario) {
                case "schema-create" -> observer.execute("GRANT USAGE, CREATE ON SCHEMA issunexa_managed TO unrelated");
                case "table-select" -> observer.execute("GRANT SELECT ON issunexa_managed.users TO unrelated");
                case "sequence-usage" -> observer.execute("GRANT USAGE ON SEQUENCE issunexa_managed.users_id_seq TO unrelated");
                case "type-usage" -> observer.execute("GRANT USAGE ON TYPE issunexa_managed.users TO unrelated");
                default -> throw new IllegalArgumentException(scenario);
            }
            var before = snapshot(observer);
            assertThatThrownBy(() -> Flyway.configure().dataSource(url(postgres), MIGRATOR, MIGRATION_PASSWORD).load().migrate())
                    .rootCause().isInstanceOfSatisfying(PSQLException.class,
                            error -> assertThat(error.getMessage()).contains("Unexpected managed namespace grants"));
            assertThat(snapshot(observer)).as("no-pending callback: " + scenario).isEqualTo(before);
            var result = provision(postgres, ROTATED_MIGRATION, ROTATED_RUNTIME, Map.of());
            assertThat(result.getExitCode()).as(scenario).isNotZero();
            assertThat(result.getStderr()).contains("Unexpected managed namespace grants")
                    .doesNotContain(ROTATED_MIGRATION, ROTATED_RUNTIME);
            assertThat(snapshot(observer)).as("provisioning: " + scenario).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema-marker", "migration-marker", "runtime-marker", "incorrect-pair", "runtime-column-grant", "public-column-grant"})
    void noPendingMigrationStillRejectsMissingOrIncorrectManagedMetadata(String scenario) throws Exception {
        try (var postgres = postgres()) {
            postgres.start();
            prepareOwner(postgres);
            provisionSuccessfully(postgres, MIGRATION_PASSWORD, RUNTIME_PASSWORD);
            Flyway.configure().dataSource(url(postgres), MIGRATOR, MIGRATION_PASSWORD).load().migrate();
            JdbcTemplate observer = jdbc(postgres, postgres.getUsername(), postgres.getPassword());
            switch (scenario) {
                case "schema-marker" -> observer.execute("COMMENT ON SCHEMA issunexa_managed IS NULL");
                case "migration-marker" -> observer.execute("COMMENT ON ROLE managed_migrator IS NULL");
                case "runtime-marker" -> observer.execute("COMMENT ON ROLE managed_runtime IS NULL");
                case "runtime-column-grant" -> observer.execute("GRANT UPDATE (display_name) ON issunexa_managed.users TO managed_runtime");
                case "public-column-grant" -> observer.execute("GRANT UPDATE (display_name) ON issunexa_managed.users TO PUBLIC");
                case "incorrect-pair" -> observer.execute("COMMENT ON SCHEMA issunexa_managed IS 'Issunexa managed schema for database test migrator managed_migrator runtime wrong_runtime administrator managed_owner'");
                default -> throw new IllegalArgumentException(scenario);
            }
            var before = snapshot(observer);
            assertThatThrownBy(() -> Flyway.configure().dataSource(url(postgres), MIGRATOR, MIGRATION_PASSWORD).load().migrate())
                    .rootCause().isInstanceOfSatisfying(PSQLException.class,
                            error -> assertThat(error.getMessage()).contains("managed"));
            assertThat(snapshot(observer)).isEqualTo(before);
        }
    }

    @Test
    void recoversManagedBackupIntoFreshTlsDatabaseWithDataSequencesAndPrivileges() throws Exception {
        try (var source = postgres(); var target = postgres().withDatabaseName("recovery_target")) {
            source.start();
            target.start();
            prepareOwner(source);
            prepareOwner(target);
            provisionSuccessfully(source, MIGRATION_PASSWORD, RUNTIME_PASSWORD);
            assertRecoverySuccess(provision(target, ROTATED_MIGRATION, ROTATED_RUNTIME,
                    Map.of("ISSUNEXA_FLYWAY_USERNAME", "recovery_migrator", "ISSUNEXA_DB_USERNAME", "recovery_runtime")));
            Long ticketId;
            try (var context = application(source, MIGRATION_PASSWORD, RUNTIME_PASSWORD)) {
                var accounts = context.getBean(UserAccountService.class);
                var requester = accounts.createUser("recovery@example.test", "Recovery requester",
                        "synthetic-requester-password", UserRole.REQUESTER);
                var agent = accounts.createUser("recovery-agent@example.test", "Recovery agent",
                        "synthetic-agent-password", UserRole.AGENT);
                var tickets = context.getBean(TicketService.class);
                var ticket = tickets.createTicket("Recovered ticket", "Synthetic recovery data", TicketPriority.HIGH,
                        TicketCategory.INCIDENT, requester.getEmail());
                ticketId = ticket.getId();
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(
                                    agent.getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));
                    tickets.claimTicket(ticketId, agent.getEmail());
                    tickets.changeStatus(ticketId, TicketStatus.IN_PROGRESS, agent.getEmail());
                } finally {
                    SecurityContextHolder.clearContext();
                }
                context.getBean(TicketCommentService.class).addComment(ticketId, requester.getEmail(), "Restored comment");
            }
            var sourceObserver = jdbc(source, source.getUsername(), source.getPassword());
            var expectedRows = rows(sourceObserver);
            var expectedSequences = sequences(sourceObserver);
            var expectedHistory = sourceObserver.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
            var sourceBefore = snapshot(sourceObserver);
            byte[] archive = backup(source);
            assertThat(snapshot(sourceObserver)).isEqualTo(sourceBefore);
            target.copyFileToContainer(Transferable.of(archive, 0600), "/tmp/trusted.dump");
            var result = recovery(target, ROTATED_MIGRATION, "restore", "/tmp/trusted.dump", Map.of("PGUSER", "recovery_migrator"));
            assertRecoverySuccess(result);
            var targetObserver = jdbc(target, target.getUsername(), target.getPassword());
            assertThat(targetObserver.queryForObject("SELECT has_function_privilege('public', 'issunexa_managed.expire_demo_workspaces()', 'EXECUTE')", Boolean.class)).isFalse();
            assertThat(targetObserver.queryForObject("SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE oid = 'issunexa_managed.expire_demo_workspaces()'::regprocedure", String.class)).isEqualTo("recovery_migrator");
            assertThat(jdbc(target, "recovery_runtime", ROTATED_RUNTIME).queryForObject("SELECT expire_demo_workspaces()", Integer.class)).isZero();
            assertDenied(jdbc(target, "recovery_runtime", ROTATED_RUNTIME), "UPDATE tickets SET requester_id = NULL");
            assertThat(rows(targetObserver)).isEqualTo(expectedRows);
            assertThat(sequences(targetObserver)).isEqualTo(expectedSequences);
            assertThat(targetObserver.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"))
                    .isEqualTo(expectedHistory);
            assertThat(targetObserver.queryForObject("SELECT obj_description(oid, 'pg_namespace') FROM pg_namespace WHERE nspname = ?", String.class, SCHEMA))
                    .contains("database recovery_target");
            try (var context = application(target, ROTATED_MIGRATION, ROTATED_RUNTIME, "recovery_migrator", "recovery_runtime")) {
                assertThat(context.getBean(Flyway.class).info().pending()).isEmpty();
                assertPrivileges(target, context.getBean(JdbcTemplate.class), ROTATED_MIGRATION, "recovery_migrator", "recovery_runtime");
                assertThat(rows(targetObserver)).isEqualTo(expectedRows);
                var tickets = context.getBean(TicketService.class);
                assertThat(tickets.getTicket(ticketId, "recovery@example.test").getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
                var next = tickets.createTicket("After recovery", "New write", TicketPriority.LOW,
                        TicketCategory.SERVICE_REQUEST, "recovery@example.test");
                assertThat(next.getId()).isGreaterThan(ticketId);
                context.getBean(TicketCommentService.class).addComment(next.getId(), "recovery@example.test", "New comment");
                assertThat(targetObserver.queryForObject("SELECT count(*) FROM ticket_comments", Long.class)).isEqualTo(2);
                assertThat(targetObserver.queryForObject("SELECT count(*) FROM ticket_history_entries", Long.class)).isEqualTo(4);
            }
            var before = snapshot(targetObserver);
            assertRecoveryFailure(recovery(target, ROTATED_MIGRATION, "restore", "/tmp/trusted.dump", Map.of("PGUSER", "recovery_migrator")), "empty provisioned");
            assertThat(snapshot(targetObserver)).isEqualTo(before);
        }
    }

    @Test
    void refusesUnsafeRecoveryAndRollsBackLateFailureWithoutChangingTarget() throws Exception {
        try (var source = postgres(); var target = postgres()) {
            source.start();
            target.start();
            prepareOwner(source);
            prepareOwner(target);
            provisionSuccessfully(source, MIGRATION_PASSWORD, RUNTIME_PASSWORD);
            provisionSuccessfully(target, MIGRATION_PASSWORD, RUNTIME_PASSWORD);
            Flyway.configure().dataSource(url(source), MIGRATOR, MIGRATION_PASSWORD).load().migrate();
            byte[] archive = backup(source);
            target.copyFileToContainer(Transferable.of(archive, 0600), "/tmp/trusted.dump");
            var observer = jdbc(target, target.getUsername(), target.getPassword());
            var before = snapshot(observer);
            target.copyFileToContainer(Transferable.of("invalid archive", 0600), "/tmp/corrupt.dump");
            assertRecoveryFailure(recovery(target, MIGRATION_PASSWORD, "restore", "/tmp/corrupt.dump", Map.of()), "valid archive");
            target.copyFileToContainer(Transferable.of(java.util.Arrays.copyOf(archive, archive.length - 40), 0600), "/tmp/truncated.dump");
            assertThat(target.execInContainer("pg_restore", "--list", "/tmp/truncated.dump").getExitCode()).isZero();
            var truncated = recovery(target, MIGRATION_PASSWORD, "restore", "/tmp/truncated.dump", Map.of());
            assertRecoveryFailure(truncated, "pg_restore:");
            target.copyFileToContainer(Transferable.of(source.copyFileFromContainer("/tmp/managed-test.crt", InputStream::readAllBytes), 0600), "/tmp/wrong-ca.crt");
            assertRecoveryFailure(recovery(target, MIGRATION_PASSWORD, "restore", "/tmp/trusted.dump", Map.of("PGSSLROOTCERT", "/tmp/wrong-ca.crt")), "certificate verify failed");
            assertRecoveryFailure(recovery(target, MIGRATION_PASSWORD, "restore", "/tmp/trusted.dump", Map.of("PGSSLMODE", "require")), "verify-full");
            assertRecoveryFailure(recovery(target, MIGRATION_PASSWORD, "restore", "/tmp/trusted.dump", Map.of("ISSUNEXA_DB_SCHEMA", "another_schema")), "selected schema");
            assertRecoveryFailure(recovery(target, MIGRATION_PASSWORD, "restore", "/tmp/trusted.dump", Map.of("PGUSER", target.getUsername(), "PGPASSWORD", target.getPassword())), "schema-owning migrator");
            assertThat(snapshot(observer)).isEqualTo(before);
            observer.execute("CREATE COLLATION issunexa_managed.preexisting FROM pg_catalog.\"C\"");
            var withCollation = snapshot(observer);
            assertRecoveryFailure(recovery(target, MIGRATION_PASSWORD, "restore", "/tmp/trusted.dump", Map.of()), "empty provisioned");
            assertThat(snapshot(observer)).isEqualTo(withCollation);
            assertThat(observer.queryForObject("SELECT count(*) FROM pg_collation WHERE collname = 'preexisting'", Long.class)).isEqualTo(1);
            observer.execute("DROP COLLATION issunexa_managed.preexisting");
            observer.execute("ALTER ROLE managed_runtime CREATEDB");
            var elevated = snapshot(observer);
            assertRecoveryFailure(recovery(target, MIGRATION_PASSWORD, "restore", "/tmp/trusted.dump", Map.of()), "role privileges");
            assertThat(snapshot(observer)).isEqualTo(elevated);
            observer.execute("ALTER ROLE managed_runtime NOCREATEDB");
            observer.execute("CREATE ROLE unrelated");
            observer.execute("GRANT unrelated TO managed_runtime");
            var membership = snapshot(observer);
            assertRecoveryFailure(recovery(target, MIGRATION_PASSWORD, "restore", "/tmp/trusted.dump", Map.of()), "role privileges");
            assertThat(snapshot(observer)).isEqualTo(membership);
            observer.execute("REVOKE unrelated FROM managed_runtime");
            observer.execute("DROP ROLE unrelated");
            String schemaMarker = observer.queryForObject("SELECT obj_description(oid, 'pg_namespace') FROM pg_namespace WHERE nspname = ?", String.class, SCHEMA);
            observer.execute("COMMENT ON SCHEMA issunexa_managed IS NULL");
            var unmarked = snapshot(observer);
            assertRecoveryFailure(recovery(target, MIGRATION_PASSWORD, "restore", "/tmp/trusted.dump", Map.of()), "markers");
            assertThat(snapshot(observer)).isEqualTo(unmarked);
            observer.execute("COMMENT ON SCHEMA issunexa_managed IS '" + schemaMarker + "'");
            // Restore-only default revocation must not permit an unsafe routine shape.
            jdbc(source, MIGRATOR, MIGRATION_PASSWORD).execute("ALTER FUNCTION expire_demo_workspaces() SECURITY INVOKER");
            target.copyFileToContainer(Transferable.of(backup(source), 0600), "/tmp/unsafe-routine.dump");
            assertRecoveryFailure(recovery(target, MIGRATION_PASSWORD, "restore", "/tmp/unsafe-routine.dump", Map.of()), "Unexpected demo routine");
            assertThat(snapshot(observer)).isEqualTo(before);
            jdbc(source, MIGRATOR, MIGRATION_PASSWORD).execute("ALTER FUNCTION expire_demo_workspaces() SECURITY DEFINER");
            // Valid archive and early guard; the shared callback fails after all restored objects/data.
            jdbc(source, MIGRATOR, MIGRATION_PASSWORD).execute("ALTER TABLE tickets RENAME COLUMN id TO renamed_id");
            target.copyFileToContainer(Transferable.of(backup(source), 0600), "/tmp/late-failure.dump");
            assertRecoveryFailure(recovery(target, MIGRATION_PASSWORD, "restore", "/tmp/late-failure.dump", Map.of()), "does not exist");
            assertThat(snapshot(observer)).isEqualTo(before);
            assertThat(observer.queryForObject("SELECT count(*) FROM pg_class WHERE relnamespace = 'issunexa_managed'::regnamespace", Long.class)).isZero();
        }
    }

    private static byte[] backup(PostgreSQLContainer source) throws Exception {
        assertThat(source.execInContainer("mkdir", "-p", "-m", "700", "/tmp/private-backups").getExitCode()).isZero();
        var result = recovery(source, MIGRATION_PASSWORD, "backup", "/tmp/private-backups", Map.of());
        assertRecoverySuccess(result);
        String archive = result.getStdout().strip();
        assertThat(archive).matches("/tmp/private-backups/issunexa\\.[A-Za-z0-9]+\\.dump");
        assertThat(source.execInContainer("stat", "-c", "%a", archive).getStdout().strip()).isEqualTo("600");
        return source.copyFileFromContainer(archive, InputStream::readAllBytes);
    }

    private static ExecResult recovery(PostgreSQLContainer postgres, String password, String action, String path,
            Map<String, String> overrides) throws Exception {
        var environment = new LinkedHashMap<>(Map.of("PGHOST", "127.0.0.1", "PGDATABASE", postgres.getDatabaseName(),
                "PGUSER", MIGRATOR, "PGPASSWORD", password, "PGSSLMODE", "verify-full",
                "PGSSLROOTCERT", "/tmp/managed-test.crt", "ISSUNEXA_DB_SCHEMA", SCHEMA));
        environment.putAll(overrides);
        var command = new java.util.ArrayList<String>();
        command.add("env");
        environment.forEach((name, value) -> command.add(name + "=" + value));
        command.addAll(List.of("bash", "/backend/database/recovery-managed.sh", action, path));
        return postgres.execInContainer(command.toArray(String[]::new));
    }

    private static void assertRecoverySuccess(ExecResult result) {
        assertThat(result.getStdout() + result.getStderr()).doesNotContain(MIGRATION_PASSWORD, RUNTIME_PASSWORD, ROTATED_MIGRATION, ROTATED_RUNTIME);
        assertThat(result.getExitCode()).withFailMessage("%s", result.getStderr()).isZero();
    }

    private static void assertRecoveryFailure(ExecResult result, String message) {
        assertThat(result.getStdout() + result.getStderr()).doesNotContain(MIGRATION_PASSWORD, RUNTIME_PASSWORD, ROTATED_MIGRATION, ROTATED_RUNTIME);
        assertThat(result.getExitCode()).isNotZero();
        assertThat(result.getStderr()).contains(message);
    }

    private static PostgreSQLContainer postgres() {
        // A generated disposable certificate tests the actual verify-full psql entry point.
        String tls = """
                #!/bin/sh
                set -eu
                openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=localhost \\
                    -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \\
                    -keyout "$PGDATA/server.key" -out "$PGDATA/server.crt" >/dev/null 2>&1
                chmod 600 "$PGDATA/server.key"
                cp "$PGDATA/server.crt" /tmp/managed-test.crt
                printf '\\nssl=on\\n' >> "$PGDATA/postgresql.conf"
                """;
        return new PostgreSQLContainer("postgres:18.6")
                .withCopyToContainer(Transferable.of(tls, 0755), "/docker-entrypoint-initdb.d/managed-tls.sh")
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of("database").toAbsolutePath()), "/backend/database")
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of(
                        "src/main/resources/db/migration/afterMigrate__runtime_privileges.sql").toAbsolutePath()),
                        "/backend/src/main/resources/db/migration/afterMigrate__runtime_privileges.sql");
    }

    private static void prepareOwner(PostgreSQLContainer postgres) {
        JdbcTemplate fixture = jdbc(postgres, postgres.getUsername(), postgres.getPassword());
        fixture.execute("CREATE ROLE " + OWNER + " LOGIN CREATEROLE NOINHERIT PASSWORD '" + OWNER_PASSWORD + "'");
        fixture.execute("ALTER DATABASE " + postgres.getDatabaseName() + " OWNER TO " + OWNER);
    }

    private static ExecResult provision(PostgreSQLContainer postgres, String migrationPassword, String runtimePassword,
            Map<String, String> overrides) throws Exception {
        var environment = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("PGHOST", "127.0.0.1"), Map.entry("PGDATABASE", postgres.getDatabaseName()),
                Map.entry("PGUSER", OWNER), Map.entry("PGPASSWORD", OWNER_PASSWORD),
                Map.entry("PGSSLMODE", "verify-full"), Map.entry("PGSSLROOTCERT", "/tmp/managed-test.crt"),
                Map.entry("ISSUNEXA_DB_SCHEMA", SCHEMA), Map.entry("ISSUNEXA_FLYWAY_USERNAME", MIGRATOR),
                Map.entry("ISSUNEXA_FLYWAY_PASSWORD", migrationPassword), Map.entry("ISSUNEXA_DB_USERNAME", RUNTIME),
                Map.entry("ISSUNEXA_DB_PASSWORD", runtimePassword)));
        environment.putAll(overrides);
        var command = new java.util.ArrayList<String>();
        command.add("env");
        environment.forEach((name, value) -> command.add(name + "=" + value));
        command.addAll(List.of("sh", "/backend/database/provision-managed.sh"));
        return postgres.execInContainer(command.toArray(String[]::new));
    }

    private static void provisionSuccessfully(PostgreSQLContainer postgres, String migrationPassword, String runtimePassword) throws Exception {
        var result = provision(postgres, migrationPassword, runtimePassword, Map.of());
        assertThat(result.getStdout() + result.getStderr()).doesNotContain(migrationPassword, runtimePassword);
        assertThat(result.getExitCode()).withFailMessage("%s", result.getStderr()).isZero();
    }

    private static String url(PostgreSQLContainer postgres) {
        Path certificate = certificates.resolve(postgres.getContainerId() + ".crt");
        try {
            if (!Files.exists(certificate)) {
                Files.write(certificate, postgres.copyFileFromContainer("/tmp/managed-test.crt", InputStream::readAllBytes));
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return postgres.getJdbcUrl() + "&sslmode=verify-full&sslrootcert="
                + URLEncoder.encode(certificate.toString(), StandardCharsets.UTF_8) + "&currentSchema=" + SCHEMA;
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer postgres, String user, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(url(postgres), user, password));
    }

    private static ConfigurableApplicationContext application(PostgreSQLContainer postgres, String migrationPassword, String runtimePassword) {
        return application(postgres, migrationPassword, runtimePassword, MIGRATOR, RUNTIME);
    }

    private static ConfigurableApplicationContext application(PostgreSQLContainer postgres, String migrationPassword,
            String runtimePassword, String migrator, String runtime) {
        return new SpringApplication(IssunexaApplication.class).run("--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + url(postgres), "--spring.datasource.username=" + runtime,
                "--spring.datasource.password=" + runtimePassword, "--spring.flyway.user=" + migrator,
                "--spring.flyway.password=" + migrationPassword);
    }

    private static Map<String, List<Map<String, Object>>> rows(JdbcTemplate jdbc) {
        return Map.of("users", jdbc.queryForList("SELECT * FROM users ORDER BY id"),
                "tickets", jdbc.queryForList("SELECT * FROM tickets ORDER BY 1"),
                "comments", jdbc.queryForList("SELECT * FROM ticket_comments ORDER BY id"),
                "history", jdbc.queryForList("SELECT * FROM ticket_history_entries ORDER BY id"));
    }

    private static Map<String, List<Map<String, Object>>> sequences(JdbcTemplate jdbc) {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("users", "tickets", "ticket_comments", "ticket_history_entries")) {
            result.put(table, jdbc.queryForList("SELECT last_value, is_called FROM " + SCHEMA + "." + table + "_id_seq"));
        }
        return result;
    }

    private static Map<String, Object> snapshot(JdbcTemplate observer) {
        var result = new LinkedHashMap<String, Object>();
        result.put("database", observer.queryForList("SELECT datdba, datacl::text FROM pg_database WHERE datname = current_database()"));
        result.put("schemas", observer.queryForList("SELECT oid, nspname, nspowner, nspacl::text FROM pg_namespace ORDER BY oid"));
        // Compare verifier-bearing role state without printing verifiers on an assertion failure.
        result.put("roles", observer.queryForList("""
                SELECT oid, rolname, md5(row_to_json(r)::text) AS state FROM pg_authid r
                WHERE rolname IN ('managed_owner', 'managed_migrator', 'managed_runtime') ORDER BY oid
                """));
        result.put("memberships", observer.queryForList("SELECT * FROM pg_auth_members ORDER BY roleid, member, grantor"));
        result.put("settings", observer.queryForList("SELECT setdatabase, setrole, setconfig::text FROM pg_db_role_setting ORDER BY setdatabase, setrole"));
        result.put("objects", observer.queryForList("""
                SELECT c.oid, c.relname, c.relowner, c.relacl::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname IN ('public', 'issunexa_managed', 'extensions') ORDER BY c.oid
                """));
        result.put("types", observer.queryForList("SELECT oid, typname, typowner, typacl::text FROM pg_type WHERE typnamespace IN (SELECT oid FROM pg_namespace WHERE nspname IN ('public', 'issunexa_managed', 'extensions')) ORDER BY oid"));
        result.put("columns", observer.queryForList("SELECT attrelid, attnum, attacl::text FROM pg_attribute WHERE attacl IS NOT NULL ORDER BY attrelid, attnum"));
        result.put("functions", observer.queryForList("SELECT oid, proname, proowner, proacl::text FROM pg_proc WHERE pronamespace IN (SELECT oid FROM pg_namespace WHERE nspname IN ('issunexa_managed', 'extensions')) ORDER BY oid"));
        result.put("defaults", observer.queryForList("SELECT oid, defaclrole, defaclnamespace, defaclobjtype, defaclacl::text FROM pg_default_acl ORDER BY oid"));
        if (observer.queryForObject("SELECT to_regclass('issunexa_managed.users') IS NOT NULL", Boolean.class)) {
            result.put("data", rows(observer));
            result.put("sequences", sequences(observer));
            result.put("checksums", observer.queryForList("SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank"));
        }
        return result;
    }

    private static void assertPrivileges(PostgreSQLContainer postgres, JdbcTemplate runtime, String migrationPassword) {
        assertPrivileges(postgres, runtime, migrationPassword, MIGRATOR, RUNTIME);
    }

    private static void assertPrivileges(PostgreSQLContainer postgres, JdbcTemplate runtime, String migrationPassword,
            String migrationRole, String runtimeRole) {
        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo(runtimeRole);
        assertThat(runtime.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        for (String role : List.of(migrationRole, runtimeRole)) {
            assertThat(runtime.queryForMap("SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls, rolinherit FROM pg_roles WHERE rolname = ?", role))
                    .containsOnly(Map.entry("rolsuper", false), Map.entry("rolcreatedb", false), Map.entry("rolcreaterole", false),
                            Map.entry("rolreplication", false), Map.entry("rolbypassrls", false), Map.entry("rolinherit", false));
        }
        assertThat(runtime.queryForObject("SELECT count(*) FROM pg_auth_members WHERE member = (SELECT oid FROM pg_roles WHERE rolname = current_user)", Long.class)).isZero();
        for (String sql : List.of("CREATE ROLE forbidden", "CREATE DATABASE forbidden", "CREATE SCHEMA forbidden",
                "CREATE TABLE public.forbidden (id bigint)", "CREATE TABLE forbidden (id bigint)", "CREATE TEMP TABLE forbidden (id bigint)",
                "ALTER TABLE tickets ADD COLUMN forbidden text", "DROP TABLE ticket_comments", "TRUNCATE tickets CASCADE", "DELETE FROM tickets",
                "UPDATE users SET display_name = 'Changed'", "UPDATE ticket_comments SET body = 'Changed'", "DELETE FROM ticket_history_entries",
                "SELECT * FROM flyway_schema_history", "SET ROLE " + migrationRole, "SET ROLE managed_owner", "SELECT setval('tickets_id_seq', 1)")) {
            assertDenied(runtime, sql);
        }
        JdbcTemplate migrator = jdbc(postgres, migrationRole, migrationPassword);
        for (String sql : List.of("CREATE ROLE forbidden", "CREATE DATABASE forbidden", "CREATE SCHEMA forbidden", "CREATE TABLE public.forbidden (id bigint)")) {
            assertDenied(migrator, sql);
        }
        migrator.execute("CREATE TABLE future_table (id bigint)");
        assertDenied(runtime, "SELECT * FROM future_table");
        migrator.execute("DROP TABLE future_table");
        assertThat(runtime.queryForObject("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = ?", String.class, SCHEMA)).isEqualTo(migrationRole);
        assertThat(runtime.queryForList("SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = ?", String.class, SCHEMA)).containsExactly(migrationRole);
    }

    private static void assertDenied(JdbcTemplate jdbc, String sql) {
        assertThatThrownBy(() -> jdbc.execute(sql)).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
    }
}
