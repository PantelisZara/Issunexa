package io.github.panteliszara.issunexa.ticket.api;

import com.jayway.jsonpath.JsonPath;
import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketRepository;
import io.github.panteliszara.issunexa.ticket.TicketService;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import io.github.panteliszara.issunexa.user.UserAccountService;
import io.github.panteliszara.issunexa.user.UserRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Testcontainers
@Transactional
class TicketAuthorizationIntegrationTests {

    private static final String REQUESTER_A = "requester-a@example.com";
    private static final String REQUESTER_B = "requester-b@example.com";
    private static final String PASSWORD = "authorization integration test password";

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
    private TicketService ticketService;

    @MockitoSpyBean
    private TicketRepository ticketRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private Long firstOwnedId;
    private Long secondOwnedId;
    private Long otherOwnedId;
    private Long hiddenId;
    private Long historicalId;
    private List<Long> allIds;

    @BeforeEach
    void createAccountsAndTickets() {
        userAccountService.createUser(REQUESTER_A, "Requester A", PASSWORD, UserRole.REQUESTER);
        userAccountService.createUser(REQUESTER_B, "Requester B", PASSWORD, UserRole.REQUESTER);
        userAccountService.createUser("agent@example.com", "Agent", PASSWORD, UserRole.AGENT);
        userAccountService.createUser("admin@example.com", "Admin", PASSWORD, UserRole.ADMIN);
        firstOwnedId = createTicket("Alpha login", TicketPriority.HIGH, REQUESTER_A).getId();
        secondOwnedId = createTicket("Beta login", TicketPriority.HIGH, REQUESTER_A).getId();
        Ticket other = createTicket("Gamma display", TicketPriority.LOW, REQUESTER_A);
        other.changeStatus(TicketStatus.IN_PROGRESS);
        otherOwnedId = other.getId();
        hiddenId = createTicket("Aardvark login", TicketPriority.HIGH, REQUESTER_B).getId();
        Long agentId = createTicket("Agent note", TicketPriority.HIGH, "agent@example.com").getId();
        Long adminId = createTicket("Admin note", TicketPriority.HIGH, "admin@example.com").getId();
        // The schema intentionally permits historical Tickets whose requester is unknown.
        historicalId = jdbcTemplate.queryForObject("""
                INSERT INTO tickets (title, description, status, priority, created_at, updated_at, version)
                VALUES ('Ancient login', 'Login failure', 'OPEN', 'HIGH',
                        TIMESTAMPTZ '2000-01-01 00:00:00+00', TIMESTAMPTZ '2000-01-01 00:00:00+00', 0) RETURNING id
                """, Long.class);
        ticketRepository.flush();
        allIds = List.of(firstOwnedId, secondOwnedId, otherOwnedId, hiddenId, historicalId, agentId, adminId);
        entityManager.clear();
    }

