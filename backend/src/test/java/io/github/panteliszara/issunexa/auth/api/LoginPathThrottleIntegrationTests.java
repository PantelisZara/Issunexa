package io.github.panteliszara.issunexa.auth.api;

import com.jayway.jsonpath.JsonPath;
import io.github.panteliszara.issunexa.user.UserAccountService;
import io.github.panteliszara.issunexa.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.method.HandlerMethod;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(LoginPathThrottleIntegrationTests.MatrixParameterTestConfiguration.class)
@Testcontainers
@Transactional
class LoginPathThrottleIntegrationTests {

    private static final String EMAIL = "path-login@example.test";
    private static final String PASSWORD = "synthetic path integration password";
    private static final AtomicLong WINDOWS = new AtomicLong();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountService accounts;

    @MockitoBean(name = "loginThrottleClock")
    private Clock clock;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void prepareAccountAndFreshWindow() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-05T10:00:00Z")
                .plusSeconds(WINDOWS.incrementAndGet() * 600));
        accounts.createUser(EMAIL, "Path login", PASSWORD, UserRole.REQUESTER);
        clearInvocations(passwordEncoder);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/auth/login;attempt=%d", "/api;attempt=%d/auth/login", "/api/auth;attempt=%d/login",
            "/api;attempt=%d/auth;mode=test/login;other=value", "/api/auth/login;jsessionid=%d",
            "/api/auth/%%6cogin?attempt=%d"
    })
    void alternateMvcLoginPathsShareOneSourceBudgetAndCannotKeepReachingBcrypt(String pathTemplate) throws Exception {
        CsrfState csrf = csrf(null);
        String originalSessionId = csrf.session().getId();
        for (int i = 0; i < 30; i++) {
            String path = pathTemplate.formatted(i);
            // Distinct identifiers prevent the account budget from masking a source-matching regression.
            MvcResult result = mockMvc.perform(login(path, csrf, "missing-" + i + "@example.test"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Invalid email or password.")).andReturn();
            assertThat(result.getHandler()).isInstanceOfSatisfying(HandlerMethod.class, handler -> {
                assertThat(handler.getBeanType()).isEqualTo(AuthenticationController.class);
                assertThat(handler.getMethod().getName()).isEqualTo("login");
            });
        }
        for (int i = 30; i < 65; i++) {
            mockMvc.perform(login(pathTemplate.formatted(i), csrf, EMAIL))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.instance").value("/api/auth/login"));
        }
        // Source enforcement still precedes CSRF and malformed-body parsing on the alternate spelling.
        mockMvc.perform(post(URI.create(pathTemplate.formatted(66))).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(login("/api/auth/login", csrf, EMAIL)).andExpect(status().isTooManyRequests());
        verify(passwordEncoder, times(30)).matches(any(CharSequence.class), anyString());
        assertThat(csrf.session().getId()).isEqualTo(originalSessionId);
        mockMvc.perform(get("/api/auth/session").session(csrf.session())).andExpect(status().isUnauthorized());
        csrf(csrf.session());

        // An independent source can still log in normally with real BCrypt and session-ID rotation.
        mockMvc.perform(login("/api/auth/login", csrf, EMAIL)
                .with(request -> { request.setRemoteAddr("192.0.2.2"); return request; }))
                .andExpect(status().isNoContent());
        assertThat(csrf.session().getId()).isNotEqualTo(originalSessionId);
        mockMvc.perform(get("/api/auth/session").session(csrf.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(EMAIL));
        verify(passwordEncoder, times(31)).matches(any(CharSequence.class), anyString());
    }

    @Test
    void unrelatedPathsAndMethodsDoNotConsumeTheLoginSourceBudget() throws Exception {
        CsrfState csrf = csrf(null);
        for (int i = 0; i < 35; i++) {
            mockMvc.perform(get("/api/auth/csrf").session(csrf.session())).andExpect(status().isOk());
            mockMvc.perform(get("/api/auth/session").session(csrf.session())).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/auth/login-extra").session(csrf.session())
                    .header(csrf.headerName(), csrf.token())).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/auth/login;attempt=" + i).session(csrf.session()))
                    .andExpect(status().isUnauthorized());
        }
        verify(passwordEncoder, times(0)).matches(any(CharSequence.class), anyString());
        mockMvc.perform(login("/api/auth/login", csrf, EMAIL)).andExpect(status().isNoContent());
        verify(passwordEncoder, times(1)).matches(any(CharSequence.class), anyString());
    }

    private MockHttpServletRequestBuilder login(String path, CsrfState csrf, String email) {
        return post(URI.create(path)).session(csrf.session()).header(csrf.headerName(), csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
    }

    private CsrfState csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return new CsrfState((MockHttpSession) result.getRequest().getSession(false),
                JsonPath.read(result.getResponse().getContentAsString(), "$.headerName"),
                JsonPath.read(result.getResponse().getContentAsString(), "$.token"));
    }

    private record CsrfState(MockHttpSession session, String headerName, String token) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MatrixParameterTestConfiguration {
        @Bean
        HttpFirewall matrixParameterFirewall() {
            // Only this test permits matrix parameters, exercising the real MVC/filter mapping
            // beyond the production firewall's rejection. Production StrictHttpFirewall is unchanged.
            StrictHttpFirewall firewall = new StrictHttpFirewall();
            firewall.setAllowSemicolon(true);
            return firewall;
        }
    }
}
