package io.github.panteliszara.issunexa.ticket.history.api;

import io.github.panteliszara.issunexa.ticket.history.TicketHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

import java.security.Principal;

@RestController
@RequestMapping("/api/tickets/{ticketId}/history")
@Tag(name = "Ticket history")
@SecurityRequirement(name = "sessionAuth")
public class TicketHistoryController {

    private final TicketHistoryService ticketHistoryService;

    public TicketHistoryController(TicketHistoryService ticketHistoryService) {
        this.ticketHistoryService = ticketHistoryService;
    }

    @GetMapping
    @Operation(summary = "List Ticket lifecycle history", description = "REQUESTER may view only owned Ticket history. "
            + "AGENT and ADMIN may view all Tickets, including historical Tickets without a requester. "
            + "Ordering is fixed newest-first: createdAt DESC, then id DESC. "
            + "History begins with V8; earlier activity is not backfilled and may be incomplete. Comments remain separate.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lifecycle history with page metadata.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TicketHistoryPageResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid pagination. RFC 9457 Problem Detail; "
                    + "validation failures include an errors array with field and message entries.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Ticket not found or outside the requester's visibility.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    public TicketHistoryPageResponse listHistory(@PathVariable Long ticketId,
            @Parameter(description = "Zero-based page number.")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Maximum number of history entries per page.")
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size,
            Principal principal) {
        return TicketHistoryPageResponse.from(ticketHistoryService.listHistory(ticketId, principal.getName(), page, size));
    }

    @InitBinder({"page", "size"})
    void validateSinglePaginationParameter(WebDataBinder binder, NativeWebRequest request)
            throws ServletRequestBindingException {
        String parameter = binder.getObjectName();
        String[] values = request.getParameterValues(parameter);
        if (request.getParameterValues(parameter + "[]") != null
                || (values != null && (values.length != 1 || values[0].isBlank()))) {
            throw new ServletRequestBindingException("Each parameter must have one non-blank value.");
        }
    }

}
