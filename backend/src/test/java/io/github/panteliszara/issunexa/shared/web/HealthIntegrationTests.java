package io.github.panteliszara.issunexa.shared.web;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "issunexa.public-deployment.origin=https://demo.example.invalid"
})
@ActiveProfiles("public")
@Testcontainers
class HealthIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @LocalServerPort
    private int port;

    @Autowired
    private HikariDataSource dataSource;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    @Timeout(60)
    void readinessReportsActualDatabaseFailureAndRecoveryWhileLivenessDoesNotConnect() throws Exception {
        assertThat(dataSource.getConnectionTimeout()).isEqualTo(3000);
        assertStatus(get("ready"), 200, "UP");
        // Keep the observer connection open while new application logins are denied.
        try (var observer = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = observer.createStatement()) {
            statement.execute("ALTER ROLE " + postgres.getUsername() + " NOLOGIN");
            dataSource.getHikariPoolMXBean().softEvictConnections();
            try {
                assertThat(dataSource.getHikariPoolMXBean().getTotalConnections()).isZero();
                assertStatus(get("live"), 200, "UP");
                assertThat(dataSource.getHikariPoolMXBean().getTotalConnections()).isZero();
                long started = System.nanoTime();
                assertStatus(get("ready"), 503, "DOWN");
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
                assertStatus(get("live"), 200, "UP");
            } finally {
                statement.execute("ALTER ROLE " + postgres.getUsername() + " LOGIN");
            }
            HttpResponse<String> recovered = get("ready");
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (recovered.statusCode() != 200 && System.nanoTime() < deadline) {
                Thread.sleep(Duration.ofMillis(100));
                recovered = get("ready");
            }
            assertStatus(recovered, 200, "UP");
        }
    }

    @Test
    void onlyTheTwoGetProbesAreAnonymous() throws Exception {
        for (String probe : List.of("live", "ready")) {
            assertStatus(get(probe), 200, "UP");
            for (String method : List.of("HEAD", "OPTIONS")) {
                var response = client.send(HttpRequest.newBuilder(uri(probe)).timeout(Duration.ofSeconds(10))
                        .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(401);
            }
            for (String method : List.of("POST", "PUT", "DELETE")) {
                var response = client.send(HttpRequest.newBuilder(uri(probe)).timeout(Duration.ofSeconds(10))
                        .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(403);
            }
        }
        assertThat(get("configuration").statusCode()).isEqualTo(401);
    }

    private void assertStatus(HttpResponse<String> response, int code, String status) {
        assertThat(response.statusCode()).isEqualTo(code);
        assertThat(response.body()).isEqualTo("{\"status\":\"" + status + "\"}");
        assertThat(response.headers().firstValue("content-type").orElseThrow()).contains("application/json");
        assertThat(response.headers().firstValue("cache-control").orElseThrow()).contains("no-store");
        assertThat(response.headers().allValues("set-cookie")).isEmpty();
    }

    private HttpResponse<String> get(String probe) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(probe)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String probe) {
        return URI.create("http://localhost:" + port + "/api/health/" + probe);
    }
}
