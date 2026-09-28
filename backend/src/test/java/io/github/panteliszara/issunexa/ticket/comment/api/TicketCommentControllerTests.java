package io.github.panteliszara.issunexa.ticket.comment.api;

import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketNotFoundException;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import io.github.panteliszara.issunexa.ticket.comment.TicketComment;
import io.github.panteliszara.issunexa.ticket.comment.TicketCommentService;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// HTTP binding only; TicketCommentIntegrationTests exercises real session authentication and CSRF.
@WebMvcTest(controllers = TicketCommentController.class, excludeAutoConfiguration = {
        ServletWebSecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class
})
@AutoConfigureMockMvc(addFilters = false)
class TicketCommentControllerTests {

    private static final String EMAIL = "alice@example.com";
    private static final String PATH = "/api/tickets/42/comments";
    private static final String COMMENT_JSON = """
            {"id":7,"body":"Comment","author":{"id":3,"displayName":"Alice"},
             "createdAt":"2026-01-01T10:00:00Z"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketCommentService service;

    @Test
    void createsSafeResponseWithoutLocationUsingOnlyTrustedPrincipal() throws Exception {
        when(service.addComment(42L, EMAIL, "Comment")).thenReturn(comment());

        mockMvc.perform(post(PATH).principal(() -> EMAIL)
                        .queryParam("authorId", "99").queryParam("authorEmail", "other@example.com")
                        .header("X-Author-Email", "other@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"body":"Comment","authorId":99,"authorEmail":"other@example.com",
                                 "author":{"id":99},"ticketId":99,"userId":99}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(COMMENT_JSON, JsonCompareMode.STRICT));

        verify(service).addComment(42L, EMAIL, "Comment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"body\":null}", "{\"body\":\"\"}", "{\"body\":\" \\t\\n\"}"})
    void rejectsMissingOrBlankBody(String json) throws Exception {
        mockMvc.perform(post(PATH).principal(() -> EMAIL).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value("body"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsOversizeBody() throws Exception {
        mockMvc.perform(post(PATH).principal(() -> EMAIL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + "x".repeat(4001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value("body"));
        verifyNoInteractions(service);
    }

    @Test
    void acceptsMaximumLengthBody() throws Exception {
        String body = "x".repeat(4000);
        when(service.addComment(42L, EMAIL, body)).thenReturn(comment());

        mockMvc.perform(post(PATH).principal(() -> EMAIL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated());

        verify(service).addComment(42L, EMAIL, body);
    }

    @Test
    void listsSafePageWithDefaultPaginationAndTrustedPrincipal() throws Exception {
        when(service.listComments(42L, EMAIL, 0, 20))
                .thenReturn(new PageImpl<>(List.of(comment()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get(PATH).principal(() -> EMAIL))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"content":[%s],"page":0,"size":20,"totalElements":1,"totalPages":1,"first":true,"last":true}
                        """.formatted(COMMENT_JSON), JsonCompareMode.STRICT));

        verify(service).listComments(42L, EMAIL, 0, 20);
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "2, 100"})
    void acceptsExplicitPagination(int page, int size) throws Exception {
        when(service.listComments(42L, EMAIL, page, size)).thenReturn(Page.empty(PageRequest.of(page, size)));

        mockMvc.perform(get(PATH).principal(() -> EMAIL)
                        .param("page", String.valueOf(page)).param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(page))
                .andExpect(jsonPath("$.size").value(size));

        verify(service).listComments(42L, EMAIL, page, size);
    }

    @ParameterizedTest
    @CsvSource({"page, -1", "size, 0", "size, -1", "size, 101"})
    void rejectsOutOfRangePagination(String name, String value) throws Exception {
        mockMvc.perform(get(PATH).principal(() -> EMAIL).param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value(name));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @MethodSource("malformedPagination")
    void rejectsMalformedAndRepeatedScalarsSafely(String name, String[] values) throws Exception {
        mockMvc.perform(get(PATH).principal(() -> EMAIL).param(name, values))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Invalid request parameter","status":400,
                         "detail":"Malformed or unreadable request parameter.","instance":"%s"}
                        """.formatted(PATH), JsonCompareMode.STRICT));
        verifyNoInteractions(service);
    }

    @Test
    void preservesExistingParentNotFoundContract() throws Exception {
        when(service.addComment(42L, EMAIL, "Comment")).thenThrow(new TicketNotFoundException(42L));
        when(service.listComments(42L, EMAIL, 0, 20)).thenThrow(new TicketNotFoundException(42L));

        for (var request : List.of(get(PATH), post(PATH).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Comment\"}"))) {
            mockMvc.perform(request.principal(() -> EMAIL))
                    .andExpect(status().isNotFound())
                    .andExpect(content().json("""
                            {"type":"about:blank","title":"Ticket not found","status":404,
                             "detail":"Ticket with ID 42 was not found","instance":"%s"}
                            """.formatted(PATH), JsonCompareMode.STRICT));
        }
    }

    private static Stream<Arguments> malformedPagination() {
        return Stream.of("page", "size").flatMap(name -> Stream.of(
                Arguments.of(name, new String[]{"abc"}),
                Arguments.of(name, new String[]{"1.5"}),
                Arguments.of(name, new String[]{"2147483648"}),
                Arguments.of(name, new String[]{""}),
                Arguments.of(name, new String[]{" "}),
                Arguments.of(name, new String[]{"1", "2"}),
                Arguments.of(name, new String[]{"1", "1"}),
                Arguments.of(name + "[]", new String[]{"1"})));
    }

    private TicketComment comment() {
        UserAccount author = new UserAccount(EMAIL, "Alice", "secret-hash", UserRole.REQUESTER);
        ReflectionTestUtils.setField(author, "id", 3L);
        Ticket ticket = new Ticket("Printer", "Offline", TicketStatus.OPEN, TicketPriority.HIGH, author);
        TicketComment comment = new TicketComment(ticket, author, "Comment");
        ReflectionTestUtils.setField(comment, "id", 7L);
        ReflectionTestUtils.setField(comment, "createdAt", Instant.parse("2026-01-01T10:00:00Z"));
        return comment;
    }

}
