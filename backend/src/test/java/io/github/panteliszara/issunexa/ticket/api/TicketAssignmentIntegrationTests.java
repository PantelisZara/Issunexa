package io.github.panteliszara.issunexa.ticket.api;

import com.jayway.jsonpath.JsonPath;
import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketRepository;
import io.github.panteliszara.issunexa.ticket.TicketService;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountService;
import io.github.panteliszara.issunexa.user.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// No test transaction: requests commit normally and response mapping runs with Open EntityManager in View disabled.
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Testcontainers
class TicketAssignmentIntegrationTests {

    private static final String PASSWORD = "assignment integration test password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private UserAccountService userAccountService;

    @MockitoSpyBean
    private TicketRepository ticketRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    private UserAccount requester;
    private UserAccount agent;
    private UserAccount admin;
    private Long ownedId;
    private Long staffOwnedId;
    private Long historicalId;

    @BeforeEach
    void createCommittedFixture() {
        jdbc.update("DELETE FROM tickets");
        jdbc.update("DELETE FROM users");
        requester = userAccountService.createUser("requester@example.com", "Requester", PASSWORD, UserRole.REQUESTER);
        agent = userAccountService.createUser("agent@example.com", "Alice Agent", PASSWORD, UserRole.AGENT);
        admin = userAccountService.createUser("admin@example.com", "Alex Admin", PASSWORD, UserRole.ADMIN);
        ownedId = ticketService.createTicket("Alpha ticket", "Needs support", TicketPriority.HIGH, requester.getEmail())
                .getId();
        staffOwnedId = ticketService.createTicket("Staff ticket", "Needs support", TicketPriority.HIGH, admin.getEmail())
                .getId();
        historicalId = jdbc.queryForObject("""
                INSERT INTO tickets (title, description, status, priority, created_at, updated_at, version)
                VALUES ('Historical ticket', 'Unknown requester', 'OPEN', 'LOW',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0) RETURNING id
                """, Long.class);
    }

