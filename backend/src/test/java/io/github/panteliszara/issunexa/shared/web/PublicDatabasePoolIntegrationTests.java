package io.github.panteliszara.issunexa.shared.web;

import com.zaxxer.hikari.HikariDataSource;
import io.github.panteliszara.issunexa.user.UserAccountService;
import io.github.panteliszara.issunexa.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "issunexa.public-deployment.origin=https://demo.example.invalid",
        // Exercise real idle retirement sooner; production uses 60 seconds.
        "spring.datasource.hikari.idle-timeout=10000"
})
@ActiveProfiles("public")
@Testcontainers
class PublicDatabasePoolIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private HikariDataSource dataSource;

    @Autowired
    private UserAccountService accounts;

    @Test
    @Timeout(60)
    void retiresAllIdleConnectionsAndReconnectsForApplicationWork() throws Exception {
        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(3);
        assertThat(dataSource.getMinimumIdle()).isZero();
        assertThat(dataSource.getKeepaliveTime()).isZero();
        assertThat(dataSource.getIdleTimeout()).isEqualTo(10000);
        accounts.createUser("idle-pool@example.invalid", "Synthetic pool check", "synthetic test password",
                UserRole.REQUESTER);
        int originalBackend = backendPid();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (dataSource.getHikariPoolMXBean().getTotalConnections() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(Duration.ofMillis(100));
        }
        assertThat(dataSource.getHikariPoolMXBean().getTotalConnections()).isZero();
        assertThat(backendPid()).isNotEqualTo(originalBackend);
        assertThat(accounts.getAuthenticatedAccount("idle-pool@example.invalid").getDisplayName())
                .isEqualTo("Synthetic pool check");
    }

    private int backendPid() throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }
}
