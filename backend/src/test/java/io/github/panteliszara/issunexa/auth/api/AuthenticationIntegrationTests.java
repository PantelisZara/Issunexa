package io.github.panteliszara.issunexa.auth.api;

import com.jayway.jsonpath.JsonPath;
import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketRepository;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountRepository;
import io.github.panteliszara.issunexa.user.UserAccountService;
import io.github.panteliszara.issunexa.user.UserRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Testcontainers
@Transactional
class AuthenticationIntegrationTests {

    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "  correct integration password  ";
    private static final String TICKET_JSON = """
            {"title":"Printer offline","description":"The printer is unreachable.","priority":"HIGH"}
            """;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void createAccount() {
        userAccountService.createUser(EMAIL, "Alice", PASSWORD, UserRole.REQUESTER);
    }

    @Test
    void exposesOnlyCsrfTokenAndHeaderNameWithoutCaching() throws Exception {
        CsrfState csrf = csrf(null);

        assertThat(csrf.session()).isNotNull();
        assertThat(csrf.token()).isNotBlank().isNotEqualTo(csrf.session().getId());
        assertThat(csrf.headerName()).isEqualTo("X-CSRF-TOKEN");
        assertThat(csrf.session().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
    }

    @Test
    void keepsOpenApiAndSwaggerPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger UI")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/tickets", "/api/tickets/42"})
    void rejectsAnonymousTicketReadsWithoutCreatingASavedRequestSession(String path) throws Exception {
        MvcResult result = assertUnauthenticated(mockMvc.perform(get(path))).andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @ParameterizedTest
    @MethodSource("ticketMutations")
    void rejectsAnonymousTicketMutationsEvenWithValidCsrf(HttpMethod method, String path, String body)
            throws Exception {
        CsrfState csrf = csrf(null);

        assertUnauthenticated(mockMvc.perform(request(method, path).session(csrf.session())
                .header(csrf.headerName(), csrf.token()).contentType(MediaType.APPLICATION_JSON).content(body)));
    }

    static Stream<Arguments> ticketMutations() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/tickets", TICKET_JSON),
                Arguments.of(HttpMethod.PATCH, "/api/tickets/42/status", "{\"status\":\"IN_PROGRESS\"}")
        );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "invalid-token")
    void rejectsLoginWithMissingOrInvalidCsrfWithoutAuthenticating(String suppliedToken) throws Exception {
        CsrfState csrf = csrf(null);
        String originalSessionId = csrf.session().getId();
        MockHttpServletRequestBuilder login = loginRequest(csrf.session(), EMAIL, PASSWORD);
        if (suppliedToken != null) {
            login.header(csrf.headerName(), suppliedToken);
        }

        assertForbidden(mockMvc.perform(login));

        assertThat(csrf.session().getId()).isEqualTo(originalSessionId);
        assertThat(csrf.session().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
        assertUnauthenticated(mockMvc.perform(get("/api/tickets").session(csrf.session())));
    }

    @Test
    void givesTheSameGenericFailureForUnknownEmailAndWrongPassword() throws Exception {
        CsrfState csrf = csrf(null);
        MvcResult wrongPassword = assertProblem(mockMvc.perform(loginRequest(csrf.session(), EMAIL, "incorrect")
                        .header(csrf.headerName(), csrf.token())),
                401, "Authentication failed", "Invalid email or password.")
                .andReturn();
        MvcResult unknownAccount = assertProblem(mockMvc.perform(
                        loginRequest(csrf.session(), "missing@example.com", PASSWORD)
                                .header(csrf.headerName(), csrf.token())),
                401, "Authentication failed", "Invalid email or password.")
                .andReturn();

        assertThat(wrongPassword.getResponse().getContentAsString())
                .isEqualTo(unknownAccount.getResponse().getContentAsString());
        assertThat(csrf.session().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
        assertUnauthenticated(mockMvc.perform(get("/api/tickets").session(csrf.session())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice@example.com", "  Alice@Example.COM  "})
    void authenticatesCanonicalEmailRotatesSessionIdAndPersistsContext(String email) throws Exception {
        CsrfState anonymous = csrf(null);
        String anonymousSessionId = anonymous.session().getId();

        MockHttpSession authenticated = login(anonymous, email);

        assertThat(authenticated.getId()).isNotEqualTo(anonymousSessionId);
        SecurityContext context = (SecurityContext) authenticated
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().isAuthenticated()).isTrue();
        assertThat(context.getAuthentication().getName()).isEqualTo(EMAIL);
        assertThat(context.getAuthentication().getCredentials()).isNull();
        UserDetails principal = (UserDetails) context.getAuthentication().getPrincipal();
        assertThat(principal.getPassword()).isNull();
        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_REQUESTER");
        assertThat(userAccountRepository.count()).isEqualTo(1);
        mockMvc.perform(get("/api/tickets").session(authenticated))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @ParameterizedTest
    @CsvSource({"REQUESTER, ROLE_REQUESTER", "AGENT, ROLE_AGENT", "ADMIN, ROLE_ADMIN"})
    void exposesThePersistedRoleAfterRealLoginAndAllowsAllTicketOperations(
            UserRole role, String expectedAuthority) throws Exception {
        String email = "role@example.com";
        userAccountService.createUser(email, "Role account", PASSWORD, role);

        MockHttpSession session = login(csrf(null), email);

        SecurityContext context = (SecurityContext) session
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        UserDetails principal = (UserDetails) context.getAuthentication().getPrincipal();
        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly(expectedAuthority);
        assertThat(context.getAuthentication().getAuthorities())
                .filteredOn(authority -> authority.getAuthority().startsWith("ROLE_"))
                .extracting(GrantedAuthority::getAuthority).containsExactly(expectedAuthority);

        mockMvc.perform(get("/api/tickets").session(session)).andExpect(status().isOk());
        CsrfState fresh = csrf(session);
        MvcResult created = mockMvc.perform(post("/api/tickets").session(session)
                        .header(fresh.headerName(), fresh.token())
                        .contentType(MediaType.APPLICATION_JSON).content(TICKET_JSON))
                .andExpect(status().isCreated()).andReturn();
        Number ticketId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        mockMvc.perform(get("/api/tickets/" + ticketId).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(patch("/api/tickets/" + ticketId + "/status").session(session)
                        .header(fresh.headerName(), fresh.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void invalidatesPreLoginCsrfAndRequiresFreshTokensForTicketCreationAndStatusChanges() throws Exception {
        CsrfState anonymous = csrf(null);
        MockHttpSession session = login(anonymous, EMAIL);

        assertForbidden(mockMvc.perform(post("/api/tickets").session(session)
                .contentType(MediaType.APPLICATION_JSON).content(TICKET_JSON)));
        assertForbidden(mockMvc.perform(post("/api/tickets").session(session)
                .header(anonymous.headerName(), anonymous.token())
                .contentType(MediaType.APPLICATION_JSON).content(TICKET_JSON)));

        CsrfState fresh = csrf(session);
        MvcResult created = mockMvc.perform(post("/api/tickets").session(session)
                        .header(fresh.headerName(), fresh.token())
                        .contentType(MediaType.APPLICATION_JSON).content(TICKET_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();
        Number ticketId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        String statusPath = "/api/tickets/" + ticketId + "/status";
        String newStatus = "{\"status\":\"IN_PROGRESS\"}";
        assertForbidden(mockMvc.perform(patch(statusPath).session(session)
                .contentType(MediaType.APPLICATION_JSON).content(newStatus)));
        assertForbidden(mockMvc.perform(patch(statusPath).session(session)
                .header(anonymous.headerName(), anonymous.token())
                .contentType(MediaType.APPLICATION_JSON).content(newStatus)));
        mockMvc.perform(patch(statusPath).session(session).header(fresh.headerName(), fresh.token())
                        .contentType(MediaType.APPLICATION_JSON).content(newStatus))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mockMvc.perform(get("/api/tickets/" + ticketId).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mockMvc.perform(get("/api/tickets").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void persistsAuthenticatedRequesterDespiteClientSuppliedIdentityWithoutExposingAccountData() throws Exception {
        Long authenticatedId = userAccountRepository.findByEmail(EMAIL).orElseThrow().getId();
        UserAccount other = userAccountService.createUser("other@example.com", "Other user", PASSWORD,
                UserRole.REQUESTER);
        MockHttpSession session = login(csrf(null), EMAIL);
        CsrfState fresh = csrf(session);

        MvcResult result = mockMvc.perform(post("/api/tickets").session(session)
                        .header(fresh.headerName(), fresh.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Printer offline", "description", "The printer is unreachable.",
                                "priority", "HIGH", "requesterId", other.getId(), "requesterEmail", other.getEmail(),
                                "requester", Map.of("id", other.getId()), "userId", other.getId()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();

        Map<String, Object> response = JsonPath.read(result.getResponse().getContentAsString(), "$");
        assertThat(response).containsOnlyKeys("id", "title", "description", "status", "priority",
                "createdAt", "updatedAt");
        Long ticketId = ((Number) response.get("id")).longValue();
        ticketRepository.flush();
        entityManager.clear();
        Ticket persisted = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(persisted.getRequester().getId()).isEqualTo(authenticatedId).isNotEqualTo(other.getId());
        assertThat(persisted.getRequester().getEmail()).isEqualTo(EMAIL);
    }

    @Test
    void logsOutByInvalidatingTheSessionAndClearingAuthenticationAndCsrfState() throws Exception {
        MockHttpSession session = login(csrf(null), EMAIL);
        String formerSessionId = session.getId();
        CsrfState csrf = csrf(session);
        SecurityContext context = (SecurityContext) session
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);

        MvcResult logout = mockMvc.perform(post("/api/auth/logout").session(session)
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION)).andReturn();

        assertThat(session.isInvalid()).isTrue();
        assertThat(context.getAuthentication()).isNull();
        assertThat(logout.getRequest().getSession(false)).isNull();
        assertUnauthenticated(mockMvc.perform(get("/api/tickets").session(session)));
        CsrfState afterLogout = csrf(null);
        assertThat(afterLogout.session().getId()).isNotEqualTo(formerSessionId);
        assertForbidden(mockMvc.perform(loginRequest(afterLogout.session(), EMAIL, PASSWORD)
                .header(csrf.headerName(), csrf.token())));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "invalid-token")
    void rejectsLogoutWithoutValidCsrfAndPreservesAuthentication(String suppliedToken) throws Exception {
        MockHttpSession session = login(csrf(null), EMAIL);
        CsrfState csrf = csrf(session);
        MockHttpServletRequestBuilder logout = post("/api/auth/logout").session(session);
        if (suppliedToken != null) {
            logout.header(csrf.headerName(), suppliedToken);
        }

        assertForbidden(mockMvc.perform(logout));

        assertThat(session.isInvalid()).isFalse();
        mockMvc.perform(get("/api/tickets").session(session)).andExpect(status().isOk());
    }

    @Test
    void rejectsAnonymousLogoutEvenWithValidCsrf() throws Exception {
        CsrfState csrf = csrf(null);

        assertUnauthenticated(mockMvc.perform(post("/api/auth/logout").session(csrf.session())
                .header(csrf.headerName(), csrf.token())));
    }

    @Test
    void doesNotAllowGetLogoutOrUnmatchedRoutesForAuthenticatedUsers() throws Exception {
        MockHttpSession session = login(csrf(null), EMAIL);

        assertForbidden(mockMvc.perform(get("/api/auth/logout").session(session)));
        assertForbidden(mockMvc.perform(get("/unmatched-route").session(session)));
        mockMvc.perform(get("/api/tickets").session(session)).andExpect(status().isOk());
    }

    @Test
    void doesNotEnableHtmlLoginOrHttpBasic() throws Exception {
        assertUnauthenticated(mockMvc.perform(get("/login").accept(MediaType.TEXT_HTML)));
        String basicCredentials = Base64.getEncoder().encodeToString((EMAIL + ":" + PASSWORD)
                .getBytes(StandardCharsets.UTF_8));

        assertUnauthenticated(mockMvc.perform(get("/api/tickets")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basicCredentials)));
    }

    @ParameterizedTest(name = "invalid login request {index}")
    @MethodSource("invalidLoginRequests")
    void rejectsInvalidLoginBodiesWithoutExposingCredentials(String body) throws Exception {
        CsrfState csrf = csrf(null);

        mockMvc.perform(post("/api/auth/login").session(csrf.session())
                        .header(csrf.headerName(), csrf.token()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andExpect(content().string(not(containsString("passwordHash"))));
        assertThat(csrf.session().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
    }

    static Stream<String> invalidLoginRequests() {
        return Stream.of(
                "{}",
                "{\"email\":\"  \",\"password\":\"" + PASSWORD + "\"}",
                "{\"email\":\"" + EMAIL + "\",\"password\":\"  \"}",
                "{\"email\":\"" + "a".repeat(255) + "\",\"password\":\"" + PASSWORD + "\"}",
                "{\"password\":\"" + PASSWORD + "\","
        );
    }

    private CsrfState csrf(MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store"))).andReturn();
        Map<String, String> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        assertThat(body).containsOnlyKeys("token", "headerName");
        return new CsrfState((MockHttpSession) result.getRequest().getSession(false),
                body.get("headerName"), body.get("token"));
    }

    private MockHttpSession login(CsrfState csrf, String email) throws Exception {
        MvcResult result = mockMvc.perform(loginRequest(csrf.session(), email, PASSWORD)
                        .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpServletRequestBuilder loginRequest(MockHttpSession session, String email, String password) {
        return post("/api/auth/login").session(session).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, password)));
    }

    private ResultActions assertUnauthenticated(ResultActions response) throws Exception {
        return assertProblem(response, 401, "Authentication required",
                "Authentication is required to access this resource.");
    }

    private ResultActions assertForbidden(ResultActions response) throws Exception {
        return assertProblem(response, 403, "Forbidden", "Access to this resource is forbidden.");
    }

    private ResultActions assertProblem(ResultActions response, int statusCode, String title, String detail)
            throws Exception {
        return response.andExpect(status().is(statusCode))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(statusCode))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").exists())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(content().string(not(containsString(PASSWORD))))
                .andExpect(content().string(not(containsString(EMAIL))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    private record CsrfState(MockHttpSession session, String headerName, String token) {
    }

}
