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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class TicketAssignmentMigrationTests {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Test
    void upgradesRealV5RowsWithNullAssigneesAndInitialVersionsWithoutExtraIndexes() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("5").load().migrate();
        Long staffId = jdbc.queryForObject("""
                INSERT INTO users (email, display_name, password_hash, role, created_at, updated_at)
                VALUES ('agent@example.com', 'Agent', 'migration-only-hash', 'AGENT',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class);
        Long historicalId = jdbc.queryForObject("""
                INSERT INTO tickets (title, description, status, priority, created_at, updated_at)
                VALUES ('Historical', 'Unknown requester', 'OPEN', 'HIGH',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class);
        Long ownedId = jdbc.queryForObject("""
                INSERT INTO tickets (title, description, status, priority, requester_id, created_at, updated_at)
                VALUES ('Owned', 'Known requester', 'IN_PROGRESS', 'LOW', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, staffId);
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM tickets ORDER BY id");
        List<String> indexes = jdbc.queryForList("""
                SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' ORDER BY indexname
                """, String.class);
        assertThat(before).allSatisfy(row -> assertThat(row).doesNotContainKeys("assignee_id", "version"));

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("6").load().migrate();

        List<Map<String, Object>> after = jdbc.queryForList("SELECT * FROM tickets ORDER BY id");
        assertThat(after).hasSize(before.size());
        for (int i = 0; i < before.size(); i++) {
            assertThat(after.get(i)).hasSize(before.get(i).size() + 2)
                    .containsAllEntriesOf(before.get(i)).containsEntry("assignee_id", null).containsEntry("version", 0L);
        }
        assertThat(jdbc.queryForList("""
                SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank
                """, String.class)).containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(jdbc.queryForList("""
                SELECT tablename FROM pg_tables WHERE schemaname = 'public'
                """, String.class)).containsExactlyInAnyOrder("flyway_schema_history", "tickets", "users");
        assertThat(jdbc.queryForList("""
                SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' ORDER BY indexname
                """, String.class)).containsExactlyElementsOf(indexes);
        assertThat(column(jdbc, "assignee_id")).containsEntry("data_type", "bigint")
                .containsEntry("is_nullable", "YES").containsEntry("column_default", null);
        assertThat(column(jdbc, "version")).containsEntry("data_type", "bigint")
                .containsEntry("is_nullable", "NO").containsEntry("column_default", null);
        assertThat(jdbc.queryForMap("""
                SELECT kcu.table_name, kcu.column_name, ccu.table_name AS referenced_table,
                       ccu.column_name AS referenced_column, rc.delete_rule
                FROM information_schema.referential_constraints rc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_schema = rc.constraint_schema AND kcu.constraint_name = rc.constraint_name
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_schema = rc.unique_constraint_schema
                 AND ccu.constraint_name = rc.unique_constraint_name
                WHERE rc.constraint_schema = 'public' AND rc.constraint_name = 'fk_tickets_assignee'
                """))
                .containsEntry("table_name", "tickets").containsEntry("column_name", "assignee_id")
                .containsEntry("referenced_table", "users").containsEntry("referenced_column", "id")
                .containsEntry("delete_rule", "NO ACTION");

        assertThat(jdbc.update("UPDATE tickets SET assignee_id = ? WHERE id = ?", staffId, historicalId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT assignee_id FROM tickets WHERE id = ?", Long.class, historicalId))
                .isEqualTo(staffId);
        assertThatThrownBy(() -> jdbc.update("UPDATE tickets SET assignee_id = -1 WHERE id = ?", ownedId))
                .isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23503");
                    assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo("fk_tickets_assignee");
                });
        // Remove the independent requester reference to test assignee deletion protection specifically.
        jdbc.update("UPDATE tickets SET requester_id = NULL WHERE id = ?", ownedId);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", staffId))
                .isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23503");
                    assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo("fk_tickets_assignee");
                });
        assertThatThrownBy(() -> jdbc.update("UPDATE tickets SET version = NULL WHERE id = ?", ownedId))
                .isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, exception ->
                        assertThat(exception.getSQLState()).isEqualTo("23502"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets", Long.class)).isEqualTo(2);
    }

    private Map<String, Object> column(JdbcTemplate jdbc, String name) {
        return jdbc.queryForMap("""
                SELECT data_type, is_nullable, column_default FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'tickets' AND column_name = ?
                """, name);
    }

}
