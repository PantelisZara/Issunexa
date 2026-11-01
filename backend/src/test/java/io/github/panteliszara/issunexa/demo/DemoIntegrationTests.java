package io.github.panteliszara.issunexa.demo;

import com.jayway.jsonpath.JsonPath;
import io.github.panteliszara.issunexa.ticket.TicketService;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountRepository;
import io.github.panteliszara.issunexa.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "issunexa.public-deployment.origin=https://demo.onrender.com",
        "issunexa.public-deployment.ingress=render-free"
})
@ActiveProfiles({"public", "demo"})
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoIntegrationTests {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");
    private static final AtomicInteger SOURCES = new AtomicInteger();
    private static final String TICKET = "{\"title\":\"Synthetic issue\",\"description\":\"Synthetic only\",\"priority\":\"LOW\",\"category\":\"OTHER\"}";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.zaxxer.hikari.HikariDataSource dataSource;
    @Autowired DemoWorkspaceService workspaces;
    @Autowired UserAccountRepository accounts;
    @Autowired TicketService tickets;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clearSyntheticFixtures() {
        jdbc.execute("TRUNCATE ticket_comments, ticket_history_entries, tickets, users, demo_workspaces CASCADE");
    }

    @Test @Order(1)
    void csrfRotationResumeForgedSelectionsPasswordBypassAndLogoutUseRealSessions() throws Exception {
        var metadata = send("GET", "/api/demo", null, null, "", "192.0.2.1");
        assertThat(metadata.statusCode()).isEqualTo(200);
        assertThat(metadata.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(JsonPath.<Boolean>read(metadata.body(), "$.enabled")).isTrue();
        Visitor visitor = anonymous();
        String oldCookie = visitor.cookie;
        String oldToken = visitor.token;
        assertThat(send("POST", "/api/demo/start", oldCookie, null, "{}", visitor.source).statusCode()).isEqualTo(403);
        visitor.post("/api/demo/start", "{}", 204);
        assertThat(visitor.cookie).isNotEqualTo(oldCookie);
        assertThat(send("GET", "/api/auth/session", oldCookie, null, "", visitor.source).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/api/demo/agent", visitor.cookie, oldToken, "{}", visitor.source).statusCode()).isEqualTo(403);
        visitor.refresh();
        String originalExpiry = JsonPath.read(visitor.get("/api/demo", 200).body(), "$.expiresAt");
        assertThat(visitor.token).isNotEqualTo(oldToken);
        for (String body : List.of("{\"workspaceId\":1}", "{\"accountId\":1}", "{\"role\":\"ADMIN\"}", "{\"password\":\"x\"}"))
            visitor.post("/api/demo/agent", body, 400);
        visitor.post("/api/demo/agent?workspace=2", "{}", 400);
        visitor.post("/api/demo/admin", "{}", 403);
        for (int i = 0; i < 6; i++) { visitor.post("/api/demo/start", "{}", 204); visitor.refresh(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_workspaces", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(2);
        assertThat(JsonPath.<String>read(visitor.get("/api/demo", 200).body(), "$.expiresAt")).isEqualTo(originalExpiry);
        String email = jdbc.queryForObject("SELECT email FROM users WHERE role = 'REQUESTER'", String.class);
        jdbc.update("UPDATE users SET password_hash = '{noop}known-synthetic-password' WHERE email = ?", email);
        visitor.post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"known-synthetic-password\"}", 401);
        String requesterCookie = visitor.cookie;
        visitor.post("/api/demo/agent", "{}", 204);
        assertThat(visitor.cookie).isNotEqualTo(requesterCookie);
        assertThat(send("GET", "/api/auth/session", requesterCookie, null, "", visitor.source).statusCode()).isEqualTo(401);
        assertThat(JsonPath.<String>read(visitor.get("/api/auth/session", 200).body(), "$.role")).isEqualTo("AGENT");
        visitor.refresh();
        String authenticated = visitor.cookie;
        visitor.post("/api/auth/logout", "", 204);
        assertThat(send("GET", "/api/auth/session", authenticated, null, "", visitor.source).statusCode()).isEqualTo(401);
    }

    @Test @Order(2)
    void visitorsCannotReadOrMutateAnyOtherWorkspacePathEvenAsAgent() throws Exception {
        Visitor alice = start();
        long aliceTicket = ticketId(alice);
        Visitor bob = start();
        long bobTicket = ticketId(bob);
        for (String rolePath : List.of("/api/demo/requester", "/api/demo/agent")) {
            alice.post(rolePath, "{}", 204); alice.refresh();
            assertThat(alice.get("/api/tickets", 200).body()).doesNotContain("\"id\":" + bobTicket + ",");
            alice.get("/api/tickets/" + bobTicket, 404);
            alice.get("/api/tickets/" + bobTicket + "/comments", 404);
            alice.get("/api/tickets/" + bobTicket + "/history", 404);
            alice.post("/api/tickets/" + bobTicket + "/comments", "{\"body\":\"forbidden\"}", 404);
            if (rolePath.endsWith("agent")) {
                alice.post("/api/tickets/" + bobTicket + "/claim", "", 404);
                assertThat(send("PATCH", "/api/tickets/" + bobTicket + "/status", alice.cookie, alice.token,
                        "{\"status\":\"IN_PROGRESS\"}", alice.source).statusCode()).isEqualTo(404);
                alice.post("/api/tickets/" + aliceTicket + "/claim", "", 200);
                assertThat(send("PATCH", "/api/tickets/" + aliceTicket + "/status", alice.cookie, alice.token,
                        "{\"status\":\"IN_PROGRESS\"}", alice.source).statusCode()).isEqualTo(200);
            }
        }
        bob.get("/api/tickets/" + bobTicket, 200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_comments", Long.class)).isZero();
    }

    @Test @Order(3)
    void concurrentHttpWritesRespectTicketCommentAndHistoryBoundsIncludingSeeds() throws Exception {
        Visitor visitor = start();
        List<Integer> created = concurrent(18, () -> send("POST", "/api/tickets", visitor.cookie, visitor.token, TICKET, visitor.source).statusCode());
        assertThat(created).filteredOn(code -> code == 201).hasSize(7);
        assertThat(created).allMatch(code -> code == 201 || code == 429);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets", Long.class)).isEqualTo(8);
        long ticket = ticketId(visitor);
        List<Integer> commented = concurrent(30, () -> send("POST", "/api/tickets/" + ticket + "/comments", visitor.cookie,
                visitor.token, "{\"body\":\"Synthetic comment\"}", visitor.source).statusCode());
        assertThat(commented).filteredOn(code -> code == 201).hasSize(20);
        assertThat(commented).allMatch(code -> code == 201 || code == 429);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_comments", Long.class)).isEqualTo(20);
        visitor.post("/api/demo/agent", "{}", 204); visitor.refresh();
        jdbc.update("""
                INSERT INTO ticket_history_entries (ticket_id, actor_id, type, new_status, created_at)
                SELECT ?, requester_id, 'TICKET_CREATED', 'OPEN', clock_timestamp() FROM tickets
                CROSS JOIN generate_series(1,119) WHERE id = ?
                """, ticket, ticket);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_history_entries", Long.class)).isEqualTo(127);
        List<Integer> claimed = concurrent(12, () -> send("POST", "/api/tickets/" + ticket + "/claim", visitor.cookie,
                visitor.token, "", visitor.source).statusCode());
        assertThat(claimed).filteredOn(code -> code == 200).hasSize(1);
        assertThat(claimed).allMatch(code -> code == 200 || code == 429);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_history_entries", Long.class)).isEqualTo(128);
        assertThat(send("PATCH", "/api/tickets/" + ticket + "/status", visitor.cookie, visitor.token,
                "{\"status\":\"IN_PROGRESS\"}", visitor.source).statusCode()).isEqualTo(429);
    }

    @Test @Order(4)
    void expiredSessionsAreDeniedWhileRowsRemainAndCleanupIsBoundedAndPreservesOtherData() throws Exception {
        Visitor expired = start();
        long ticket = ticketId(expired);
        long expiredWorkspace = jdbc.queryForObject("SELECT demo_workspace_id FROM users WHERE id = (SELECT requester_id FROM tickets WHERE id = ?)", Long.class, ticket);
        Visitor active = start();
        long activeTicket = ticketId(active);
        jdbc.update("UPDATE demo_workspaces SET created_at = statement_timestamp() - interval '31 minutes', expires_at = statement_timestamp() - interval '1 minute' WHERE id = ?", expiredWorkspace);
        String staleCookie = expired.cookie;
        for (String path : List.of("/api/tickets", "/api/tickets/" + ticket, "/api/tickets/" + ticket + "/comments", "/api/tickets/" + ticket + "/history"))
            assertThat(send("GET", path, staleCookie, null, "", expired.source).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets WHERE id = ?", Long.class, ticket)).isEqualTo(1);
        String expiredEmail = jdbc.queryForObject("SELECT email FROM users WHERE demo_workspace_id = ? AND role = 'REQUESTER'", String.class, expiredWorkspace);
        assertThatThrownBy(() -> tickets.getTicket(ticket, expiredEmail)).isInstanceOf(DemoExpiredException.class);
        seedWorkspaces(7, true);
        long ordinary = jdbc.queryForObject("INSERT INTO users(email,display_name,password_hash,role,created_at,updated_at) VALUES ('ordinary@example.invalid','ordinary','disabled','REQUESTER',now(),now()) RETURNING id", Long.class);
        jdbc.update("INSERT INTO tickets(title,description,status,priority,category,requester_id,version,created_at,updated_at) VALUES ('ordinary','preserved','OPEN','LOW','OTHER',?,0,now(),now())", ordinary);
        assertThat(workspaces.cleanup()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_workspaces WHERE expires_at <= clock_timestamp()", Long.class)).isEqualTo(3);
        assertThat(workspaces.cleanup()).isEqualTo(3);
        assertThat(workspaces.cleanup()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Long.class, ordinary)).isEqualTo(1);
        active.get("/api/tickets/" + activeTicket, 200);
    }

    @Test @Order(5)
    void postgresSerializesConcurrentRetainedAdmissionAtFifty() throws Exception {
        seedWorkspaces(49, false);
        List<Boolean> admitted = concurrent(10, () -> {
            try { workspaces.start(null); return true; }
            catch (DemoUnavailableException expected) { return false; }
        });
        assertThat(admitted).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_workspaces", Long.class)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(100);
    }

    @Test @Order(6)
    void sourceAdmissionUsesVerifiedSourceAndRejectsChangingUntrustedHeaders() throws Exception {
        String source = "198.51.100.244";
        for (int i = 0; i < 5; i++) {
            Visitor visitor = anonymous(source);
            visitor.post("/api/demo/start", "{}", 204);
        }
        Visitor saturated = anonymous(source);
        var denied = send("POST", "/api/demo/start", saturated.cookie, saturated.token, "{}", source,
                Map.of("X-Forwarded-For", "192.0.2.222", "X-Real-IP", "192.0.2.223"));
        assertThat(denied.statusCode()).isEqualTo(429);
        assertThat(denied.body()).doesNotContain(source, "192.0.2.222", "workspace", "password");
        assertThat(denied.headers().firstValue("Retry-After")).contains("60");
    }

    @Test @Order(7)
    void startupRejectsOrdinaryDataAndDemoWithoutPublic() {
        jdbc.update("INSERT INTO users(email,display_name,password_hash,role,created_at,updated_at) VALUES ('startup@example.invalid','ordinary','disabled','REQUESTER',now(),now())");
        assertThatThrownBy(() -> startApplication("public,demo")).hasRootCauseMessage("Demo requires a dedicated, consistent synthetic database.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'startup@example.invalid'", Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> startApplication("demo")).hasRootCauseMessage("Demo requires the public profile and a dedicated synthetic database.");
    }

    private void startApplication(String profiles) {
        try (var ignored = new org.springframework.boot.SpringApplication(io.github.panteliszara.issunexa.IssunexaApplication.class)
                .run("--spring.profiles.active=" + profiles, "--server.port=0", "--server.address=127.0.0.1",
                        "--issunexa.public-deployment.origin=https://demo.onrender.com", "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(), "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.flyway.user=" + postgres.getUsername(), "--spring.flyway.password=" + postgres.getPassword())) { }
    }

    @Test @Order(8)
    void parallelStartOnTheSameAnonymousSessionCannotAllocateMultipleWorkspaces() throws Exception {
        Visitor visitor = anonymous();
        List<Integer> started = concurrent(12, () -> send("POST", "/api/demo/start", visitor.cookie, visitor.token, "{}", visitor.source).statusCode());
        assertThat(started).anyMatch(code -> code == 204).allMatch(code -> code == 204 || code == 403 || code == 401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_workspaces", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(2);
    }

    @Test @Order(9)
    void boundDemoSessionDoesNotMakeLivenessOrReadinessDependOnDemoQueries() throws Exception {
        Visitor visitor = start();
        try (var observer = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = observer.createStatement()) {
            statement.execute("ALTER ROLE " + postgres.getUsername() + " NOLOGIN");
            dataSource.getHikariPoolMXBean().softEvictConnections();
            try {
                assertThat(dataSource.getHikariPoolMXBean().getTotalConnections()).isZero();
                var live = visitor.get("/api/health/live", 200);
                assertThat(live.body()).isEqualTo("{\"status\":\"UP\"}");
                assertThat(dataSource.getHikariPoolMXBean().getTotalConnections()).isZero();
                assertThat(visitor.get("/api/health/ready", 503).body()).isEqualTo("{\"status\":\"DOWN\"}");
                assertThat(visitor.get("/api/health/live", 200).body()).isEqualTo("{\"status\":\"UP\"}");
            } finally {
                statement.execute("ALTER ROLE " + postgres.getUsername() + " LOGIN");
            }
        }
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        HttpResponse<String> recovered;
        do {
            recovered = send("GET", "/api/health/ready", visitor.cookie, null, "", visitor.source);
            if (recovered.statusCode() == 200) break;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        assertThat(recovered.statusCode()).isEqualTo(200);
    }

    @Test @Order(Integer.MAX_VALUE)
    void anonymousCsrfCannotAllocateMoreThanOneHundredSessionsAndMetadataNeedsNone() throws Exception {
        int successful = 0;
        int denied = 0;
        List<Integer> statuses = concurrent(120, () -> send("GET", "/api/auth/csrf", null, null, "", "192.0.2.253").statusCode());
        for (int status : statuses) {
            if (status == 200) successful++;
            else { assertThat(status).isEqualTo(429); denied++; }
        }
        assertThat(successful).isLessThanOrEqualTo(100);
        assertThat(denied).isPositive();
        var response = send("GET", "/api/auth/csrf", null, null, "", "192.0.2.254");
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).doesNotContain("Exception", "maxActive", "100", "sessionId");
        var info = send("GET", "/api/demo", null, null, "", "192.0.2.254");
        assertThat(info.statusCode()).isEqualTo(200);
        assertThat(info.headers().allValues("Set-Cookie")).isEmpty();
    }

    private void seedWorkspaces(int count, boolean expired) {
        for (int i = 0; i < count; i++) {
            long id = jdbc.queryForObject("INSERT INTO demo_workspaces(created_at,expires_at) VALUES (statement_timestamp() - interval '30 minutes', statement_timestamp() "
                    + (expired ? "- interval '1 minute'" : "+ interval '0 seconds'") + ") RETURNING id", Long.class);
            if (!expired) jdbc.update("UPDATE demo_workspaces SET created_at = clock_timestamp(), expires_at = clock_timestamp() + interval '29 minutes' WHERE id = ?", id);
            for (String role : List.of("REQUESTER", "AGENT"))
                jdbc.update("INSERT INTO users(email,display_name,password_hash,role,demo_workspace_id,created_at,updated_at) VALUES (?, 'Synthetic fixture', 'disabled', ?, ?,now(),now())", "fixture-" + id + "-" + role.toLowerCase(java.util.Locale.ROOT) + "@example.invalid", role, id);
        }
    }

    private static <T> List<T> concurrent(int count, Callable<T> action) throws Exception {
        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<T>> calls = new ArrayList<>();
            for (int i = 0; i < count; i++) calls.add(action);
            List<T> result = new ArrayList<>();
            for (var future : executor.invokeAll(calls)) result.add(future.get());
            return result;
        }
    }

    private Visitor start() throws Exception { Visitor v = anonymous(); v.post("/api/demo/start", "{}", 204); v.refresh(); return v; }
    private Visitor anonymous() throws Exception { return anonymous("203.0.113." + SOURCES.incrementAndGet()); }
    private Visitor anonymous(String source) throws Exception {
        Visitor v = new Visitor(source); v.refresh(); return v;
    }
    private long ticketId(Visitor visitor) throws Exception {
        Number number = JsonPath.read(visitor.get("/api/tickets", 200).body(), "$.content[0].id");
        return number.longValue();
    }
    private HttpResponse<String> send(String method, String path, String cookie, String token, String body, String source) throws Exception {
        return send(method, path, cookie, token, body, source, Map.of());
    }
    private HttpResponse<String> send(String method, String path, String cookie, String token, String body, String source, Map<String,String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("CF-Connecting-IP", source)
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) builder.header("Cookie", cookie);
        if (token != null) builder.header("X-CSRF-TOKEN", token);
        if (!body.isEmpty()) builder.header("Content-Type", "application/json");
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private final class Visitor {
        String cookie;
        String token;
        final String source;
        Visitor(String source) { this.source = source; }
        HttpResponse<String> get(String path, int status) throws Exception {
            var result = send("GET", path, cookie, null, "", source);
            assertThat(result.statusCode()).as(path + ": " + result.body()).isEqualTo(status);
            remember(result); return result;
        }
        void refresh() throws Exception { var r = get("/api/auth/csrf", 200); token = JsonPath.read(r.body(), "$.token"); }
        void post(String path, String body, int status) throws Exception {
            var result = send("POST", path, cookie, token, body, source);
            assertThat(result.statusCode()).as(path + ": " + result.body()).isEqualTo(status); remember(result);
        }
        void remember(HttpResponse<String> response) {
            response.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("JSESSIONID="))
                    .findFirst().ifPresent(c -> {
                        assertThat(c).contains("; Secure", "; HttpOnly", "; SameSite=Lax", "; Path=/");
                        cookie = c.split(";", 2)[0];
                    });
        }
    }
}
