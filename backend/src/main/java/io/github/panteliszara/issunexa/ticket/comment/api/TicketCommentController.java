package io.github.panteliszara.issunexa.ticket.comment.api;

import io.github.panteliszara.issunexa.shared.web.PaginationValidation;
import io.github.panteliszara.issunexa.ticket.comment.TicketCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

import java.security.Principal;

@RestController
@RequestMapping("/api/tickets/{ticketId}/comments")
@Tag(name = "Ticket comments")
@SecurityRequirement(name = "sessionAuth")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request body or pagination. RFC 9457 Problem Detail; "
                + "validation failures include an errors array with field and message entries.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "404", description = "Ticket not found or outside the requester's visibility. "
                + "RFC 9457 Problem Detail.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
})
public class TicketCommentController {

    private final TicketCommentService ticketCommentService;

    public TicketCommentController(TicketCommentService ticketCommentService) {
        this.ticketCommentService = ticketCommentService;
    }

    @PostMapping
    @Operation(summary = "Add a Ticket comment", description = "Append-only plain text comment by the authenticated "
            + "account. REQUESTER may comment only on owned Tickets. AGENT and ADMIN may comment on all Tickets, "
            + "including historical Tickets without a requester. Comments are allowed in every Ticket status.")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "Current session CSRF token from GET /api/auth/csrf.", schema = @Schema(type = "string"))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Comment created.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TicketCommentResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing or invalid CSRF token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TicketCommentResponse> addComment(@PathVariable Long ticketId,
            @Valid @RequestBody CreateTicketCommentRequest request, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(TicketCommentResponse.from(
                ticketCommentService.addComment(ticketId, principal.getName(), request.body())));
    }

    @GetMapping
    @Operation(summary = "List Ticket comments", description = "REQUESTER may list comments only on owned Tickets. "
            + "AGENT and ADMIN may list comments on all Tickets, including historical Tickets without a requester. "
            + "Chronological ordering is fixed: createdAt ASC, then id ASC. Pagination is zero-based.")
    @ApiResponse(responseCode = "200", description = "Ticket comments with page metadata.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TicketCommentPageResponse.class)))
    public TicketCommentPageResponse listComments(@PathVariable Long ticketId,
            @Parameter(description = "Zero-based page number.")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Maximum number of comments per page.")
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size,
            Principal principal) throws ServletRequestBindingException {
        PaginationValidation.validateOffset(page, size);
        return TicketCommentPageResponse.from(
                ticketCommentService.listComments(ticketId, principal.getName(), page, size));
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