    @Test
    void requesterClaimWithValidCsrfIsForbiddenWithoutMutation() throws Exception {
        MockHttpSession session = login(requester);
        Map<String, Object> before = storedState(ownedId);

        assertProblem(mockMvc.perform(claim(ownedId, session, csrf(session))), 403, "Forbidden",
                "Access to this resource is forbidden.", "/api/tickets/" + ownedId + "/claim");

        assertThat(storedState(ownedId)).isEqualTo(before).containsEntry("assignee_id", null);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"AGENT", "ADMIN"})
    void staffCanClaimRequesterStaffAndHistoricalTicketsOnlyForThemselves(UserRole role) throws Exception {
        UserAccount actor = role == UserRole.AGENT ? agent : admin;
        MockHttpSession session = login(actor);
        CsrfState csrf = csrf(session);
        for (Long id : List.of(ownedId, staffOwnedId, historicalId)) {
            Map<String, Object> before = storedState(id);
            MockHttpServletRequestBuilder request = claim(id, session, csrf);
            if (id.equals(staffOwnedId)) {
                // Unsupported selectors cannot override the authenticated identity.
                request.param("assigneeId", requester.getId().toString())
                        .param("assigneeEmail", requester.getEmail()).param("userId", requester.getId().toString())
                        .param("username", requester.getEmail()).header("X-Assignee-Email", requester.getEmail())
                        .header("X-User-Id", requester.getId()).header("X-Username", requester.getEmail())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                "assigneeId", requester.getId(), "assigneeEmail", requester.getEmail(),
                                "userId", requester.getId(), "username", requester.getEmail())));
            }
            assertAssignedResponse(mockMvc.perform(request), actor);
            assertThat(storedState(id)).containsEntry("assignee_id", actor.getId()).containsEntry("version", 1L)
                    .containsEntry("requester_id", before.get("requester_id"))
                    .containsEntry("status", before.get("status"));
        }
    }

    @Test
    void repeatClaimBySameOrOtherStaffReturnsConflictAndPreservesOriginalState() throws Exception {
        MockHttpSession first = login(agent);
        assertAssignedResponse(mockMvc.perform(claim(ownedId, first, csrf(first))), agent);
        Map<String, Object> claimed = storedState(ownedId);

        for (UserAccount next : List.of(agent, admin)) {
            MockHttpSession session = login(next);
            assertProblem(mockMvc.perform(claim(ownedId, session, csrf(session))), 409, "Ticket already assigned",
                    "The ticket already has an assignee.", "/api/tickets/" + ownedId + "/claim");
            assertThat(storedState(ownedId)).isEqualTo(claimed);
        }
    }

    @Test
    void missingClaimTargetUsesExistingNotFoundContract() throws Exception {
        MockHttpSession session = login(agent);
        assertProblem(mockMvc.perform(claim(-1L, session, csrf(session))), 404, "Ticket not found",
                "Ticket with ID -1 was not found", "/api/tickets/-1/claim");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingOrInvalidCsrfPreventsStaffClaim(boolean missing) throws Exception {
        MockHttpSession session = login(agent);
        Map<String, Object> before = storedState(ownedId);
        MockHttpServletRequestBuilder request = post("/api/tickets/" + ownedId + "/claim").session(session);
        if (!missing) {
            request.header(csrf(session).headerName(), "invalid-csrf-token");
        }
        assertProblem(mockMvc.perform(request), 403, "Forbidden", "Access to this resource is forbidden.",
                "/api/tickets/" + ownedId + "/claim");
        assertThat(storedState(ownedId)).isEqualTo(before);
    }

    @Test
    void unauthenticatedClaimWithValidCsrfReturnsUnauthorized() throws Exception {
        CsrfState anonymous = csrf(null);
        assertProblem(mockMvc.perform(claim(ownedId, anonymous.session(), anonymous)), 401, "Authentication required",
                "Authentication is required to access this resource.", "/api/tickets/" + ownedId + "/claim");
        assertThat(storedState(ownedId)).containsEntry("assignee_id", null).containsEntry("version", 0L);
    }

    @Test
    void allTicketResponsesMapSafeAssigneeAfterServiceTransactionsClose() throws Exception {
        MockHttpSession owner = login(requester);
        CsrfState ownerCsrf = csrf(owner);
        MvcResult created = mockMvc.perform(post("/api/tickets").session(owner)
                        .header(ownerCsrf.headerName(), ownerCsrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"New ticket\",\"description\":\"New issue\",\"priority\":\"LOW\"}"))
                .andExpect(status().isCreated()).andReturn();
        Map<String, Object> body = responseBody(created);
        assertTicketKeys(body);
        assertThat(body).containsEntry("assignee", null);
        Long id = ((Number) body.get("id")).longValue();
        MockHttpSession staff = login(agent);
        CsrfState staffCsrf = csrf(staff);
        assertAssignedResponse(mockMvc.perform(claim(id, staff, staffCsrf)), agent);
        assertAssignedResponse(mockMvc.perform(get("/api/tickets/" + id).session(owner)), agent);
        assertAssignedResponse(mockMvc.perform(get("/api/tickets/" + id).session(staff)), agent);
        MvcResult listed = mockMvc.perform(get("/api/tickets").session(owner).param("q", "New ticket"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andReturn();
        Map<String, Object> row = JsonPath.read(listed.getResponse().getContentAsString(), "$.content[0]");
        assertTicketKeys(row);
        assertThat(row.get("assignee")).isEqualTo(Map.of("id", agent.getId().intValue(), "displayName", "Alice Agent"));
        assertAssignedResponse(mockMvc.perform(patch("/api/tickets/" + id + "/status").session(staff)
                .header(staffCsrf.headerName(), staffCsrf.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\"}")), agent);
        assertThat(storedState(id)).containsEntry("version", 2L).containsEntry("status", "IN_PROGRESS")
                .containsEntry("requester_id", requester.getId()).containsEntry("assignee_id", agent.getId());
    }

    @Test
    void requesterPageFetchesAssigneesWithoutPerTicketQueriesAndRetainsUnassignedRows() throws Exception {
        Long second = ticketService.createTicket("Bravo ticket", "Needs support", TicketPriority.HIGH, requester.getEmail())
                .getId();
        ticketService.createTicket("Charlie ticket", "Needs support", TicketPriority.HIGH, requester.getEmail());
        ticketService.createTicket("Delta ticket", "Needs support", TicketPriority.HIGH, requester.getEmail());
        MockHttpSession agentSession = login(agent);
        assertAssignedResponse(mockMvc.perform(claim(ownedId, agentSession, csrf(agentSession))), agent);
        MockHttpSession adminSession = login(admin);
        assertAssignedResponse(mockMvc.perform(claim(second, adminSession, csrf(adminSession))), admin);
        MockHttpSession owner = login(requester);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            mockMvc.perform(get("/api/tickets").session(owner).param("sortBy", "title").param("direction", "asc")
                            .param("size", "3"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4))
                    .andExpect(jsonPath("$.content.length()").value(3))
                    .andExpect(jsonPath("$.content[0].assignee.displayName").value("Alice Agent"))
                    .andExpect(jsonPath("$.content[1].assignee.displayName").value("Alex Admin"))
                    .andExpect(jsonPath("$.content[2].title").value("Charlie ticket"))
                    .andExpect(jsonPath("$.content[2].assignee").isEmpty());
            // Current account, one content query with assignee fetch, and one scoped count query.
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
            assertThat(statistics.getEntityFetchCount()).isZero();
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    @Test
    void realConcurrentClaimAtTransactionCommitReturnsSafeConflict() throws Exception {
        MockHttpSession session = login(agent);
        CsrfState csrf = csrf(session);
        Answer<?> repositoryRead = mockingDetails(ticketRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object stale = repositoryRead.answer(invocation);
            // A separate persistence context commits after the request reads, before it writes.
            EntityManager competing = entityManagerFactory.createEntityManager();
            try {
                competing.getTransaction().begin();
                competing.find(Ticket.class, ownedId).claim(competing.find(UserAccount.class, admin.getId()));
                competing.getTransaction().commit();
            } finally {
                if (competing.getTransaction().isActive()) {
                    competing.getTransaction().rollback();
                }
                competing.close();
            }
            return stale;
        }).when(ticketRepository).findById(ownedId);

        ResultActions result = mockMvc.perform(claim(ownedId, session, csrf));
        assertProblem(result, 409, "Concurrent ticket update",
                "The ticket was modified by another request. Reload it and retry.",
                "/api/tickets/" + ownedId + "/claim");
        result.andExpect(response -> assertThat(response.getResolvedException())
                .isInstanceOf(OptimisticLockingFailureException.class));
        assertThat(storedState(ownedId)).containsEntry("assignee_id", admin.getId()).containsEntry("version", 1L)
                .containsEntry("status", "OPEN").containsEntry("requester_id", requester.getId());
    }

    private Map<String, Object> storedState(Long id) {
        return jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", id);
    }

    private MockHttpServletRequestBuilder claim(Long id, MockHttpSession session, CsrfState csrf) {
        return post("/api/tickets/" + id + "/claim").session(session).header(csrf.headerName(), csrf.token());
    }

    private MockHttpSession login(UserAccount account) throws Exception {
        CsrfState anonymous = csrf(null);
        MvcResult result = mockMvc.perform(post("/api/auth/login").session(anonymous.session())
                        .header(anonymous.headerName(), anonymous.token()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", account.getEmail(), "password", PASSWORD))))
                .andExpect(status().isNoContent()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private CsrfState csrf(MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        Map<String, String> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        return new CsrfState((MockHttpSession) result.getRequest().getSession(false),
                body.get("headerName"), body.get("token"));
    }

    private Map<String, Object> responseBody(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$");
    }

    private void assertAssignedResponse(ResultActions actions, UserAccount actor) throws Exception {
        MvcResult result = actions.andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.assignee.id").value(actor.getId()))
                .andExpect(jsonPath("$.assignee.displayName").value(actor.getDisplayName())).andReturn();
        Map<String, Object> body = responseBody(result);
        assertTicketKeys(body);
        Map<String, Object> assignee = JsonPath.read(result.getResponse().getContentAsString(), "$.assignee");
        assertThat(assignee).containsOnlyKeys("id", "displayName");
    }

    private void assertTicketKeys(Map<String, Object> body) {
        assertThat(body).containsOnlyKeys("id", "title", "description", "status", "priority",
                "createdAt", "updatedAt", "assignee");
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
