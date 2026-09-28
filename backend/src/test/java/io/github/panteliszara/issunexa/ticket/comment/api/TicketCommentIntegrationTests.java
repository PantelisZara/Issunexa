package io.github.panteliszara.issunexa.ticket.comment.api;

import com.jayway.jsonpath.JsonPath;
import io.github.panteliszara.issunexa.ticket.TicketCategory;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketService;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountService;
import io.github.panteliszara.issunexa.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// No test transaction: each request commits, and DTO mapping runs with Open EntityManager in View disabled.
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Testcontainers
class TicketCommentIntegrationTests {

    private static final String PASSWORD = "comment integration test password";

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

    private UserAccount requester;
    private UserAccount otherRequester;
    private UserAccount agent;
    private UserAccount admin;
    private Long ownedId;
    private Long foreignId;
    private Long historicalId;

    @BeforeEach
    void createCommittedFixture() {
        jdbc.update("DELETE FROM ticket_comments");
        jdbc.update("DELETE FROM ticket_history_entries");
        jdbc.update("DELETE FROM tickets");
        jdbc.update("DELETE FROM users");
        requester = userAccountService.createUser("alice@example.com", "Alice", PASSWORD, UserRole.REQUESTER);
        otherRequester = userAccountService.createUser("bob@example.com", "Bob", PASSWORD, UserRole.REQUESTER);
        agent = userAccountService.createUser("agent@example.com", "Support Agent", PASSWORD, UserRole.AGENT);
        admin = userAccountService.createUser("admin@example.com", "Support Admin", PASSWORD, UserRole.ADMIN);
        ownedId = ticketService.createTicket("Alice's ticket", "Needs support", TicketPriority.HIGH, TicketCategory.INCIDENT, requester.getEmail()).getId();
        foreignId = ticketService.createTicket("Bob's ticket", "Needs support", TicketPriority.LOW, TicketCategory.INCIDENT, otherRequester.getEmail()).getId();
        historicalId = jdbc.queryForObject("""
                INSERT INTO tickets (category, title, description, status, priority, version, created_at, updated_at)
                VALUES ('OTHER', 'Historical ticket', 'Unknown requester', 'OPEN', 'LOW', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class);
    }

    @Test
    void requesterCreatesAndListsOwnCommentsWithTrustedAuthorAndNoParentChanges() throws Exception {
        MockHttpSession session = login(requester);
        CsrfState csrf = csrf(session);
        Map<String, Object> ticketBefore = storedTicket(ownedId);
        List<Map<String, Object>> usersBefore = jdbc.queryForList("SELECT * FROM users ORDER BY id");
        String body = " First  line\n\nSecond\tline ";

        MvcResult created = mockMvc.perform(add(ownedId, session, csrf, body)
                        .param("authorId", admin.getId().toString()).param("authorEmail", admin.getEmail())
                        .header("X-Author-Email", admin.getEmail()).header("X-User-Id", admin.getId())
                        .content(objectMapper.writeValueAsString(Map.of("body", body, "authorId", admin.getId(),
                                "authorEmail", admin.getEmail(), "author", Map.of("id", admin.getId()),
                                "ticketId", foreignId, "userId", admin.getId()))))
                .andExpect(status().isCreated()).andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.body").value("First  line\n\nSecond\tline")).andReturn();
        assertSafeComment(created.getResponse().getContentAsString(), "$", requester);
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        assertThat(jdbc.queryForMap("SELECT ticket_id, author_id, body FROM ticket_comments WHERE id = ?", id.longValue()))
                .containsEntry("ticket_id", ownedId).containsEntry("author_id", requester.getId())
                .containsEntry("body", "First  line\n\nSecond\tline");

        MvcResult listed = mockMvc.perform(get(path(ownedId)).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id.intValue()))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true)).andExpect(jsonPath("$.last").value(true)).andReturn();
        assertSafeComment(listed.getResponse().getContentAsString(), "$.content[0]", requester);
        Map<String, Object> page = JsonPath.read(listed.getResponse().getContentAsString(), "$");
        assertThat(page).containsOnlyKeys("content", "page", "size", "totalElements", "totalPages", "first", "last");
        assertThat(storedTicket(ownedId)).isEqualTo(ticketBefore);
        assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(usersBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "historical", "missing"})
    void requesterCannotReadOrWriteHiddenOrMissingTicketsAndReceivesIdenticalParentContract(String kind) throws Exception {
        Long id = switch (kind) {
            case "foreign" -> foreignId;
            case "historical" -> historicalId;
            default -> -1L;
        };
        if (!kind.equals("missing")) {
            jdbc.update("""
                    INSERT INTO ticket_comments (ticket_id, author_id, body, created_at)
                    VALUES (?, ?, 'Private existing comment', CURRENT_TIMESTAMP)
                    """, id, agent.getId());
        }
        Long countBefore = commentCount();
        MockHttpSession session = login(requester);

        assertProblem(mockMvc.perform(add(id, session, csrf(session), "Should not persist")), 404,
                "Ticket not found", "Ticket with ID " + id + " was not found", path(id));
        assertProblem(mockMvc.perform(get(path(id)).session(session)), 404,
                "Ticket not found", "Ticket with ID " + id + " was not found", path(id));
        assertProblem(mockMvc.perform(get("/api/tickets/" + id).session(session)), 404,
                "Ticket not found", "Ticket with ID " + id + " was not found", "/api/tickets/" + id);

        assertThat(commentCount()).isEqualTo(countBefore);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"AGENT", "ADMIN"})
    void staffCanCreateAndListAcrossBothOwnersAndHistoricalTickets(UserRole role) throws Exception {
        UserAccount actor = role == UserRole.AGENT ? agent : admin;
        MockHttpSession session = login(actor);
        CsrfState csrf = csrf(session);
        for (Long id : List.of(ownedId, foreignId, historicalId)) {
            Map<String, Object> before = storedTicket(id);
            MvcResult created = mockMvc.perform(add(id, session, csrf, "Staff response"))
                    .andExpect(status().isCreated()).andReturn();
            assertSafeComment(created.getResponse().getContentAsString(), "$", actor);
            MvcResult listed = mockMvc.perform(get(path(id)).session(session))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andReturn();
            assertSafeComment(listed.getResponse().getContentAsString(), "$.content[0]", actor);
            assertThat(jdbc.queryForObject("SELECT author_id FROM ticket_comments WHERE ticket_id = ?", Long.class, id))
                    .isEqualTo(actor.getId());
            assertThat(storedTicket(id)).isEqualTo(before);
        }
        assertProblem(mockMvc.perform(add(-1L, session, csrf, "Missing")), 404,
                "Ticket not found", "Ticket with ID -1 was not found", path(-1L));
        assertProblem(mockMvc.perform(get(path(-1L)).session(session)), 404,
                "Ticket not found", "Ticket with ID -1 was not found", path(-1L));
        assertThat(commentCount()).isEqualTo(3);
    }

    @Test
    void commentingOnClosedAssignedTicketPreservesVersionTimestampsStatusRequesterAndAssigneeAfterCommit() throws Exception {
        jdbc.update("""
                UPDATE tickets SET status = 'CLOSED', assignee_id = ?, version = 7,
                                   updated_at = '2026-01-02T03:04:05Z' WHERE id = ?
                """, agent.getId(), ownedId);
        Map<String, Object> before = storedTicket(ownedId);
        MockHttpSession session = login(requester);

        mockMvc.perform(add(ownedId, session, csrf(session), "Additional information after closure"))
                .andExpect(status().isCreated());

        assertThat(commentCount()).isEqualTo(1);
        assertThat(storedTicket(ownedId)).isEqualTo(before).containsEntry("version", 7L)
                .containsEntry("status", "CLOSED").containsEntry("requester_id", requester.getId())
                .containsEntry("assignee_id", agent.getId()).containsEntry("category", "INCIDENT");
        mockMvc.perform(get(path(ownedId)).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid"})
    void authenticatedPostRequiresValidCsrfWithoutCreatingComment(String token) throws Exception {
        MockHttpSession session = login(requester);
        CsrfState csrf = csrf(session);
        var request = post(path(ownedId)).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Rejected\"}");
        if (token.equals("invalid")) {
            request.header(csrf.headerName(), "invalid-token");
        }

        assertProblem(mockMvc.perform(request), 403, "Forbidden", "Access to this resource is forbidden.", path(ownedId));
        assertThat(commentCount()).isZero();
    }

    @Test
    void unauthenticatedRoutesRetainExistingAuthenticationAndCsrfOrdering() throws Exception {
        assertProblem(mockMvc.perform(get(path(ownedId))), 401,
                "Authentication required", "Authentication is required to access this resource.", path(ownedId));
        assertProblem(mockMvc.perform(post(path(ownedId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Rejected\"}")), 403,
                "Forbidden", "Access to this resource is forbidden.", path(ownedId));
        CsrfState anonymous = csrf(null);
        assertProblem(mockMvc.perform(add(ownedId, anonymous.session(), anonymous, "Rejected")), 401,
                "Authentication required", "Authentication is required to access this resource.", path(ownedId));
        assertThat(commentCount()).isZero();
    }

    @Test
    void listsChronologicalPagesWithTimestampTiesAndNoOtherTicketComments() throws Exception {
        Long late = insertAt(ownedId, agent, "Late", "2026-01-02T00:00:00Z");
        Long first = insertAt(ownedId, requester, "First tie", "2026-01-01T00:00:00Z");
        Long second = insertAt(ownedId, admin, "Second tie", "2026-01-01T00:00:00Z");
        insertAt(foreignId, otherRequester, "Other ticket", "2025-01-01T00:00:00Z");
        MockHttpSession session = login(requester);

        MvcResult page = mockMvc.perform(get(path(ownedId)).session(session).param("page", "0").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(first)).andExpect(jsonPath("$.content[1].id").value(second))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(true)).andExpect(jsonPath("$.last").value(false)).andReturn();
        assertSafeComment(page.getResponse().getContentAsString(), "$.content[0]", requester);
        assertSafeComment(page.getResponse().getContentAsString(), "$.content[1]", admin);
        mockMvc.perform(get(path(ownedId)).session(session).param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(late)).andExpect(jsonPath("$.last").value(true));
        mockMvc.perform(get(path(ownedId)).session(session).param("page", "2").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    private Long insertAt(Long ticketId, UserAccount author, String body, String time) {
        return jdbc.queryForObject("""
                INSERT INTO ticket_comments (ticket_id, author_id, body, created_at) VALUES (?, ?, ?, ?::timestamptz)
                RETURNING id
                """, Long.class, ticketId, author.getId(), body, time);
    }

    private String path(Long id) {
        return "/api/tickets/" + id + "/comments";
    }

    private Map<String, Object> storedTicket(Long id) {
        return jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", id);
    }

    private Long commentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM ticket_comments", Long.class);
    }

    private MockHttpServletRequestBuilder add(Long id, MockHttpSession session, CsrfState csrf, String body) {
        return post(path(id)).session(session).header(csrf.headerName(), csrf.token())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("body", body)));
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
        return new CsrfState((MockHttpSession) result.getRequest().getSession(false), body.get("headerName"), body.get("token"));
    }

    private void assertSafeComment(String json, String path, UserAccount actor) {
        Map<String, Object> comment = JsonPath.read(json, path);
        assertThat(comment).containsOnlyKeys("id", "body", "author", "createdAt");
        assertThat(comment.get("createdAt")).isNotNull();
        Map<String, Object> author = JsonPath.read(json, path + ".author");
        assertThat(author).containsOnlyKeys("id", "displayName").containsEntry("displayName", actor.getDisplayName());
        assertThat(((Number) author.get("id")).longValue()).isEqualTo(actor.getId());
    }

    private void assertProblem(ResultActions result, int code, String title, String detail, String instance) throws Exception {
        result.andExpect(status().is(code)).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().json(objectMapper.writeValueAsString(Map.of("type", "about:blank", "title", title,
                        "status", code, "detail", detail, "instance", instance)), JsonCompareMode.STRICT));
    }

    private record CsrfState(MockHttpSession session, String headerName, String token) {
    }

}
