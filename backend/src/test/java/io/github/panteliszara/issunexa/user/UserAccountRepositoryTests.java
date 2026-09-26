package io.github.panteliszara.issunexa.user;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@Transactional
class UserAccountRepositoryTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesBothMigrationsAndCreatesOnlyTheExpectedTables() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank
                """, String.class)).containsExactly("1", "2");
        assertThat(jdbcTemplate.queryForList("""
                SELECT tablename FROM pg_tables WHERE schemaname = current_schema()
                """, String.class)).containsExactlyInAnyOrder("flyway_schema_history", "tickets", "users");
    }

    @Test
    void persistsCanonicalUsersWithSaltedHashesGeneratedIdsAndInitialTimestamps() {
        String rawPassword = "  shared integration test password  ";
        Instant beforeCreation = Instant.now().truncatedTo(ChronoUnit.MICROS);

        UserAccount alice = userAccountService.createUser(" \tAlice@Example.COM\n ", " Alice McKay ", rawPassword);
        UserAccount bob = userAccountService.createUser("Bob@Example.COM", "Bob", rawPassword);
        userAccountRepository.flush();
        assertThat(alice.getId()).isPositive();
        assertThat(bob.getId()).isPositive().isNotEqualTo(alice.getId());
        entityManager.clear();

        UserAccount loadedAlice = userAccountRepository.findByEmail("alice@example.com").orElseThrow();
        UserAccount loadedBob = userAccountRepository.findByEmail("bob@example.com").orElseThrow();
        assertThat(loadedAlice.getId()).isEqualTo(alice.getId());
        assertThat(loadedAlice.getEmail()).isEqualTo("alice@example.com");
        assertThat(loadedAlice.getDisplayName()).isEqualTo("Alice McKay");
        assertThat(loadedBob.getId()).isEqualTo(bob.getId());
        assertThat(loadedAlice.getPasswordHash()).isNotEqualTo(loadedBob.getPasswordHash());
        for (UserAccount loaded : List.of(loadedAlice, loadedBob)) {
            assertThat(loaded.getCreatedAt()).isBetween(beforeCreation, Instant.now());
            assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
            String storedHash = jdbcTemplate.queryForObject(
                    "SELECT password_hash FROM users WHERE id = ?", String.class, loaded.getId());
            assertThat(storedHash).isEqualTo(loaded.getPasswordHash()).isNotEqualTo(rawPassword).startsWith("{bcrypt}");
            assertThat(passwordEncoder.matches(rawPassword, storedHash)).isTrue();
            assertThat(passwordEncoder.matches("incorrect password", storedHash)).isFalse();
            assertThat(passwordEncoder.matches(rawPassword.strip(), storedHash)).isFalse();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE password_hash = ?", Long.class, rawPassword)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice@example.com", "  ALICE@Example.COM\t"})
    void rejectsDuplicateCanonicalEmails(String duplicateEmail) {
        userAccountService.createUser("alice@example.com", "Alice", "first test password");
        userAccountRepository.flush();

        assertThatThrownBy(() -> {
            userAccountService.createUser(duplicateEmail, "Another Alice", "second test password");
            userAccountRepository.flush();
        })
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOfSatisfying(PSQLException.class, exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23505");
                    assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo("uk_users_email");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Alice@Example.COM", " alice@example.com ", "\talice@example.com\n"})
    void rejectsNonCanonicalEmailsThroughSql(String email) {
        assertRejectedSqlAccount(email, "Alice", "ck_users_email_normalized");
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void rejectsBlankEmailsThroughSql(String email) {
        assertRejectedSqlAccount(email, "Alice", "ck_users_email_nonblank");
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void rejectsBlankDisplayNamesThroughSql(String displayName) {
        assertRejectedSqlAccount("alice@example.com", displayName, "ck_users_display_name_nonblank");
    }

    private void assertRejectedSqlAccount(String email, String displayName, String constraintName) {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO users (email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, email, displayName, "encoded-test-fixture"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOfSatisfying(PSQLException.class, exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23514");
                    assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo(constraintName);
                });
    }

}
