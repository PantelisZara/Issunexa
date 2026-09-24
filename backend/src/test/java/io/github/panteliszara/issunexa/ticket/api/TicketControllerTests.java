package io.github.panteliszara.issunexa.ticket.api;

import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketNotFoundException;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketService;
import io.github.panteliszara.issunexa.ticket.TicketSortDirection;
import io.github.panteliszara.issunexa.ticket.TicketSortField;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TicketController.class)
class TicketControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketService ticketService;

    @ParameterizedTest
    @MethodSource("validTitles")
    void createsTicketWithLocationAndResponse(String title) throws Exception {
        Ticket ticket = persistedTicket(title, TicketStatus.OPEN);
        when(ticketService.createTicket(title, "The office printer is unreachable.", TicketPriority.HIGH))
                .thenReturn(ticket);

        mockMvc.perform(post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","description":"The office printer is unreachable.","priority":"HIGH"}
                                """.formatted(title)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Location", "http://localhost/api/tickets/42"))
                .andExpect(content().json(ticketJson(title, "OPEN"), JsonCompareMode.STRICT));

        verify(ticketService).createTicket(title, "The office printer is unreachable.", TicketPriority.HIGH);
    }

    static Stream<String> validTitles() {
        return Stream.of("Printer offline", "t".repeat(255));
    }

    @Test
    void retrievesTicketThroughService() throws Exception {
        when(ticketService.getTicket(42L)).thenReturn(persistedTicket("Printer offline", TicketStatus.IN_PROGRESS));

        mockMvc.perform(get("/api/tickets/42"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(ticketJson("Printer offline", "IN_PROGRESS"), JsonCompareMode.STRICT));

        verify(ticketService).getTicket(42L);
    }

    @Test
    void returnsProblemDetailForMissingTicket() throws Exception {
        when(ticketService.getTicket(99L)).thenThrow(new TicketNotFoundException(99L));

        mockMvc.perform(get("/api/tickets/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {
                          "type":"about:blank",
                          "title":"Ticket not found",
                          "status":404,
                          "detail":"Ticket with ID 99 was not found",
                          "instance":"/api/tickets/99"
                        }
                        """, JsonCompareMode.STRICT));

        verify(ticketService).getTicket(99L);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void rejectsInvalidRequestBeforeCallingService(String scenario, String request, String field) throws Exception {
        mockMvc.perform(post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Request validation failed."))
                .andExpect(jsonPath("$.instance").value("/api/tickets"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());

        verifyNoInteractions(ticketService);
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("blank title", """
                        {"title":" ","description":"Description","priority":"HIGH"}
                        """, "title"),
                Arguments.of("missing title", """
                        {"description":"Description","priority":"HIGH"}
                        """, "title"),
                Arguments.of("null title", """
                        {"title":null,"description":"Description","priority":"HIGH"}
                        """, "title"),
                Arguments.of("title longer than 255", """
                        {"title":"%s","description":"Description","priority":"HIGH"}
                        """.formatted("t".repeat(256)), "title"),
                Arguments.of("blank description", """
                        {"title":"Title","description":" ","priority":"HIGH"}
                        """, "description"),
                Arguments.of("missing description", """
                        {"title":"Title","priority":"HIGH"}
                        """, "description"),
                Arguments.of("null description", """
                        {"title":"Title","description":null,"priority":"HIGH"}
                        """, "description"),
                Arguments.of("missing priority", """
                        {"title":"Title","description":"Description"}
                        """, "priority"),
                Arguments.of("null priority", """
                        {"title":"Title","description":"Description","priority":null}
                        """, "priority")
        );
    }

    @Test
    void returnsFieldErrorsInDeterministicOrder() throws Exception {
        mockMvc.perform(post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":" ","description":" ","priority":null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {
                          "type":"about:blank",
                          "title":"Validation failed",
                          "status":400,
                          "detail":"Request validation failed.",
                          "instance":"/api/tickets",
                          "errors":[
                            {"field":"description","message":"must not be blank"},
                            {"field":"priority","message":"must not be null"},
                            {"field":"title","message":"must not be blank"}
                          ]
                        }
                        """, JsonCompareMode.STRICT));

        verifyNoInteractions(ticketService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"title\":\"Title\",\"description\":\"Description\",\"priority\":\"UNSUPPORTED\"}",
            "{\"title\":"
    })
    void returnsSafeProblemDetailForUnreadableBody(String request) throws Exception {
        mockMvc.perform(post("/api/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {
                          "type":"about:blank",
                          "title":"Invalid request body",
                          "status":400,
                          "detail":"Malformed or unreadable request body.",
                          "instance":"/api/tickets"
                        }
                        """, JsonCompareMode.STRICT));

        verifyNoInteractions(ticketService);
    }

    @Test
    void listsTicketsWithDefaultPaginationAndSorting() throws Exception {
        Ticket ticket = persistedTicket("Printer offline", TicketStatus.OPEN);
        when(ticketService.listTickets(0, 20, null, null, null, TicketSortField.CREATED_AT, TicketSortDirection.DESC))
                .thenReturn(new PageImpl<>(List.of(ticket), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/tickets"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "content":[%s],
                          "page":0,
                          "size":20,
                          "totalElements":1,
                          "totalPages":1,
                          "first":true,
                          "last":true
                        }
                        """.formatted(ticketJson("Printer offline", "OPEN")), JsonCompareMode.STRICT));

        verify(ticketService).listTickets(0, 20, null, null, null, TicketSortField.CREATED_AT, TicketSortDirection.DESC);
    }

    @Test
    void listsTicketsWithCustomPagination() throws Exception {
        Ticket ticket = persistedTicket("Printer offline", TicketStatus.IN_PROGRESS);
        when(ticketService.listTickets(2, 10, null, null, null, TicketSortField.CREATED_AT, TicketSortDirection.DESC))
                .thenReturn(new PageImpl<>(List.of(ticket), PageRequest.of(2, 10), 45));

        mockMvc.perform(get("/api/tickets").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "content":[%s],
                          "page":2,
                          "size":10,
                          "totalElements":45,
                          "totalPages":5,
                          "first":false,
                          "last":false
                        }
                        """.formatted(ticketJson("Printer offline", "IN_PROGRESS")), JsonCompareMode.STRICT));

        verify(ticketService).listTickets(2, 10, null, null, null, TicketSortField.CREATED_AT, TicketSortDirection.DESC);
    }

    @Test
    void listsTicketsByStatus() throws Exception {
        Ticket ticket = persistedTicket("Printer offline", TicketStatus.OPEN);
        when(ticketService.listTickets(0, 20, TicketStatus.OPEN, null, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC))
                .thenReturn(new PageImpl<>(List.of(ticket), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/tickets").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(ticketService).listTickets(0, 20, TicketStatus.OPEN, null, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC);
    }

    @Test
    void listsTicketsByPriority() throws Exception {
        Ticket ticket = persistedTicket("Printer offline", TicketStatus.OPEN);
        when(ticketService.listTickets(0, 20, null, TicketPriority.HIGH, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC))
                .thenReturn(new PageImpl<>(List.of(ticket), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/tickets").param("priority", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].priority").value("HIGH"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(ticketService).listTickets(0, 20, null, TicketPriority.HIGH, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC);
    }

    @Test
    void listsTicketsByStatusAndPriorityWithCustomPagination() throws Exception {
        Ticket ticket = persistedTicket("Printer offline", TicketStatus.IN_PROGRESS, TicketPriority.URGENT);
        when(ticketService.listTickets(1, 10, TicketStatus.IN_PROGRESS, TicketPriority.URGENT, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC))
                .thenReturn(new PageImpl<>(List.of(ticket), PageRequest.of(1, 10), 11));

        mockMvc.perform(get("/api/tickets")
                        .param("status", "IN_PROGRESS")
                        .param("priority", "URGENT")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "content":[%s],
                          "page":1,
                          "size":10,
                          "totalElements":11,
                          "totalPages":2,
                          "first":false,
                          "last":true
                        }
                        """.formatted(ticketJson("Printer offline", "IN_PROGRESS", "URGENT")),
                        JsonCompareMode.STRICT));

        verify(ticketService).listTickets(1, 10, TicketStatus.IN_PROGRESS, TicketPriority.URGENT, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC);
    }

    @ParameterizedTest(name = "sortBy={0}, direction={1}")
    @CsvSource({
            "createdAt, asc, CREATED_AT, ASC",
            "createdAt, desc, CREATED_AT, DESC",
            "updatedAt, asc, UPDATED_AT, ASC",
            "updatedAt, desc, UPDATED_AT, DESC",
            "title, asc, TITLE, ASC",
            "title, desc, TITLE, DESC",
            "title, , TITLE, DESC",
            ", asc, CREATED_AT, ASC"
    })
    void listsTicketsWithControlledSorting(
            String sortBy, String direction, TicketSortField expectedField, TicketSortDirection expectedDirection)
            throws Exception {
        when(ticketService.listTickets(0, 20, null, null, null, expectedField, expectedDirection))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));
        MockHttpServletRequestBuilder request = get("/api/tickets");
        if (sortBy != null) {
            request.param("sortBy", sortBy);
        }
        if (direction != null) {
            request.param("direction", direction);
        }

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "content":[],
                          "page":0,
                          "size":20,
                          "totalElements":0,
                          "totalPages":0,
                          "first":true,
                          "last":true
                        }
                        """, JsonCompareMode.STRICT));

        verify(ticketService).listTickets(0, 20, null, null, null, expectedField, expectedDirection);
    }

    @ParameterizedTest
    @MethodSource("validSearchQueries")
    void searchesWithNormalizedQuery(String input, String expectedQuery) throws Exception {
        when(ticketService.listTickets(0, 20, null, null, expectedQuery,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        mockMvc.perform(get("/api/tickets").param("q", input))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        verify(ticketService).listTickets(0, 20, null, null, expectedQuery,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC);
    }

    static Stream<Arguments> validSearchQueries() {
        return Stream.of(
                Arguments.of("login", "login"),
                Arguments.of("  login  ", "login"),
                Arguments.of("q".repeat(100), "q".repeat(100))
        );
    }

    @Test
    void listsTicketsWithSearchFiltersSortingAndCustomPagination() throws Exception {
        Ticket ticket = persistedTicket("Login failure", TicketStatus.OPEN);
        when(ticketService.listTickets(1, 10, TicketStatus.OPEN, TicketPriority.HIGH, "login",
                TicketSortField.UPDATED_AT, TicketSortDirection.ASC))
                .thenReturn(new PageImpl<>(List.of(ticket), PageRequest.of(1, 10), 11));

        mockMvc.perform(get("/api/tickets")
                        .param("status", "OPEN")
                        .param("priority", "HIGH")
                        .param("q", "login")
                        .param("sortBy", "updatedAt")
                        .param("direction", "asc")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "content":[%s],
                          "page":1,
                          "size":10,
                          "totalElements":11,
                          "totalPages":2,
                          "first":false,
                          "last":true
                        }
                        """.formatted(ticketJson("Login failure", "OPEN")), JsonCompareMode.STRICT));

        verify(ticketService).listTickets(1, 10, TicketStatus.OPEN, TicketPriority.HIGH, "login",
                TicketSortField.UPDATED_AT, TicketSortDirection.ASC);
    }

    @ParameterizedTest
    @MethodSource("invalidSearchQueries")
    void rejectsInvalidSearchBeforeCallingService(String query, String message) throws Exception {
        mockMvc.perform(get("/api/tickets").param("q", query))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {
                          "type":"about:blank",
                          "title":"Validation failed",
                          "status":400,
                          "detail":"Request validation failed.",
                          "instance":"/api/tickets",
                          "errors":[{"field":"q","message":"%s"}]
                        }
                        """.formatted(message), JsonCompareMode.STRICT));

        verifyNoInteractions(ticketService);
    }

    static Stream<Arguments> invalidSearchQueries() {
        return Stream.of(
                Arguments.of("", "must not be blank"),
                Arguments.of(" \t\r\n ", "must not be blank"),
                Arguments.of("\u2003", "must not be blank"),
                Arguments.of("q".repeat(101), "size must be between 0 and 100")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"invalidFilters", "invalidSortingParameters", "invalidSearchParameters"})
    void returnsSafeProblemDetailForInvalidListingParameters(String scenario, String parameter, String[] values)
            throws Exception {
        mockMvc.perform(get("/api/tickets").param(parameter, values))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {
                          "type":"about:blank",
                          "title":"Invalid request parameter",
                          "status":400,
                          "detail":"Malformed or unreadable request parameter.",
                          "instance":"/api/tickets"
                        }
                        """, JsonCompareMode.STRICT));

        verifyNoInteractions(ticketService);
    }

    static Stream<Arguments> invalidFilters() {
        return Stream.of(
                Arguments.of("invalid status", "status", new String[]{"INVALID"}),
                Arguments.of("invalid priority", "priority", new String[]{"CRITICAL"}),
                Arguments.of("lowercase status", "status", new String[]{"open"}),
                Arguments.of("lowercase priority", "priority", new String[]{"high"}),
                Arguments.of("comma-separated statuses", "status", new String[]{"OPEN,CLOSED"}),
                Arguments.of("comma-separated priorities", "priority", new String[]{"LOW,HIGH"}),
                Arguments.of("multiple statuses", "status", new String[]{"OPEN", "CLOSED"}),
                Arguments.of("multiple priorities", "priority", new String[]{"LOW", "HIGH"}),
                Arguments.of("repeated status", "status", new String[]{"OPEN", "OPEN"}),
                Arguments.of("repeated priority", "priority", new String[]{"HIGH", "HIGH"}),
                Arguments.of("array-style statuses", "status[]", new String[]{"OPEN", "CLOSED"}),
                Arguments.of("array-style priorities", "priority[]", new String[]{"LOW", "HIGH"}),
                Arguments.of("empty status", "status", new String[]{""}),
                Arguments.of("blank priority", "priority", new String[]{" "})
        );
    }

    static Stream<Arguments> invalidSortingParameters() {
        return Stream.of(
                Arguments.of("unknown sort field", "sortBy", new String[]{"unknown"}),
                Arguments.of("priority sort", "sortBy", new String[]{"priority"}),
                Arguments.of("status sort", "sortBy", new String[]{"status"}),
                Arguments.of("ID primary sort", "sortBy", new String[]{"id"}),
                Arguments.of("description sort", "sortBy", new String[]{"description"}),
                Arguments.of("sort field enum name", "sortBy", new String[]{"CREATED_AT"}),
                Arguments.of("uppercase title", "sortBy", new String[]{"TITLE"}),
                Arguments.of("invalid direction", "direction", new String[]{"sideways"}),
                Arguments.of("uppercase ascending", "direction", new String[]{"ASC"}),
                Arguments.of("uppercase descending", "direction", new String[]{"DESC"}),
                Arguments.of("empty sort field", "sortBy", new String[]{""}),
                Arguments.of("blank sort field", "sortBy", new String[]{" "}),
                Arguments.of("empty direction", "direction", new String[]{""}),
                Arguments.of("blank direction", "direction", new String[]{" "}),
                Arguments.of("padded sort field", "sortBy", new String[]{" title"}),
                Arguments.of("padded direction", "direction", new String[]{"asc "}),
                Arguments.of("comma-separated sort fields", "sortBy", new String[]{"title,createdAt"}),
                Arguments.of("comma-separated directions", "direction", new String[]{"asc,desc"}),
                Arguments.of("multiple sort fields", "sortBy", new String[]{"title", "createdAt"}),
                Arguments.of("multiple directions", "direction", new String[]{"asc", "desc"}),
                Arguments.of("repeated sort field", "sortBy", new String[]{"title", "title"}),
                Arguments.of("repeated direction", "direction", new String[]{"asc", "asc"}),
                Arguments.of("array-style sort fields", "sortBy[]", new String[]{"title", "createdAt"}),
                Arguments.of("array-style directions", "direction[]", new String[]{"asc", "desc"})
        );
    }

    static Stream<Arguments> invalidSearchParameters() {
        return Stream.of(
                Arguments.of("multiple search values", "q", new String[]{"login", "printer"}),
                Arguments.of("array-style search values", "q[]", new String[]{"login", "printer"})
        );
    }

    @ParameterizedTest
    @CsvSource({
            "0, 20, 0, 0, true",
            "3, 10, 2, 1, false",
            "0, 100, 0, 0, true"
    })
    void returnsEmptyPageWithMetadata(int page, int size, long totalElements, int totalPages, boolean first)
            throws Exception {
        Page<Ticket> tickets = new PageImpl<>(List.of(), PageRequest.of(page, size), totalElements);
        when(ticketService.listTickets(page, size, null, null, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC)).thenReturn(tickets);

        mockMvc.perform(get("/api/tickets")
                        .param("page", Integer.toString(page))
                        .param("size", Integer.toString(size)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "content":[],
                          "page":%d,
                          "size":%d,
                          "totalElements":%d,
                          "totalPages":%d,
                          "first":%s,
                          "last":true
                        }
                        """.formatted(page, size, totalElements, totalPages, first), JsonCompareMode.STRICT));

        verify(ticketService).listTickets(page, size, null, null, null,
                TicketSortField.CREATED_AT, TicketSortDirection.DESC);
    }

    @ParameterizedTest(name = "rejects {0}={1}")
    @CsvSource({
            "page, -1, must be greater than or equal to 0",
            "size, 0, must be greater than or equal to 1",
            "size, 101, must be less than or equal to 100"
    })
    void rejectsInvalidPagination(String parameter, String value, String message) throws Exception {
        mockMvc.perform(get("/api/tickets").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {
                          "type":"about:blank",
                          "title":"Validation failed",
                          "status":400,
                          "detail":"Request validation failed.",
                          "instance":"/api/tickets",
                          "errors":[{"field":"%s","message":"%s"}]
                        }
                        """.formatted(parameter, message), JsonCompareMode.STRICT));

        verifyNoInteractions(ticketService);
    }

    @Test
    void returnsPaginationErrorsInDeterministicOrder() throws Exception {
        mockMvc.perform(get("/api/tickets").param("size", "101").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {
                          "type":"about:blank",
                          "title":"Validation failed",
                          "status":400,
                          "detail":"Request validation failed.",
                          "instance":"/api/tickets",
                          "errors":[
                            {"field":"page","message":"must be greater than or equal to 0"},
                            {"field":"size","message":"must be less than or equal to 100"}
                          ]
                        }
                        """, JsonCompareMode.STRICT));

        verifyNoInteractions(ticketService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page", "size"})
    void returnsSafeProblemDetailForNonNumericPagination(String parameter) throws Exception {
        mockMvc.perform(get("/api/tickets").param(parameter, "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {
                          "type":"about:blank",
                          "title":"Invalid request parameter",
                          "status":400,
                          "detail":"Malformed or unreadable request parameter.",
                          "instance":"/api/tickets"
                        }
                        """, JsonCompareMode.STRICT));

        verifyNoInteractions(ticketService);
    }

    private static Ticket persistedTicket(String title, TicketStatus status) {
        return persistedTicket(title, status, TicketPriority.HIGH);
    }

    private static Ticket persistedTicket(String title, TicketStatus status, TicketPriority priority) {
        Ticket ticket = new Ticket(title, "The office printer is unreachable.", status, priority);
        ReflectionTestUtils.setField(ticket, "id", 42L);
        ReflectionTestUtils.setField(ticket, "createdAt", Instant.parse("2026-09-23T10:00:00Z"));
        ReflectionTestUtils.setField(ticket, "updatedAt", Instant.parse("2026-09-23T10:05:00Z"));
        return ticket;
    }

    private static String ticketJson(String title, String status) {
        return ticketJson(title, status, "HIGH");
    }

    private static String ticketJson(String title, String status, String priority) {
        return """
                {
                  "id":42,
                  "title":"%s",
                  "description":"The office printer is unreachable.",
                  "status":"%s",
                  "priority":"%s",
                  "createdAt":"2026-09-23T10:00:00Z",
                  "updatedAt":"2026-09-23T10:05:00Z"
                }
                """.formatted(title, status, priority);
    }

}
