package io.github.panteliszara.issunexa.shared.web;

import com.jayway.jsonpath.JsonPath;
import io.github.panteliszara.issunexa.auth.LoginSourceResolver;
import io.github.panteliszara.issunexa.user.UserAccountService;
import io.github.panteliszara.issunexa.user.UserRole;
import jakarta.servlet.Filter;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "issunexa.public-deployment.origin=https://demo.onrender.com",
        "issunexa.public-deployment.ingress=render-free"
})
@ActiveProfiles("public")
@Import(PublicDeploymentIntegrationTests.ObservationConfiguration.class)
@Testcontainers
class PublicDeploymentIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @LocalServerPort
    private int port;

    @Autowired
    private UserAccountService accounts;

    @Autowired
    private AtomicReference<RequestMetadata> observed;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void actualCookiesAndAuthenticationKeepCsrfRotationAndLogoutRevocation() throws Exception {
        accounts.createUser("public-session@example.invalid", "Synthetic requester", "synthetic test password",
                UserRole.REQUESTER);
        var bootstrap = get("/api/auth/csrf", null, Map.of());
        assertThat(bootstrap.statusCode()).isEqualTo(200);
        String originalCookie = sessionCookie(bootstrap);
        String csrfHeader = JsonPath.read(bootstrap.body(), "$.headerName");
        String originalToken = JsonPath.read(bootstrap.body(), "$.token");
        assertThat(bootstrap.headers().firstValue("Strict-Transport-Security")).isPresent();
        assertThat(bootstrap.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(bootstrap.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");

        String credentials = """
                {"email":"public-session@example.invalid","password":"synthetic test password"}
                """;
        assertThat(post("/api/auth/login", originalCookie, Map.of(), credentials).statusCode()).isEqualTo(403);
        var login = post("/api/auth/login", originalCookie, Map.of(csrfHeader, originalToken), credentials);
        assertThat(login.statusCode()).isEqualTo(204);
        assertThat(login.headers().firstValue("Location")).isEmpty();
        String authenticatedCookie = sessionCookie(login);
        assertThat(authenticatedCookie).isNotEqualTo(originalCookie);
        assertThat(get("/api/auth/session", originalCookie, Map.of()).statusCode()).isEqualTo(401);
        assertThat(get("/api/auth/session", authenticatedCookie, Map.of()).statusCode()).isEqualTo(200);
        String sessionId = authenticatedCookie.substring("JSESSIONID=".length());
        assertThat(get("/api/auth/session;jsessionid=" + sessionId, null, Map.of()).statusCode()).isEqualTo(400);
        assertThat(post("/api/auth/logout", authenticatedCookie, Map.of(csrfHeader, originalToken), "")
                .statusCode()).isEqualTo(403);

        var fresh = get("/api/auth/csrf", authenticatedCookie, Map.of());
        String freshToken = JsonPath.read(fresh.body(), "$.token");
        assertThat(freshToken).isNotEqualTo(originalToken);
        assertThat(post("/api/auth/logout", authenticatedCookie, Map.of(csrfHeader, freshToken), "")
                .statusCode()).isEqualTo(204);
        assertThat(get("/api/auth/session", authenticatedCookie, Map.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void hostileHostAndForwardingHeadersCannotChangeExternalMetadataOrSocketPeer() throws Exception {
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(("""
                    GET /api/auth/csrf HTTP/1.1\r
                    Host: attacker.example\r
                    Forwarded: for=192.0.2.90;proto=http;host=attacker.example\r
                    X-Forwarded-For: 192.0.2.91\r
                    X-Forwarded-Host: attacker.example\r
                    X-Forwarded-Proto: http\r
                    X-Real-IP: 192.0.2.92\r
                    CF-Connecting-IP: 203.0.113.10\r
                    Connection: close\r
                    \r
                    """).getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(response).startsWith("HTTP/1.1 200");
        }
        var metadata = observed.get();
        assertThat(metadata.scheme()).isEqualTo("https");
        assertThat(metadata.secure()).isTrue();
        assertThat(metadata.host()).isEqualTo("demo.onrender.com");
        assertThat(metadata.port()).isEqualTo(443);
        assertThat(InetAddress.getByName(metadata.peer()).isLoopbackAddress()).isTrue();
        assertThat(metadata.loginSource()).isEqualTo("203.0.113.10");
        assertThat(metadata.trackingModes()).containsExactly(SessionTrackingMode.COOKIE);
    }

    @Test
    void renderSourceBudgetsCannotBeBypassedWithOtherForwardingHeaders() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(post("/api/auth/login", null, Map.of("CF-Connecting-IP", "203.0.113.80",
                    "X-Forwarded-For", "192.0.2." + i), "{}").statusCode()).isEqualTo(403);
        }
        var denied = post("/api/auth/login", null, Map.of("CF-Connecting-IP", "203.0.113.80",
                "X-Forwarded-For", "198.51.100.90", "X-Real-IP", "198.51.100.91"), "{}");
        assertThat(denied.statusCode()).isEqualTo(429);
        assertThat(Integer.parseInt(denied.headers().firstValue("Retry-After").orElseThrow())).isPositive();
        assertThat(denied.body()).doesNotContain("203.0.113.80", "198.51.100.90");
        assertThat(post("/api/auth/login", null, Map.of("CF-Connecting-IP", "203.0.113.81"), "{}")
                .statusCode()).isEqualTo(403);
    }

    @Test
    void publicProfileDisablesExplorersAndDoesNotExposeManagement() throws Exception {
        assertThat(get("/v3/api-docs", null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(get("/swagger-ui/index.html", null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(get("/actuator/health", null, Map.of()).statusCode()).isEqualTo(401);
        var denied = get("/api/auth/session", null, Map.of("X-Forwarded-Host", "attacker.example"));
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(denied.headers().firstValue("Location")).isEmpty();
    }

    private String sessionCookie(HttpResponse<String> response) {
        String value = response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertThat(value).contains("; Secure", "; HttpOnly", "; SameSite=Lax", "; Path=/");
        assertThat(value.toLowerCase()).doesNotContain("domain=");
        return value.split(";", 2)[0];
    }

    private HttpResponse<String> get(String path, String cookie, Map<String, String> headers) throws Exception {
        return request("GET", path, cookie, headers, "");
    }

    private HttpResponse<String> post(String path, String cookie, Map<String, String> headers, String body)
            throws Exception {
        return request("POST", path, cookie, headers, body);
    }

    private HttpResponse<String> request(String method, String path, String cookie, Map<String, String> headers,
            String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) request.header("Cookie", cookie);
        if (method.equals("POST")) request.header("Content-Type", "application/json");
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    record RequestMetadata(String scheme, boolean secure, String host, int port, String peer,
            String loginSource, Set<SessionTrackingMode> trackingModes) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ObservationConfiguration {

        @Bean
        AtomicReference<RequestMetadata> observedMetadata() {
            return new AtomicReference<>();
        }

        @Bean
        FilterRegistrationBean<Filter> requestObserver(AtomicReference<RequestMetadata> observed,
                LoginSourceResolver resolver) {
            var registration = new FilterRegistrationBean<Filter>();
            registration.setFilter((request, response, chain) -> {
                var http = (HttpServletRequest) request;
                observed.set(new RequestMetadata(http.getScheme(), http.isSecure(), http.getServerName(),
                        http.getServerPort(), http.getRemoteAddr(), resolver.resolve(http),
                        http.getServletContext().getEffectiveSessionTrackingModes()));
                chain.doFilter(request, response);
            });
            return registration;
        }
    }
}
