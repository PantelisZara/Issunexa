package io.github.panteliszara.issunexa.ticket.comment;

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
class TicketCommentMigrationTests {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Test
    void upgradesV6WithoutChangingExistingRowsOrSchemaAndAddsOnlyCommentTableAndIndexes() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("6").load().migrate();
        Long requester = jdbc.queryForObject("""
                INSERT INTO users (email, display_name, password_hash, role, created_at, updated_at)
                VALUES ('requester@example.com', 'Requester', 'migration-only-hash', 'REQUESTER',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class);
        Long agent = jdbc.queryForObject("""
                INSERT INTO users (email, display_name, password_hash, role, created_at, updated_at)
                VALUES ('agent@example.com', 'Agent', 'migration-only-hash', 'AGENT',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class);
        Long owned = jdbc.queryForObject("""
                INSERT INTO tickets (title, description, status, priority, requester_id, assignee_id,
                                     version, created_at, updated_at)
                VALUES ('Assigned ticket', 'Existing details', 'IN_PROGRESS', 'HIGH', ?, ?, 3,
                        '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z') RETURNING id
                """, Long.class, requester, agent);
        jdbc.update("""
                INSERT INTO tickets (title, description, status, priority, version, created_at, updated_at)
                VALUES ('Historical', 'Unknown requester', 'CLOSED', 'LOW', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        List<Map<String, Object>> ticketsBefore = jdbc.queryForList("SELECT * FROM tickets ORDER BY id");
        List<Map<String, Object>> usersBefore = jdbc.queryForList("SELECT * FROM users ORDER BY id");
        List<Map<String, Object>> columnsBefore = existingColumns(jdbc);
        List<Map<String, Object>> constraintsBefore = existingConstraints(jdbc);
        List<Map<String, Object>> indexesBefore = existingIndexes(jdbc);
        List<String> schemasBefore = jdbc.queryForList("SELECT schema_name FROM information_schema.schemata ORDER BY schema_name", String.class);
        assertThat(jdbc.queryForList("""
                SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank
                """, String.class)).containsExactly("1", "2", "3", "4", "5", "6");

        var result = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("7").load().migrate();

        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM tickets ORDER BY id")).isEqualTo(ticketsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(usersBefore);
        assertThat(existingColumns(jdbc)).isEqualTo(columnsBefore);
        assertThat(existingConstraints(jdbc)).isEqualTo(constraintsBefore);
        assertThat(existingIndexes(jdbc)).isEqualTo(indexesBefore);
        assertThat(jdbc.queryForList("SELECT schema_name FROM information_schema.schemata ORDER BY schema_name", String.class))
                .isEqualTo(schemasBefore);
        assertThat(jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class))
                .containsExactlyInAnyOrder("flyway_schema_history", "tickets", "users", "ticket_comments");
        assertThat(jdbc.queryForList("""
                SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank
                """, String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7");

        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable, is_identity, identity_generation, character_maximum_length
                FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'ticket_comments'
                ORDER BY ordinal_position
                """);
        assertThat(columns).extracting(column -> column.get("column_name"))
                .containsExactly("id", "ticket_id", "author_id", "body", "created_at");
        assertThat(columns).allSatisfy(column -> assertThat(column).containsEntry("is_nullable", "NO"));
        assertThat(columns.get(0)).containsEntry("data_type", "bigint")
                .containsEntry("is_identity", "YES").containsEntry("identity_generation", "ALWAYS");
        assertThat(columns.get(1)).containsEntry("data_type", "bigint");
        assertThat(columns.get(2)).containsEntry("data_type", "bigint");
        assertThat(columns.get(3)).containsEntry("data_type", "character varying").containsEntry("character_maximum_length", 4000);
        assertThat(columns.get(4)).containsEntry("data_type", "timestamp with time zone");
        assertForeignKey(jdbc, "fk_ticket_comments_ticket", "ticket_id", "tickets");
        assertForeignKey(jdbc, "fk_ticket_comments_author", "author_id", "users");
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'ticket_comments'
                """, String.class)).containsExactlyInAnyOrder("pk_ticket_comments", "idx_ticket_comments_ticket_created_id");
        assertThat(jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_ticket_comments_ticket_created_id'
                """, String.class)).isEqualTo("CREATE INDEX idx_ticket_comments_ticket_created_id ON public.ticket_comments USING btree (ticket_id, created_at, id)");
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'ticket_comments'::regclass AND conname = 'ck_ticket_comments_body_nonblank'
                """, String.class)).contains("CHECK", "btrim", "body", "> 0");
        Long commentId = jdbc.queryForObject("""
                INSERT INTO ticket_comments (ticket_id, author_id, body, created_at)
                VALUES (?, ?, 'First comment', CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, owned, agent);
        assertThat(commentId).isPositive();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ticket_comments (ticket_id, author_id, body, created_at)
                VALUES (?, ?, '   ', CURRENT_TIMESTAMP)
                """, owned, agent))
                .isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23514");
                    assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("ck_ticket_comments_body_nonblank");
                });
        assertThat(jdbc.queryForList("SELECT * FROM tickets ORDER BY id")).isEqualTo(ticketsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(usersBefore);
    }

    private List<Map<String, Object>> existingColumns(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT * FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name <> 'ticket_comments' ORDER BY table_name, ordinal_position
                """);
    }

    private List<Map<String, Object>> existingConstraints(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT c.conname, pg_get_constraintdef(c.oid) AS definition FROM pg_constraint c
                JOIN pg_namespace n ON n.oid = c.connamespace
                WHERE n.nspname = 'public' AND c.conrelid <> COALESCE(to_regclass('public.ticket_comments'), 0)
                ORDER BY c.conname
                """);
    }

    private List<Map<String, Object>> existingIndexes(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT indexname, indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename <> 'ticket_comments' ORDER BY indexname
                """);
    }

    private void assertForeignKey(JdbcTemplate jdbc, String constraint, String column, String referencedTable) {
        assertThat(jdbc.queryForMap("""
                SELECT kcu.table_name, kcu.column_name, ccu.table_name AS referenced_table,
                       ccu.column_name AS referenced_column, rc.delete_rule
                FROM information_schema.referential_constraints rc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_schema = rc.constraint_schema AND kcu.constraint_name = rc.constraint_name
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_schema = rc.unique_constraint_schema AND ccu.constraint_name = rc.unique_constraint_name
                WHERE rc.constraint_schema = 'public' AND rc.constraint_name = ?
                """, constraint)).containsEntry("table_name", "ticket_comments").containsEntry("column_name", column)
                .containsEntry("referenced_table", referencedTable).containsEntry("referenced_column", "id")
                .containsEntry("delete_rule", "NO ACTION");
    }

}
