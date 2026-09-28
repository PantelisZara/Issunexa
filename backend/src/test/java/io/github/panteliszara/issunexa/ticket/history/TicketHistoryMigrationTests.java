package io.github.panteliszara.issunexa.ticket.history;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class TicketHistoryMigrationTests {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Test
    void upgradesRealV7DataWithoutBackfillingHistoryOrChangingExistingSchema() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("7").load().migrate();
        Long requester = user(jdbc, "requester", "REQUESTER");
        Long staff = user(jdbc, "staff", "AGENT");
        Long ticket = jdbc.queryForObject("""
                INSERT INTO tickets (title, description, status, priority, requester_id, assignee_id, version, created_at, updated_at)
                VALUES ('Existing', 'Existing details', 'IN_PROGRESS', 'HIGH', ?, ?, 3,
                        '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z') RETURNING id
                """, Long.class, requester, staff);
        jdbc.update("""
                INSERT INTO tickets (title, description, status, priority, version, created_at, updated_at)
                VALUES ('Historical', 'No requester', 'CLOSED', 'LOW', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        jdbc.update("""
                INSERT INTO ticket_comments (ticket_id, author_id, body, created_at)
                VALUES (?, ?, 'Existing comment', '2026-01-02T12:00:00Z')
                """, ticket, staff);
        Map<String, List<Map<String, Object>>> before = Map.of(
                "users", rows(jdbc, "users"), "tickets", rows(jdbc, "tickets"), "ticket_comments", rows(jdbc, "ticket_comments"));
        List<Map<String, Object>> columnsBefore = existingColumns(jdbc);
        List<Map<String, Object>> constraintsBefore = existingConstraints(jdbc);
        List<String> indexesBefore = existingIndexes(jdbc);
        List<String> schemasBefore = schemas(jdbc);
        assertThat(versions(jdbc)).containsExactly("1", "2", "3", "4", "5", "6", "7");

        var result = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("8").load().migrate();

        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(versions(jdbc)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        before.forEach((table, data) -> assertThat(rows(jdbc, table)).isEqualTo(data));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_history_entries", Long.class)).isZero();
        assertThat(existingColumns(jdbc)).isEqualTo(columnsBefore);
        assertThat(existingConstraints(jdbc)).isEqualTo(constraintsBefore);
        assertThat(existingIndexes(jdbc)).isEqualTo(indexesBefore);
        assertThat(schemas(jdbc)).isEqualTo(schemasBefore);
        assertThat(jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class))
                .containsExactlyInAnyOrder("flyway_schema_history", "tickets", "users", "ticket_comments", "ticket_history_entries");

        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable, is_identity, identity_generation, character_maximum_length
                FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'ticket_history_entries'
                ORDER BY ordinal_position
                """);
        assertThat(columns).extracting(row -> row.get("column_name"))
                .containsExactly("id", "ticket_id", "actor_id", "type", "previous_status", "new_status", "assignee_id", "created_at");
        assertThat(columns).extracting(row -> row.get("is_nullable"))
                .containsExactly("NO", "NO", "NO", "NO", "YES", "YES", "YES", "NO");
        assertThat(columns.get(0)).containsEntry("data_type", "bigint")
                .containsEntry("is_identity", "YES").containsEntry("identity_generation", "ALWAYS");
        for (int position : List.of(1, 2, 6)) {
            assertThat(columns.get(position)).containsEntry("data_type", "bigint");
        }
        for (int position : List.of(3, 4, 5)) {
            assertThat(columns.get(position)).containsEntry("data_type", "character varying").containsEntry("character_maximum_length", 20);
        }
        assertThat(columns.get(7)).containsEntry("data_type", "timestamp with time zone");
        foreignKey(jdbc, "ticket", "tickets");
        foreignKey(jdbc, "actor", "users");
        foreignKey(jdbc, "assignee", "users");
        assertThat(jdbc.queryForList("""
                SELECT conname FROM pg_constraint WHERE conrelid = 'ticket_history_entries'::regclass AND contype = 'c'
                """, String.class)).containsExactlyInAnyOrder("ck_ticket_history_type", "ck_ticket_history_previous_status",
                        "ck_ticket_history_new_status", "ck_ticket_history_event_shape");
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'ticket_history_entries'
                """, String.class)).containsExactlyInAnyOrder("pk_ticket_history_entries", "idx_ticket_history_ticket_created_id");
        assertThat(jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_ticket_history_ticket_created_id'
                """, String.class)).isEqualTo("CREATE INDEX idx_ticket_history_ticket_created_id ON public.ticket_history_entries USING btree (ticket_id, created_at DESC, id DESC)");
        // A NULL new status must fail, rather than pass a CHECK via SQL's UNKNOWN result.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ticket_history_entries (ticket_id, actor_id, type, created_at)
                VALUES (?, ?, 'TICKET_CREATED', CURRENT_TIMESTAMP)
                """, ticket, requester)).isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23514");
                    assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("ck_ticket_history_event_shape");
                });
        assertThat(jdbc.queryForObject("""
                INSERT INTO ticket_history_entries (ticket_id, actor_id, type, previous_status, new_status, created_at)
                VALUES (?, ?, 'STATUS_CHANGED', 'IN_PROGRESS', 'RESOLVED', CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, ticket, staff)).isPositive();
        before.forEach((table, data) -> assertThat(rows(jdbc, table)).isEqualTo(data));
    }

    private Long user(JdbcTemplate jdbc, String name, String role) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, password_hash, role, created_at, updated_at)
                VALUES (?, ?, 'migration-only-hash', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, name + "@example.com", name, role);
    }

    private List<Map<String, Object>> rows(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id");
    }

    private List<String> versions(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
    }

    private List<String> schemas(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT schema_name FROM information_schema.schemata ORDER BY schema_name", String.class);
    }

    private List<Map<String, Object>> existingColumns(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT * FROM information_schema.columns WHERE table_schema = 'public' AND table_name <> 'ticket_history_entries'
                ORDER BY table_name, ordinal_position
                """);
    }

    private List<Map<String, Object>> existingConstraints(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT c.conname, pg_get_constraintdef(c.oid) AS definition FROM pg_constraint c
                JOIN pg_namespace n ON n.oid = c.connamespace
                WHERE n.nspname = 'public' AND c.conrelid <> COALESCE(to_regclass('public.ticket_history_entries'), 0)
                ORDER BY c.conname
                """);
    }

    private List<String> existingIndexes(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename <> 'ticket_history_entries' ORDER BY indexname
                """, String.class);
    }

    private void foreignKey(JdbcTemplate jdbc, String name, String table) {
        assertThat(jdbc.queryForMap("""
                SELECT kcu.table_name, kcu.column_name, ccu.table_name AS referenced_table, ccu.column_name AS referenced_column, rc.delete_rule
                FROM information_schema.referential_constraints rc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_schema = rc.constraint_schema AND kcu.constraint_name = rc.constraint_name
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_schema = rc.unique_constraint_schema AND ccu.constraint_name = rc.unique_constraint_name
                WHERE rc.constraint_schema = 'public' AND rc.constraint_name = ?
                """, "fk_ticket_history_" + name)).containsEntry("table_name", "ticket_history_entries")
                .containsEntry("column_name", name + "_id").containsEntry("referenced_table", table)
                .containsEntry("referenced_column", "id").containsEntry("delete_rule", "NO ACTION");
    }

}
