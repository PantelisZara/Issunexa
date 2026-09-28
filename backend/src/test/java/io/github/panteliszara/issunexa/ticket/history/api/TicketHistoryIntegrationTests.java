package io.github.panteliszara.issunexa.ticket.history.api;

import com.jayway.jsonpath.JsonPath;
import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketRepository;
import io.github.panteliszara.issunexa.ticket.TicketService;
import io.github.panteliszara.issunexa.ticket.history.TicketHistoryType;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountService;
import io.github.panteliszara.issunexa.user.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Requests commit independently; parent/history assertions read committed data and OEV remains disabled.
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Testcontainers
class TicketHistoryIntegrationTests {

    private static final String PASSWORD = "history integration test password";

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
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @MockitoSpyBean
    private TicketRepository ticketRepository;

    private UserAccount requester;
    private UserAccount otherRequester;
    private UserAccount agent;
    private UserAccount admin;
    private Long ownedId;
    private Long foreignId;
    private Long historicalId;

    @BeforeEach
    void createCommittedFixture() {
        jdbc.update("DELETE FROM ticket_history_entries");
        jdbc.update("DELETE FROM ticket_comments");
        jdbc.update("DELETE FROM tickets");
        jdbc.update("DELETE FROM users");
        requester = userAccountService.createUser("alice@example.com", "Alice", PASSWORD, UserRole.REQUESTER);
        otherRequester = userAccountService.createUser("bob@example.com", "Bob", PASSWORD, UserRole.REQUESTER);
        agent = userAccountService.createUser("agent@example.com", "Agent", PASSWORD, UserRole.AGENT);
        admin = userAccountService.createUser("admin@example.com", "Admin", PASSWORD, UserRole.ADMIN);
        ownedId = ticketService.createTicket("Alice's ticket", "Needs support", TicketPriority.HIGH, requester.getEmail()).getId();
        foreignId = ticketService.createTicket("Bob's ticket", "Needs support", TicketPriority.LOW, otherRequester.getEmail()).getId();
        historicalId = jdbc.queryForObject("""
                INSERT INTO tickets (title, description, status, priority, version, created_at, updated_at)
                VALUES ('Historical', 'No requester', 'OPEN', 'LOW', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class);
    }

    @Test
    void recordsCompleteLifecycleWithTrustedActorsAndNewestFirstHistory() throws Exception {
        MockHttpSession owner = login(requester);
        CsrfState ownerCsrf = csrf(owner);
        MvcResult created = mockMvc.perform(create(owner, ownerCsrf)
                        .param("actorEmail", admin.getEmail()).header("X-Actor-Email", admin.getEmail())
                        .content(objectMapper.writeValueAsString(Map.of("title", "Lifecycle", "description", "Details",
                                "priority", "HIGH", "actorId", admin.getId(), "requesterId", admin.getId()))))
                .andExpect(status().isCreated()).andReturn();
        Long id = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(stored(id)).containsEntry("version", 0L);
        mockMvc.perform(get(path(id)).session(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("TICKET_CREATED"))
                .andExpect(jsonPath("$.content[0].newStatus").value("OPEN"))
                .andExpect(jsonPath("$.content[0].actor.id").value(requester.getId()));

        MockHttpSession staff = login(agent);
        CsrfState staffCsrf = csrf(staff);
        mockMvc.perform(claim(id, staff, staffCsrf).param("actorId", admin.getId().toString())
                        .param("assigneeId", admin.getId().toString()))
                .andExpect(status().isOk());
        assertThat(stored(id)).containsEntry("version", 1L);
        mockMvc.perform(changeStatus(id, staff, staffCsrf, "IN_PROGRESS")
                        .header("X-Actor-Email", admin.getEmail()).param("actorEmail", admin.getEmail())
                        .content(objectMapper.writeValueAsString(Map.of("status", "IN_PROGRESS", "actorId", admin.getId(),
                                "actorEmail", admin.getEmail(), "actor", Map.of("id", admin.getId())))))
                .andExpect(status().isOk());
        assertThat(stored(id)).containsEntry("version", 2L).containsEntry("status", "IN_PROGRESS")
                .containsEntry("requester_id", requester.getId()).containsEntry("assignee_id", agent.getId());

        // Equal timestamps prove that ID provides the deterministic newest-first tie-breaker.
        jdbc.update("UPDATE ticket_history_entries SET created_at = '2026-01-01T00:00:00Z' WHERE ticket_id = ?", id);
        MvcResult listed = mockMvc.perform(get(path(id)).session(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3)).andReturn();
        List<Map<String, Object>> entries = JsonPath.read(listed.getResponse().getContentAsString(), "$.content");
        assertThat(entries).extracting(row -> row.get("type")).containsExactly("STATUS_CHANGED", "ASSIGNEE_CLAIMED", "TICKET_CREATED");
        entries.forEach(this::assertSafeEntry);
        assertThat(entries.get(0)).containsEntry("previousStatus", "OPEN").containsEntry("newStatus", "IN_PROGRESS").containsEntry("assignee", null);
        assertThat(entries.get(1)).containsEntry("previousStatus", null).containsEntry("newStatus", null);
        assertThat(entries.get(2)).containsEntry("previousStatus", null).containsEntry("newStatus", "OPEN").containsEntry("assignee", null);
        assertAccount(entries.get(0).get("actor"), agent);
        assertAccount(entries.get(1).get("actor"), agent);
        assertAccount(entries.get(1).get("assignee"), agent);
        assertAccount(entries.get(2).get("actor"), requester);
        assertThat(jdbc.queryForList("SELECT actor_id FROM ticket_history_entries WHERE ticket_id = ? ORDER BY id", Long.class, id))
                .containsExactly(requester.getId(), agent.getId(), agent.getId());
        mockMvc.perform(get(path(id)).session(owner).param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("TICKET_CREATED"))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.last").value(true));
        assertThat(count(id)).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "historical", "missing"})
    void requesterHiddenAndMissingHistoryUseSameTicketNotFoundContract(String kind) throws Exception {
        Long id = switch (kind) {
            case "foreign" -> foreignId;
            case "historical" -> historicalId;
            default -> -1L;
        };
        MockHttpSession session = login(requester);
        assertProblem(mockMvc.perform(get(path(id)).session(session)), 404, "Ticket not found",
                "Ticket with ID " + id + " was not found", path(id));
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"AGENT", "ADMIN"})
    void staffCanReadEveryExistingTicketIncludingEmptyHistoricalHistory(UserRole role) throws Exception {
        MockHttpSession session = login(role == UserRole.AGENT ? agent : admin);
        for (Long id : List.of(ownedId, foreignId, historicalId)) {
            mockMvc.perform(get(path(id)).session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(id.equals(historicalId) ? 0 : 1));
        }
        assertProblem(mockMvc.perform(get(path(-1L)).session(session)), 404, "Ticket not found",
                "Ticket with ID -1 was not found", path(-1L));
    }

    @Test
    void historyRequiresAuthenticationAndCannotBeManuallyCreated() throws Exception {
        assertProblem(mockMvc.perform(get(path(ownedId))), 401, "Authentication required",
                "Authentication is required to access this resource.", path(ownedId));
        MockHttpSession staff = login(agent);
        CsrfState csrf = csrf(staff);
        for (var request : List.of(post(path(ownedId)), patch(path(ownedId)),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path(ownedId)))) {
            mockMvc.perform(request.session(staff).header(csrf.headerName(), csrf.token()))
                    .andExpect(status().isMethodNotAllowed());
        }
        assertThat(count(ownedId)).isEqualTo(1);
    }

    @Test
    void failedClaimsTransitionsMissingTicketsAndRequesterDenialsCreateNoHistory() throws Exception {
        MockHttpSession staff = login(agent);
        CsrfState csrf = csrf(staff);
        mockMvc.perform(claim(ownedId, staff, csrf)).andExpect(status().isOk());
        List<Map<String, Object>> before = historyRows();
        Map<String, Object> ticketBefore = stored(ownedId);

        mockMvc.perform(claim(ownedId, staff, csrf)).andExpect(status().isConflict());
        mockMvc.perform(changeStatus(ownedId, staff, csrf, "CLOSED")).andExpect(status().isConflict());
        mockMvc.perform(claim(-1L, staff, csrf)).andExpect(status().isNotFound());
        mockMvc.perform(changeStatus(-1L, staff, csrf, "IN_PROGRESS")).andExpect(status().isNotFound());
        MockHttpSession owner = login(requester);
        CsrfState ownerCsrf = csrf(owner);
        mockMvc.perform(claim(foreignId, owner, ownerCsrf)).andExpect(status().isForbidden());
        mockMvc.perform(changeStatus(ownedId, owner, ownerCsrf, "IN_PROGRESS")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tickets/" + foreignId + "/claim").session(staff)).andExpect(status().isForbidden());

        assertThat(historyRows()).isEqualTo(before);
        assertThat(stored(ownedId)).isEqualTo(ticketBefore);
    }

    @Test
    void commentCreationLeavesHistoryAndParentUnchanged() throws Exception {
        List<Map<String, Object>> before = historyRows();
        Map<String, Object> ticketBefore = stored(ownedId);
        MockHttpSession owner = login(requester);
        CsrfState csrf = csrf(owner);

        mockMvc.perform(post("/api/tickets/" + ownedId + "/comments").session(owner)
                        .header(csrf.headerName(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Additional details\"}"))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_comments", Long.class)).isEqualTo(1);
        assertThat(historyRows()).isEqualTo(before);
        assertThat(stored(ownedId)).isEqualTo(ticketBefore);
    }

    @ParameterizedTest
    @EnumSource(TicketHistoryType.class)
    void historyPersistenceFailureRollsBackTheEntireTicketOperation(TicketHistoryType type) throws Exception {
        MockHttpSession staff = login(agent);
        CsrfState csrf = csrf(staff);
        List<Map<String, Object>> ticketsBefore = jdbc.queryForList("SELECT * FROM tickets ORDER BY id");
        List<Map<String, Object>> historyBefore = historyRows();
        // Test-only database guard; no production failure hook or altered transaction propagation.
        jdbc.execute("ALTER TABLE ticket_history_entries ADD CONSTRAINT ck_history_test_reject CHECK (type <> '" + type.name() + "') NOT VALID");
        try {
            MockHttpServletRequestBuilder request = switch (type) {
                case TICKET_CREATED -> create(staff, csrf);
                case ASSIGNEE_CLAIMED -> claim(ownedId, staff, csrf);
                case STATUS_CHANGED -> changeStatus(ownedId, staff, csrf, "IN_PROGRESS");
            };
            assertThatThrownBy(() -> mockMvc.perform(request)).rootCause()
                    .isInstanceOfSatisfying(PSQLException.class, error -> {
                        assertThat(error.getSQLState()).isEqualTo("23514");
                        assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("ck_history_test_reject");
                    });
        } finally {
            jdbc.execute("ALTER TABLE ticket_history_entries DROP CONSTRAINT ck_history_test_reject");
        }
        assertThat(jdbc.queryForList("SELECT * FROM tickets ORDER BY id")).isEqualTo(ticketsBefore);
        assertThat(historyRows()).isEqualTo(historyBefore);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void optimisticRollbackOfClaimOrStatusCannotLeaveHistory(boolean claim) throws Exception {
        MockHttpSession staff = login(agent);
        CsrfState csrf = csrf(staff);
        List<Map<String, Object>> before = historyRows();
        Answer<?> read = mockingDetails(ticketRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object stale = read.answer(invocation);
            EntityManager winner = entityManagerFactory.createEntityManager();
            try {
                winner.getTransaction().begin();
                winner.find(Ticket.class, ownedId).claim(winner.find(UserAccount.class, admin.getId()));
                winner.getTransaction().commit();
            } finally {
                if (winner.getTransaction().isActive()) {
                    winner.getTransaction().rollback();
                }
                winner.close();
            }
            return stale;
        }).when(ticketRepository).findById(ownedId);

        mockMvc.perform(claim ? claim(ownedId, staff, csrf) : changeStatus(ownedId, staff, csrf, "IN_PROGRESS"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Concurrent ticket update"));

        assertThat(historyRows()).isEqualTo(before);
        assertThat(stored(ownedId)).containsEntry("assignee_id", admin.getId()).containsEntry("version", 1L).containsEntry("status", "OPEN");
    }

    private MockHttpServletRequestBuilder create(MockHttpSession session, CsrfState csrf) {
        return post("/api/tickets").session(session).header(csrf.headerName(), csrf.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Lifecycle\",\"description\":\"Details\",\"priority\":\"HIGH\"}");
    }

    private MockHttpServletRequestBuilder claim(Long id, MockHttpSession session, CsrfState csrf) {
        return post("/api/tickets/" + id + "/claim").session(session).header(csrf.headerName(), csrf.token());
    }

    private MockHttpServletRequestBuilder changeStatus(Long id, MockHttpSession session, CsrfState csrf, String status) {
        return patch("/api/tickets/" + id + "/status").session(session).header(csrf.headerName(), csrf.token())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("status", status)));
    }

    private String path(Long id) {
        return "/api/tickets/" + id + "/history";
    }

    private Map<String, Object> stored(Long id) {
        return jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", id);
    }

    private List<Map<String, Object>> historyRows() {
        return jdbc.queryForList("SELECT * FROM ticket_history_entries ORDER BY id");
    }

    private Long count(Long id) {
        return jdbc.queryForObject("SELECT count(*) FROM ticket_history_entries WHERE ticket_id = ?", Long.class, id);
    }

    private void assertSafeEntry(Map<String, Object> entry) {
        assertThat(entry).containsOnlyKeys("id", "type", "actor", "previousStatus", "newStatus", "assignee", "createdAt");
        assertThat(entry.get("createdAt")).isNotNull();
        assertThat((Map<?, ?>) entry.get("actor")).hasSize(2);
        if (entry.get("assignee") != null) {
            assertThat((Map<?, ?>) entry.get("assignee")).hasSize(2);
        }
    }

    private void assertAccount(Object value, UserAccount expected) {
        Map<?, ?> account = (Map<?, ?>) value;
        assertThat(account.keySet()).isEqualTo(java.util.Set.of("id", "displayName"));
        assertThat(((Number) account.get("id")).longValue()).isEqualTo(expected.getId());
        assertThat(account.get("displayName")).isEqualTo(expected.getDisplayName());
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
        var request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        Map<String, String> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        return new CsrfState((MockHttpSession) result.getRequest().getSession(false), body.get("headerName"), body.get("token"));
    }

    private void assertProblem(ResultActions result, int code, String title, String detail, String instance) throws Exception {
        result.andExpect(status().is(code)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json(objectMapper.writeValueAsString(Map.of("type", "about:blank", "title", title,
                        "status", code, "detail", detail, "instance", instance)), JsonCompareMode.STRICT));
    }

    private record CsrfState(MockHttpSession session, String headerName, String token) {
    }

}
