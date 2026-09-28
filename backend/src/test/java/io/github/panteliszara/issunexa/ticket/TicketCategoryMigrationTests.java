package io.github.panteliszara.issunexa.ticket;

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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class TicketCategoryMigrationTests {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Test
    void upgradesV8DataToOtherWithoutDefaultOrUnrelatedSchemaChanges() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("8").load().migrate();
        Long requester = user(jdbc, "requester", "REQUESTER");
        Long agent = user(jdbc, "agent", "AGENT");
        Long owned = jdbc.queryForObject("""
                INSERT INTO tickets (title, description, status, priority, requester_id, assignee_id, version, created_at, updated_at)
                VALUES ('VPN incident', 'Access request', 'IN_PROGRESS', 'HIGH', ?, ?, 3,
                        '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z') RETURNING id
                """, Long.class, requester, agent);
        jdbc.update("""
                INSERT INTO tickets (title, description, status, priority, version, created_at, updated_at)
                VALUES ('Historical', 'Unknown requester', 'CLOSED', 'LOW', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        jdbc.update("""
                INSERT INTO ticket_comments (ticket_id, author_id, body, created_at)
                VALUES (?, ?, 'Existing comment', CURRENT_TIMESTAMP)
                """, owned, agent);
        jdbc.update("""
                INSERT INTO ticket_history_entries (ticket_id, actor_id, type, previous_status, new_status, created_at)
                VALUES (?, ?, 'STATUS_CHANGED', 'OPEN', 'IN_PROGRESS', CURRENT_TIMESTAMP)
                """, owned, agent);
        List<Map<String, Object>> ticketsBefore = rows(jdbc, "tickets");
        Map<String, List<Map<String, Object>>> relatedBefore = Map.of(
                "users", rows(jdbc, "users"), "ticket_comments", rows(jdbc, "ticket_comments"),
                "ticket_history_entries", rows(jdbc, "ticket_history_entries"));
        List<Map<String, Object>> columnsBefore = existingColumns(jdbc);
        List<Map<String, Object>> constraintsBefore = existingConstraints(jdbc);
        List<String> indexesBefore = indexes(jdbc);
        List<String> schemasBefore = schemas(jdbc);

        var result = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("9").load().migrate();

        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
        List<Map<String, Object>> ticketsAfter = rows(jdbc, "tickets");
        ticketsAfter.forEach(row -> assertThat(row.remove("category")).isEqualTo("OTHER"));
        assertThat(ticketsAfter).isEqualTo(ticketsBefore);
        relatedBefore.forEach((table, data) -> assertThat(rows(jdbc, table)).isEqualTo(data));
        assertThat(existingColumns(jdbc)).isEqualTo(columnsBefore);
        assertThat(existingConstraints(jdbc)).isEqualTo(constraintsBefore);
        assertThat(indexes(jdbc)).isEqualTo(indexesBefore);
        assertThat(schemas(jdbc)).isEqualTo(schemasBefore);
        assertThat(jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class))
                .containsExactlyInAnyOrder("flyway_schema_history", "tickets", "users", "ticket_comments", "ticket_history_entries");
        assertThat(jdbc.queryForMap("""
                SELECT data_type, character_maximum_length, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'tickets' AND column_name = 'category'
                """)).containsEntry("data_type", "character varying").containsEntry("character_maximum_length", 20)
                .containsEntry("is_nullable", "NO").containsEntry("column_default", null);
        assertThat(jdbc.queryForList("""
                SELECT conname FROM pg_constraint WHERE conrelid = 'tickets'::regclass
                    AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%category%'
                """, String.class)).containsExactly("ck_tickets_category");
        String definition = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'tickets'::regclass AND conname = 'ck_tickets_category'
                """, String.class);
        assertThat(Pattern.compile("'([^']*)'").matcher(definition).results().map(match -> match.group(1)).toList())
                .containsExactlyInAnyOrder("INCIDENT", "SERVICE_REQUEST", "ACCESS_REQUEST", "OTHER");
        for (String category : List.of("INCIDENT", "SERVICE_REQUEST", "ACCESS_REQUEST", "OTHER")) {
            assertThat(jdbc.update("""
                    INSERT INTO tickets (title, description, status, priority, category, version, created_at, updated_at)
                    VALUES ('New', 'Details', 'OPEN', 'HIGH', ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, category)).isEqualTo(1);
        }
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO tickets (title, description, status, priority, category, version, created_at, updated_at)
                VALUES ('Invalid', 'Details', 'OPEN', 'HIGH', 'UNSUPPORTED', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)).isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23514");
                    assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("ck_tickets_category");
                });
        // Omitting category must fail; the historical fallback is not a creation default.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO tickets (title, description, status, priority, version, created_at, updated_at)
                VALUES ('Missing', 'Details', 'OPEN', 'HIGH', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)).isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23502");
                    assertThat(error.getServerErrorMessage().getColumn()).isEqualTo("category");
                });
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

    private List<Map<String, Object>> existingColumns(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT * FROM information_schema.columns WHERE table_schema = 'public'
                    AND NOT (table_name = 'tickets' AND column_name = 'category')
                ORDER BY table_name, ordinal_position
                """);
    }

    private List<Map<String, Object>> existingConstraints(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT c.conname, pg_get_constraintdef(c.oid) AS definition FROM pg_constraint c
                JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname = 'public'
                    AND NOT (c.conrelid = 'tickets'::regclass AND pg_get_constraintdef(c.oid) LIKE '%category%')
                ORDER BY c.conname
                """);
    }

    private List<String> indexes(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' ORDER BY indexname", String.class);
    }

    private List<String> schemas(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT schema_name FROM information_schema.schemata ORDER BY schema_name", String.class);
    }

}
