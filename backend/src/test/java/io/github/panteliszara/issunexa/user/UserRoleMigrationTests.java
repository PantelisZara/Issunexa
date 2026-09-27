package io.github.panteliszara.issunexa.user;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class UserRoleMigrationTests {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Test
    void upgradesAnExistingV2AccountToRequesterAndRequiresExplicitValidRolesAfterward() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("2").load().migrate();
        assertThat(jdbcTemplate.queryForList("""
                SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank
                """, String.class)).containsExactly("1", "2");

        String passwordHash = PasswordEncoderFactories.createDelegatingPasswordEncoder()
                .encode("migration test password");
        jdbcTemplate.update("""
                INSERT INTO users (email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "legacy@example.com", "Legacy account", passwordHash);
        Map<String, Object> original = jdbcTemplate.queryForMap("SELECT * FROM users WHERE email = ?",
                "legacy@example.com");
        assertThat(original).containsOnlyKeys("id", "email", "display_name", "password_hash", "created_at", "updated_at");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("3").load().migrate();

        assertThat(jdbcTemplate.queryForMap("SELECT * FROM users WHERE email = ?", "legacy@example.com"))
                .containsAllEntriesOf(original).containsEntry("role", "REQUESTER");
        assertThat(jdbcTemplate.queryForList("""
                SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank
                """, String.class)).containsExactly("1", "2", "3");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT data_type, character_maximum_length, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'role'
                """))
                .containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 20)
                .containsEntry("is_nullable", "NO")
                .containsEntry("column_default", null);

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE users SET role = 'INVALID' WHERE email = ?",
                "legacy@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOfSatisfying(PSQLException.class, exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23514");
                    assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo("ck_users_role");
                });
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE users SET role = NULL WHERE email = ?",
                "legacy@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOfSatisfying(PSQLException.class, exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23502");
                    assertThat(exception.getServerErrorMessage().getColumn()).isEqualTo("role");
                });
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO users (email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "new@example.com", "New account", passwordHash))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOfSatisfying(PSQLException.class, exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23502");
                    assertThat(exception.getServerErrorMessage().getColumn()).isEqualTo("role");
                });
    }

}
