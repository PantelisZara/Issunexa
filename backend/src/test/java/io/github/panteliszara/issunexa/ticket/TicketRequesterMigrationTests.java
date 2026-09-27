package io.github.panteliszara.issunexa.ticket;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class TicketRequesterMigrationTests {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Test
    void preservesHistoricalTicketsWithoutOwnershipAndConstrainsNewRequesterReferences() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("3").load().migrate();
        assertThat(jdbcTemplate.queryForList("""
                SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank
                """, String.class)).containsExactly("1", "2", "3");

        String passwordHash = PasswordEncoderFactories.createDelegatingPasswordEncoder()
                .encode("requester migration test password");
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, display_name, password_hash, created_at, updated_at, role)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'REQUESTER') RETURNING id
                """, Long.class, "existing@example.com", "Existing user", passwordHash);
        Long historicalId = jdbcTemplate.queryForObject("""
                INSERT INTO tickets (title, description, status, priority, created_at, updated_at)
                VALUES ('Historical ticket', 'Unknown requester', 'OPEN', 'HIGH',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class);
        Map<String, Object> original = jdbcTemplate.queryForMap("SELECT * FROM tickets WHERE id = ?", historicalId);
        assertThat(original).containsOnlyKeys("id", "title", "description", "status", "priority",
                "created_at", "updated_at");
        List<String> originalIndexes = jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'tickets'
                ORDER BY indexname
                """, String.class);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("4").load().migrate();

        assertThat(jdbcTemplate.queryForMap("SELECT * FROM tickets WHERE id = ?", historicalId))
                .containsAllEntriesOf(original).containsEntry("requester_id", null);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
                SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank
                """, String.class)).containsExactly("1", "2", "3", "4");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT data_type, is_nullable, column_default FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'tickets' AND column_name = 'requester_id'
                """))
                .containsEntry("data_type", "bigint")
                .containsEntry("is_nullable", "YES")
                .containsEntry("column_default", null);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT kcu.table_name, kcu.column_name, ccu.table_name AS referenced_table,
                       ccu.column_name AS referenced_column, rc.delete_rule
                FROM information_schema.referential_constraints rc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_schema = rc.constraint_schema AND kcu.constraint_name = rc.constraint_name
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_schema = rc.unique_constraint_schema
                 AND ccu.constraint_name = rc.unique_constraint_name
                WHERE rc.constraint_schema = 'public' AND rc.constraint_name = 'fk_tickets_requester'
                """))
                .containsEntry("table_name", "tickets")
                .containsEntry("column_name", "requester_id")
                .containsEntry("referenced_table", "users")
                .containsEntry("referenced_column", "id")
                .containsEntry("delete_rule", "NO ACTION");
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'tickets'
                ORDER BY indexname
                """, String.class)).containsExactlyElementsOf(originalIndexes);

        Long ownedId = jdbcTemplate.queryForObject("""
                INSERT INTO tickets (title, description, status, priority, created_at, updated_at, requester_id)
                VALUES ('Owned ticket', 'Known requester', 'OPEN', 'HIGH',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?) RETURNING id
                """, Long.class, userId);
        assertThat(jdbcTemplate.queryForObject("SELECT requester_id FROM tickets WHERE id = ?", Long.class, ownedId))
                .isEqualTo(userId);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO tickets (title, description, status, priority, created_at, updated_at, requester_id)
                VALUES ('Invalid owner', 'Missing user', 'OPEN', 'HIGH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, -1)
                """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOfSatisfying(PSQLException.class, exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23503");
                    assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo("fk_tickets_requester");
                });
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOfSatisfying(PSQLException.class, exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23503");
                    assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo("fk_tickets_requester");
                });
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tickets", Long.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT requester_id FROM tickets WHERE id = ?",
                Long.class, historicalId)).isNull();
    }

}