    @Test
    void requesterPaginationAndTotalsContainOnlyOwnedRowsDespiteSpoofedIdentity() throws Exception {
        MockHttpSession session = login(REQUESTER_A);
        List<Long> expected = List.of(firstOwnedId, secondOwnedId, otherOwnedId);

        for (int page = 0; page < expected.size(); page++) {
            mockMvc.perform(get("/api/tickets").session(session)
                            .param("page", Integer.toString(page)).param("size", "1")
                            .param("sortBy", "title").param("direction", "asc")
                            .param("requesterEmail", REQUESTER_B).param("role", "ADMIN")
                            .header("X-Requester-Email", REQUESTER_B))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(expected.get(page)))
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.page").value(page));
        }
    }

    @Test
    void ownershipCombinesWithFiltersSearchSortingAndPaginationInPostgres() throws Exception {
        MockHttpSession session = login(REQUESTER_A);
        List<Long> expected = List.of(secondOwnedId, firstOwnedId);

        for (int page = 0; page < expected.size(); page++) {
            mockMvc.perform(get("/api/tickets").session(session)
                            .param("status", "OPEN").param("priority", "HIGH").param("q", "LoGiN")
                            .param("sortBy", "title").param("direction", "desc")
                            .param("size", "1").param("page", Integer.toString(page)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(expected.get(page)))
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.totalPages").value(2));
        }
    }

    @Test
    void requesterCanReadOwnTicketWhileOtherHistoricalAndMissingUseIdenticalNotFoundContract() throws Exception {
        MockHttpSession session = login(REQUESTER_A);
        assertTicketResponse(mockMvc.perform(get("/api/tickets/" + firstOwnedId).session(session)), firstOwnedId);

        for (Long id : List.of(hiddenId, historicalId, -1L)) {
            assertProblem(mockMvc.perform(get("/api/tickets/" + id).session(session)), 404, "Ticket not found",
                    "Ticket with ID " + id + " was not found", "/api/tickets/" + id);
        }
    }

    @Test
    void requesterStatusChangeWithValidCsrfIsForbiddenWithoutChangingStoredState() throws Exception {
        MockHttpSession session = login(REQUESTER_A);
        CsrfState csrf = csrf(session);
        Map<String, Object> before = storedState(firstOwnedId);

        assertProblem(mockMvc.perform(statusChange(firstOwnedId, session, csrf)), 403, "Forbidden",
                "Access to this resource is forbidden.", "/api/tickets/" + firstOwnedId + "/status");

        ticketRepository.flush();
        entityManager.clear();
        assertThat(storedState(firstOwnedId)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"AGENT", "ADMIN"})
    void staffCanListAndRetrieveEveryOwnerIncludingHistoricalTickets(UserRole role) throws Exception {
        MockHttpSession session = login(staffEmail(role));
        MvcResult result = mockMvc.perform(get("/api/tickets").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(allIds.size())).andReturn();
        List<Number> ids = JsonPath.read(result.getResponse().getContentAsString(), "$.content[*].id");
        assertThat(ids.stream().map(Number::longValue).toList()).containsExactlyInAnyOrderElementsOf(allIds);
        for (Long id : allIds) {
            assertTicketResponse(mockMvc.perform(get("/api/tickets/" + id).session(session)), id);
        }
        assertProblem(mockMvc.perform(get("/api/tickets/-1").session(session)), 404, "Ticket not found",
                "Ticket with ID -1 was not found", "/api/tickets/-1");
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"AGENT", "ADMIN"})
    void staffCanChangeAnotherRequestersAndHistoricalTicketsWithoutReassigningThem(UserRole role) throws Exception {
        MockHttpSession session = login(staffEmail(role));
        CsrfState csrf = csrf(session);
        for (Long id : List.of(hiddenId, historicalId)) {
            Map<String, Object> before = storedState(id);
            mockMvc.perform(statusChange(id, session, csrf))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
            ticketRepository.flush();
            entityManager.clear();
            assertThat(storedState(id)).containsEntry("status", "IN_PROGRESS")
                    .containsEntry("requester_id", before.get("requester_id"));
        }
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void serviceProxyEnforcesStatusRolesEvenOutsideTheController(UserRole role) throws Exception {
        MockHttpSession session = login(role == UserRole.REQUESTER ? REQUESTER_A : staffEmail(role));
        SecurityContext context = (SecurityContext) session
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        clearInvocations(ticketRepository);

        try {
            SecurityContextHolder.setContext(context);
            if (role == UserRole.REQUESTER) {
                assertThatThrownBy(() -> ticketService.changeStatus(firstOwnedId, TicketStatus.IN_PROGRESS, context.getAuthentication().getName()))
                        .isInstanceOf(AccessDeniedException.class);
                verifyNoInteractions(ticketRepository);
            } else {
                assertThat(ticketService.changeStatus(firstOwnedId, TicketStatus.IN_PROGRESS, context.getAuthentication().getName()).getStatus())
                        .isEqualTo(TicketStatus.IN_PROGRESS);
                verify(ticketRepository).findById(firstOwnedId);
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
        ticketRepository.flush();
        entityManager.clear();
        assertThat(storedState(firstOwnedId)).containsEntry("status",
                role == UserRole.REQUESTER ? "OPEN" : "IN_PROGRESS");
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void serviceProxyEnforcesClaimRolesBeforeTicketAccess(UserRole role) throws Exception {
        String email = role == UserRole.REQUESTER ? REQUESTER_A : staffEmail(role);
        MockHttpSession session = login(email);
        SecurityContext context = (SecurityContext) session
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        clearInvocations(ticketRepository);

        try {
            SecurityContextHolder.setContext(context);
            if (role == UserRole.REQUESTER) {
                assertThatThrownBy(() -> ticketService.claimTicket(firstOwnedId, email))
                        .isInstanceOf(AccessDeniedException.class);
                verifyNoInteractions(ticketRepository);
            } else {
                assertThat(ticketService.claimTicket(firstOwnedId, email).getAssignee().getEmail()).isEqualTo(email);
                verify(ticketRepository).findById(firstOwnedId);
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
        ticketRepository.flush();
        entityManager.clear();
        Long expected = role == UserRole.REQUESTER ? null
                : jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        assertThat(jdbcTemplate.queryForObject("SELECT assignee_id FROM tickets WHERE id = ?",
                Long.class, firstOwnedId)).isEqualTo(expected);
    }

    @Test
    void unauthenticatedTicketAccessStillUsesUnauthorizedProblemDetails() throws Exception {
        assertProblem(mockMvc.perform(get("/api/tickets")), 401, "Authentication required",
                "Authentication is required to access this resource.", "/api/tickets");
        CsrfState csrf = csrf(null);
        assertProblem(mockMvc.perform(statusChange(firstOwnedId, csrf.session(), csrf)), 401, "Authentication required",
                "Authentication is required to access this resource.", "/api/tickets/" + firstOwnedId + "/status");
    }

    @Test
    void flywayCreatesOnlyTheRequesterBtreeIndexInV5() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank
                """, String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'tickets'
                """, String.class)).containsExactlyInAnyOrder("pk_tickets", "idx_tickets_requester_id");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'tickets' AND indexname = 'idx_tickets_requester_id'
                """, String.class))
                .isEqualTo("CREATE INDEX idx_tickets_requester_id ON public.tickets USING btree (requester_id)");
    }

    private Ticket createTicket(String title, TicketPriority priority, String email) {
        return ticketService.createTicket(title, "Ticket description", priority, email);
    }

    private Map<String, Object> storedState(Long id) {
        return jdbcTemplate.queryForMap("SELECT status, updated_at, requester_id FROM tickets WHERE id = ?", id);
    }

    private static String staffEmail(UserRole role) {
        return switch (role) {
            case AGENT -> "agent@example.com";
            case ADMIN -> "admin@example.com";
            case REQUESTER -> throw new IllegalArgumentException("Expected a staff role");
        };
    }

    private MockHttpServletRequestBuilder statusChange(Long id, MockHttpSession session, CsrfState csrf) {
        return patch("/api/tickets/" + id + "/status").session(session)
                .header(csrf.headerName(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\"}");
    }

    private MockHttpSession login(String email) throws Exception {
        CsrfState anonymous = csrf(null);
        MvcResult result = mockMvc.perform(post("/api/auth/login").session(anonymous.session())
                        .header(anonymous.headerName(), anonymous.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isNoContent()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private CsrfState csrf(MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store"))).andReturn();
        Map<String, String> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        return new CsrfState((MockHttpSession) result.getRequest().getSession(false),
                body.get("headerName"), body.get("token"));
    }

    private void assertTicketResponse(ResultActions result, Long id) throws Exception {
        MvcResult response = result.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id)).andReturn();
        Map<String, Object> body = JsonPath.read(response.getResponse().getContentAsString(), "$");
        assertThat(body).containsOnlyKeys("id", "title", "description", "status", "priority", "createdAt", "updatedAt", "assignee");
    }

    private void assertProblem(ResultActions result, int code, String title, String detail, String instance)
            throws Exception {
        result.andExpect(status().is(code))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(content().json(objectMapper.writeValueAsString(Map.of(
                        "type", "about:blank", "title", title, "status", code, "detail", detail, "instance", instance)),
                        JsonCompareMode.STRICT));
    }

    private record CsrfState(MockHttpSession session, String headerName, String token) {
    }

}
