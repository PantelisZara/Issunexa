package io.github.panteliszara.issunexa.shared.database;

import io.github.panteliszara.issunexa.IssunexaApplication;
import io.github.panteliszara.issunexa.ticket.TicketCategory;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketRepository;
import io.github.panteliszara.issunexa.ticket.TicketService;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import io.github.panteliszara.issunexa.user.UserAccountService;
import io.github.panteliszara.issunexa.user.UserRole;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabasePrivilegesIntegrationTests {

    // Synthetic credentials for disposable test containers only.
    private static final String MIGRATION = "issunexa_migrator";
    private static final String RUNTIME = "issunexa_runtime";
    private static final String MIGRATION_PASSWORD = "disposable-migration-password";
    private static final String RUNTIME_PASSWORD = "disposable-runtime-password";

    @Test
    void freshBootstrapMigrationsAndHibernateUseSeparateRestrictedConnections() throws Exception {
        try (var postgres = postgres()) {
            postgres.start();
            try (var connection = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).getConnection();
                    var statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE internal_schema_check (value text)");
                List<String> schemas = jdbc(postgres, postgres.getUsername(), postgres.getPassword())
                        .queryForList("SELECT nspname FROM pg_namespace", String.class);
                assertThat(schemas).anyMatch(name -> name.startsWith("pg_temp_"))
                        .anyMatch(name -> name.startsWith("pg_toast_temp_"));
                bootstrap(postgres);
            }
            try (var context = startApplication(postgres)) {
                JdbcTemplate runtime = context.getBean(JdbcTemplate.class);
                assertPrivilegeModel(postgres, runtime);
                assertThat(new JdbcTemplate(context.getBean(Flyway.class).getConfiguration().getDataSource())
                        .queryForObject("SELECT current_user", String.class)).isEqualTo(MIGRATION);
                assertThat(context.getBean(EntityManagerFactory.class).getProperties())
                        .containsEntry("hibernate.hbm2ddl.auto", "validate");
                var account = context.getBean(UserAccountService.class)
                        .createUser("runtime@example.test", "Runtime", "disposable-account-password", UserRole.REQUESTER);
                TicketService tickets = context.getBean(TicketService.class);
                var ticket = tickets.createTicket("Restricted connection", "Identity insert", TicketPriority.HIGH,
                        TicketCategory.INCIDENT, account.getEmail());
                assertThat(tickets.getTicket(ticket.getId(), account.getEmail()).getTitle()).isEqualTo("Restricted connection");
                ticket.changeStatus(TicketStatus.IN_PROGRESS);
                context.getBean(TicketRepository.class).saveAndFlush(ticket);
                assertThat(runtime.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, ticket.getId()))
                        .isEqualTo("IN_PROGRESS");
                runtime.update("INSERT INTO ticket_comments (ticket_id, author_id, body, created_at) VALUES (?, ?, 'Comment', now())",
                        ticket.getId(), account.getId());
                assertThat(runtime.queryForObject("SELECT count(*) FROM ticket_history_entries", Long.class)).isEqualTo(1);
                assertThat(runtime.queryForObject("SELECT count(*) FROM ticket_comments", Long.class)).isEqualTo(1);
                assertThat(runtime.queryForObject("SELECT nextval('tickets_id_seq')", Long.class)).isGreaterThan(ticket.getId());
                JdbcTemplate migration = jdbc(postgres, MIGRATION, MIGRATION_PASSWORD);
                migration.execute("CREATE TABLE public.future_table (id bigint)");
                assertDenied(runtime, "SELECT * FROM future_table");
                migration.execute("DROP TABLE public.future_table");
                assertDenied(runtime, "DELETE FROM tickets");
                assertDenied(runtime, "UPDATE users SET display_name = 'Changed'");
                assertDenied(runtime, "UPDATE ticket_comments SET body = 'Changed'");
                assertDenied(runtime, "DELETE FROM ticket_history_entries");
            }
            // Reprovisioning keeps existing runtime access even without an application restart.
            bootstrap(postgres);
            assertPrivilegeModel(postgres, jdbc(postgres, RUNTIME, RUNTIME_PASSWORD));
            assertThat(jdbc(postgres, RUNTIME, RUNTIME_PASSWORD).queryForObject("SELECT count(*) FROM tickets", Long.class))
                    .isEqualTo(1);
            try (var context = startApplication(postgres)) {
                assertThat(context.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM tickets", Long.class))
                        .isEqualTo(1);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"8", "9"})
    void existingRowsSurviveRepeatableOwnershipUpgradeAndMigrationValidation(String version) throws Exception {
        try (var postgres = postgres()) {
            postgres.start();
            JdbcTemplate admin = jdbc(postgres, postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .target(version).load().migrate();
            admin.update("""
                    INSERT INTO users (email, display_name, password_hash, role, created_at, updated_at)
                    VALUES ('existing@example.test', 'Existing', 'disposable-hash', 'REQUESTER', now(), now())
                    """);
            if (version.equals("8")) {
                admin.update("""
                        INSERT INTO tickets (title, description, status, priority, requester_id, version, created_at, updated_at)
                        VALUES ('Preserved', 'Existing data', 'OPEN', 'LOW', 1, 0, now(), now())
                        """);
            } else {
                admin.update("""
                        INSERT INTO tickets (title, description, status, priority, category, requester_id, version, created_at, updated_at)
                        VALUES ('Preserved', 'Existing data', 'OPEN', 'LOW', 'OTHER', 1, 0, now(), now())
                        """);
            }
            admin.update("INSERT INTO ticket_comments (ticket_id, author_id, body, created_at) VALUES (1, 1, 'Preserved comment', now())");
            admin.update("""
                    INSERT INTO ticket_history_entries (ticket_id, actor_id, type, new_status, created_at)
                    VALUES (1, 1, 'TICKET_CREATED', 'OPEN', now())
                    """);
            Map<String, List<Map<String, Object>>> before = snapshot(admin);
            List<Map<String, Object>> checksums = admin.queryForList(
                    "SELECT version, checksum FROM flyway_schema_history ORDER BY installed_rank");
            bootstrap(postgres);
            bootstrap(postgres);
            assertPrivilegeModel(postgres, jdbc(postgres, RUNTIME, RUNTIME_PASSWORD));
            try (var context = startApplication(postgres)) {
                JdbcTemplate runtime = context.getBean(JdbcTemplate.class);
                context.getBean(Flyway.class).validate();
                var after = snapshot(runtime);
                if (version.equals("8")) {
                    after.get("tickets").forEach(row -> assertThat(row.remove("category")).isEqualTo("OTHER"));
                }
                assertThat(after).isEqualTo(before);
                assertThat(admin.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE version::integer <= ? ORDER BY installed_rank",
                        Integer.parseInt(version)))
                        .isEqualTo(checksums);
                assertThat(admin.queryForList("SELECT installed_by FROM flyway_schema_history WHERE version = '9'", String.class))
                        .containsExactly(version.equals("8") ? MIGRATION : postgres.getUsername());
                assertPrivilegeModel(postgres, runtime);
                var account = context.getBean(UserAccountService.class)
                        .createUser("next@example.test", "Next", "disposable-account-password", UserRole.REQUESTER);
                assertThat(account.getId()).isGreaterThan(1);
                assertThat(context.getBean(TicketService.class).createTicket("Next", "New row", TicketPriority.LOW,
                        TicketCategory.OTHER, account.getEmail()).getId()).isGreaterThan(1);
            }
        }
    }

    @Test
    void unexpectedRoleCollisionFailsWithoutChangingRoles() throws Exception {
        try (var postgres = postgres()) {
            postgres.start();
            JdbcTemplate admin = jdbc(postgres, postgres.getUsername(), postgres.getPassword());
            admin.execute("CREATE ROLE issunexa_runtime LOGIN SUPERUSER");
            assertThat(provision(postgres).getExitCode()).isNotZero();
            assertThat(admin.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = 'issunexa_runtime'", Boolean.class)).isTrue();
            assertThat(admin.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = 'issunexa_migrator'", Long.class)).isZero();
            admin.execute("ALTER ROLE issunexa_runtime NOSUPERUSER");
            assertThat(provision(postgres).getExitCode()).isNotZero();
            assertThat(admin.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = 'issunexa_migrator'", Long.class)).isZero();
        }
    }

    @Test
    void unexpectedSharedSchemaFailsBeforeChangingItsDataOrOwner() throws Exception {
        try (var postgres = postgres()) {
            postgres.start();
            JdbcTemplate admin = jdbc(postgres, postgres.getUsername(), postgres.getPassword());
            admin.execute("CREATE TABLE unrelated (value text)");
            admin.update("INSERT INTO unrelated VALUES ('preserved')");
            String owner = admin.queryForObject("SELECT tableowner FROM pg_tables WHERE tablename = 'unrelated'", String.class);
            assertThat(provision(postgres).getExitCode()).isNotZero();
            assertThat(admin.queryForList("SELECT value FROM unrelated", String.class)).containsExactly("preserved");
            assertThat(admin.queryForObject("SELECT tableowner FROM pg_tables WHERE tablename = 'unrelated'", String.class)).isEqualTo(owner);
            assertThat(admin.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname IN ('issunexa_runtime', 'issunexa_migrator')", Long.class)).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false, pgshared", "false, true, unrelated_app", "true, true, unrelated_app"})
    void rejectsNonPublicUserSchemaWithoutChangingPrivilegesOrRoles(
            boolean existingRoles, boolean existingTable, String schema) throws Exception {
        try (var postgres = postgres()) {
            postgres.start();
            JdbcTemplate admin = jdbc(postgres, postgres.getUsername(), postgres.getPassword());
            if (existingRoles) {
                bootstrap(postgres);
            }
            admin.execute("CREATE SCHEMA " + schema);
            if (existingTable) {
                admin.execute("CREATE TABLE " + schema + ".unrelated (value text)");
                admin.update("INSERT INTO " + schema + ".unrelated VALUES ('preserved')");
            }
            // Existing dedicated provisioning revokes this privilege; explicitly restore it
            // to model the PUBLIC temporary-table capability of a shared installation.
            admin.execute("GRANT TEMPORARY ON DATABASE " + postgres.getDatabaseName() + " TO PUBLIC");
            assertThat(publicHasTemporaryPrivilege(admin)).isTrue();
            Map<String, Object> databaseBefore = admin.queryForMap("""
                    SELECT datdba, datacl::text FROM pg_database WHERE datname = current_database()
                    """);
            List<Map<String, Object>> namespacesBefore = admin.queryForList("""
                    SELECT oid, nspname, nspowner, nspacl::text FROM pg_namespace
                    WHERE nspname IN ('public', ?) ORDER BY nspname
                    """, schema);
            List<Map<String, Object>> rolesBefore = admin.queryForList("""
                    SELECT * FROM pg_authid WHERE rolname IN ('issunexa_migrator', 'issunexa_runtime') ORDER BY rolname
                    """);
            List<Map<String, Object>> roleSettingsBefore = admin.queryForList("""
                    SELECT setdatabase, setrole, setconfig::text FROM pg_db_role_setting
                    WHERE setrole IN (SELECT oid FROM pg_roles WHERE rolname IN ('issunexa_migrator', 'issunexa_runtime'))
                    ORDER BY setdatabase, setrole
                    """);
            List<Map<String, Object>> relationsBefore = admin.queryForList("""
                    SELECT oid, relname, relowner, relacl::text FROM pg_class
                    WHERE relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = ?) ORDER BY oid
                    """, schema);
            assertThat(rolesBefore).hasSize(existingRoles ? 2 : 0);

            var result = provision(postgres);

            assertThat(result.getExitCode()).isNotZero();
            assertThat(result.getStderr()).contains("Unexpected user-defined schema outside public");
            assertThat(admin.queryForMap("SELECT datdba, datacl::text FROM pg_database WHERE datname = current_database()"))
                    .isEqualTo(databaseBefore);
            assertThat(publicHasTemporaryPrivilege(admin)).isTrue();
            assertThat(admin.queryForList("""
                    SELECT oid, nspname, nspowner, nspacl::text FROM pg_namespace
                    WHERE nspname IN ('public', ?) ORDER BY nspname
                    """, schema)).isEqualTo(namespacesBefore);
            // pg_authid includes password verifiers as well as role identity/attributes.
            assertThat(admin.queryForList("""
                    SELECT * FROM pg_authid WHERE rolname IN ('issunexa_migrator', 'issunexa_runtime') ORDER BY rolname
                    """)).isEqualTo(rolesBefore);
            assertThat(admin.queryForList("""
                    SELECT setdatabase, setrole, setconfig::text FROM pg_db_role_setting
                    WHERE setrole IN (SELECT oid FROM pg_roles WHERE rolname IN ('issunexa_migrator', 'issunexa_runtime'))
                    ORDER BY setdatabase, setrole
                    """)).isEqualTo(roleSettingsBefore);
            assertThat(admin.queryForList("""
                    SELECT oid, relname, relowner, relacl::text FROM pg_class
                    WHERE relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = ?) ORDER BY oid
                    """, schema)).isEqualTo(relationsBefore);
            if (existingTable) {
                assertThat(admin.queryForList("SELECT value FROM " + schema + ".unrelated", String.class))
                        .containsExactly("preserved");
            }
        }
    }

    private static boolean publicHasTemporaryPrivilege(JdbcTemplate admin) {
        // Inspect PUBLIC's ACL entry directly: the administrator's effective privilege
        // would remain true even if PUBLIC access were incorrectly revoked.
        return Boolean.TRUE.equals(admin.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_database d, LATERAL aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) a
                    WHERE d.datname = current_database() AND a.grantee = 0 AND a.privilege_type = 'TEMPORARY')
                """, Boolean.class));
    }

    private static PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18.6")
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of("database").toAbsolutePath()), "/backend/database")
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of(
                        "src/main/resources/db/migration/afterMigrate__runtime_privileges.sql").toAbsolutePath()),
                        "/backend/src/main/resources/db/migration/afterMigrate__runtime_privileges.sql");
    }

    private static org.testcontainers.containers.Container.ExecResult provision(PostgreSQLContainer postgres) throws Exception {
        return postgres.execInContainer("env", "PGHOST=127.0.0.1", "PGDATABASE=" + postgres.getDatabaseName(),
                "PGUSER=" + postgres.getUsername(), "PGPASSWORD=" + postgres.getPassword(),
                "ISSUNEXA_FLYWAY_USERNAME=" + MIGRATION, "ISSUNEXA_FLYWAY_PASSWORD=" + MIGRATION_PASSWORD,
                "ISSUNEXA_DB_USERNAME=" + RUNTIME, "ISSUNEXA_DB_PASSWORD=" + RUNTIME_PASSWORD,
                "sh", "/backend/database/bootstrap.sh");
    }

    private static void bootstrap(PostgreSQLContainer postgres) throws Exception {
        var result = provision(postgres);
        assertThat(result.getStdout() + result.getStderr()).doesNotContain(MIGRATION_PASSWORD, RUNTIME_PASSWORD);
        assertThat(result.getExitCode()).withFailMessage("%s", result.getStderr()).isZero();
    }

    private static ConfigurableApplicationContext startApplication(PostgreSQLContainer postgres) {
        var application = new SpringApplication(IssunexaApplication.class);
        return application.run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + RUNTIME, "--spring.datasource.password=" + RUNTIME_PASSWORD,
                "--spring.flyway.user=" + MIGRATION, "--spring.flyway.password=" + MIGRATION_PASSWORD);
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer postgres, String username, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), username, password));
    }

    private static Map<String, List<Map<String, Object>>> snapshot(JdbcTemplate jdbc) {
        return Map.of("users", jdbc.queryForList("SELECT * FROM users ORDER BY id"),
                "tickets", jdbc.queryForList("SELECT * FROM tickets ORDER BY id"),
                "ticket_comments", jdbc.queryForList("SELECT * FROM ticket_comments ORDER BY id"),
                "ticket_history_entries", jdbc.queryForList("SELECT * FROM ticket_history_entries ORDER BY id"));
    }

    private static void assertPrivilegeModel(PostgreSQLContainer postgres, JdbcTemplate runtime) {
        assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo(RUNTIME);
        for (String role : List.of(RUNTIME, MIGRATION)) {
            assertThat(runtime.queryForMap("SELECT rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls FROM pg_roles WHERE rolname = ?", role))
                    .containsOnly(Map.entry("rolsuper", false), Map.entry("rolcreatedb", false),
                            Map.entry("rolcreaterole", false), Map.entry("rolreplication", false), Map.entry("rolbypassrls", false));
        }
        JdbcTemplate admin = jdbc(postgres, postgres.getUsername(), postgres.getPassword());
        assertThat(admin.queryForList("SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'public'", String.class))
                .containsExactly(MIGRATION);
        assertThat(admin.queryForList("""
                SELECT DISTINCT pg_get_userbyid(c.relowner) FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind = 'S'
                """, String.class)).containsExactly(MIGRATION);
        assertThat(runtime.queryForObject("SELECT count(*) FROM pg_auth_members WHERE member = (SELECT oid FROM pg_roles WHERE rolname = current_user)", Long.class)).isZero();
        for (String sql : List.of("CREATE ROLE forbidden_role", "CREATE DATABASE forbidden_database",
                "CREATE SCHEMA forbidden_schema", "CREATE TABLE public.forbidden_table (id bigint)",
                "CREATE TEMP TABLE forbidden_temp (id bigint)", "ALTER TABLE tickets ADD COLUMN forbidden text",
                "TRUNCATE tickets CASCADE", "SELECT * FROM flyway_schema_history", "SET ROLE issunexa_migrator",
                "SELECT setval('tickets_id_seq', 1)")) {
            assertDenied(runtime, sql);
        }
        assertDenied(jdbc(postgres, MIGRATION, MIGRATION_PASSWORD), "CREATE ROLE forbidden_migration_role");
        assertDenied(jdbc(postgres, MIGRATION, MIGRATION_PASSWORD), "CREATE DATABASE forbidden_migration_database");
    }

    private static void assertDenied(JdbcTemplate jdbc, String sql) {
        assertThatThrownBy(() -> jdbc.execute(sql)).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
    }
}
