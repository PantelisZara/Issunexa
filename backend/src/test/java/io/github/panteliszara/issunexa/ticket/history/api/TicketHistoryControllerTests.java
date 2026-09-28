package io.github.panteliszara.issunexa.ticket.history.api;

import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import io.github.panteliszara.issunexa.ticket.history.TicketHistoryEntry;
import io.github.panteliszara.issunexa.ticket.history.TicketHistoryService;
import io.github.panteliszara.issunexa.ticket.history.TicketHistoryType;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserRole;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Binding and serialization only; integration tests cover the real session/CSRF chain.
@WebMvcTest(controllers = TicketHistoryController.class, excludeAutoConfiguration = {
        ServletWebSecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class
})
@AutoConfigureMockMvc(addFilters = false)
class TicketHistoryControllerTests {

    private static final String EMAIL = "actor@example.com";
    private static final String PATH = "/api/tickets/42/history";

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private TicketHistoryService service;

    @ParameterizedTest
    @EnumSource(TicketHistoryType.class)
    void mapsEachEventSafelyAndDelegatesTrustedPrincipalWithDefaultPage(TicketHistoryType type) throws Exception {
        when(service.listHistory(42L, EMAIL, 0, 20))
                .thenReturn(new PageImpl<>(List.of(entry(type)), PageRequest.of(0, 20), 1));
        String previous = type == TicketHistoryType.STATUS_CHANGED ? "\"OPEN\"" : "null";
        String next = switch (type) {
            case TICKET_CREATED -> "\"OPEN\"";
            case STATUS_CHANGED -> "\"IN_PROGRESS\"";
            case ASSIGNEE_CLAIMED -> "null";
        };
        String assignee = type == TicketHistoryType.ASSIGNEE_CLAIMED ? "{\"id\":4,\"displayName\":\"Assignee\"}" : "null";

        mockMvc.perform(get(PATH).principal(() -> EMAIL).param("actorEmail", "other@example.com"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"content":[{"id":7,"type":"%s","actor":{"id":3,"displayName":"Actor"},
                          "previousStatus":%s,"newStatus":%s,"assignee":%s,"createdAt":"2026-01-01T00:00:00Z"}],
                         "page":0,"size":20,"totalElements":1,"totalPages":1,"first":true,"last":true}
                        """.formatted(type, previous, next, assignee), JsonCompareMode.STRICT));

        verify(service).listHistory(42L, EMAIL, 0, 20);
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "2, 100"})
    void acceptsExplicitPageAndSize(int page, int size) throws Exception {
        when(service.listHistory(42L, EMAIL, page, size)).thenReturn(Page.empty(PageRequest.of(page, size)));

        mockMvc.perform(get(PATH).principal(() -> EMAIL).param("page", String.valueOf(page)).param("size", String.valueOf(size)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(page)).andExpect(jsonPath("$.size").value(size));

        verify(service).listHistory(42L, EMAIL, page, size);
    }

    @ParameterizedTest
    @CsvSource({"page, -1", "size, 0", "size, -1", "size, 101"})
    void rejectsOutOfBoundsPagination(String name, String value) throws Exception {
        mockMvc.perform(get(PATH).principal(() -> EMAIL).param(name, value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value(name));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("malformedPagination")
    void rejectsMalformedOrRepeatedScalarsWithSafeProblem(String name, String[] values) throws Exception {
        mockMvc.perform(get(PATH).principal(() -> EMAIL).param(name, values))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Invalid request parameter","status":400,
                         "detail":"Malformed or unreadable request parameter.","instance":"%s"}
                        """.formatted(PATH), JsonCompareMode.STRICT));
        verifyNoInteractions(service);
    }

    private static Stream<Arguments> malformedPagination() {
        return Stream.of("page", "size").flatMap(name -> Stream.of(
                Arguments.of(name, new String[]{"abc"}), Arguments.of(name, new String[]{"1.5"}),
                Arguments.of(name, new String[]{"2147483648"}), Arguments.of(name, new String[]{""}),
                Arguments.of(name, new String[]{" "}), Arguments.of(name, new String[]{"1", "2"}),
                Arguments.of(name, new String[]{"1", "1"}), Arguments.of(name + "[]", new String[]{"1"})));
    }

    private TicketHistoryEntry entry(TicketHistoryType type) {
        UserAccount actor = new UserAccount(EMAIL, "Actor", "secret-hash", UserRole.AGENT);
        UserAccount assignee = new UserAccount("assignee@example.com", "Assignee", "other-secret", UserRole.ADMIN);
        ReflectionTestUtils.setField(actor, "id", 3L);
        ReflectionTestUtils.setField(assignee, "id", 4L);
        Ticket ticket = new Ticket("Printer", "Offline", TicketStatus.OPEN, TicketPriority.HIGH, actor);
        TicketHistoryEntry entry = switch (type) {
            case TICKET_CREATED -> TicketHistoryEntry.ticketCreated(ticket, actor);
            case STATUS_CHANGED -> TicketHistoryEntry.statusChanged(ticket, actor, TicketStatus.OPEN, TicketStatus.IN_PROGRESS);
            case ASSIGNEE_CLAIMED -> TicketHistoryEntry.assigneeClaimed(ticket, actor, assignee);
        };
        ReflectionTestUtils.setField(entry, "id", 7L);
        ReflectionTestUtils.setField(entry, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        return entry;
    }

}
