package io.github.panteliszara.issunexa.ticket.api;

import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketService;
import io.github.panteliszara.issunexa.ticket.TicketSortDirection;
import io.github.panteliszara.issunexa.ticket.TicketSortField;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.beans.PropertyEditorSupport;
import java.net.URI;
import java.security.Principal;

@RestController
@RequestMapping("/api/tickets")
@Tag(name = "Tickets")
@SecurityRequirement(name = "sessionAuth")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "Invalid request body or parameter. RFC 9457 Problem Detail; "
                + "validation failures include an errors array with field and message entries.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "401", description = "Authentication required.",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ProblemDetail.class)))
})
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping
    @Operation(summary = "Create a Ticket", description = "Any authenticated account may create a Ticket "
            + "with initial status OPEN. The requester is derived from the authenticated account.")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "Current session CSRF token from GET /api/auth/csrf.", schema = @Schema(type = "string"))
    @ApiResponse(responseCode = "403", description = "Missing or invalid CSRF token.",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "201", description = "Ticket created.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TicketResponse.class)),
            headers = @Header(name = "Location", description = "URL of the created Ticket.",
                    schema = @Schema(type = "string", format = "uri")))
    public ResponseEntity<TicketResponse> createTicket(@Valid @RequestBody CreateTicketRequest request,
            Principal principal) {
        Ticket ticket = ticketService.createTicket(request.title(), request.description(), request.priority(),
                principal.getName());
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}")
                .buildAndExpand(ticket.getId())
                .toUri();
        return ResponseEntity.created(location).body(TicketResponse.from(ticket));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a Ticket", description = "REQUESTER may retrieve only their own Tickets. "
            + "AGENT and ADMIN may retrieve all Tickets, including historical Tickets without a requester. "
            + "Tickets outside REQUESTER visibility return the same 404 as nonexistent Tickets.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ticket found.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "404", description = "Ticket not found or outside the requester's visibility. "
                    + "RFC 9457 Problem Detail.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    public TicketResponse getTicket(@Parameter(description = "Ticket ID.", example = "42") @PathVariable Long id,
            Principal principal) {
        return TicketResponse.from(ticketService.getTicket(id, principal.getName()));
    }

    @PatchMapping("/{id}/status")
    @Parameter(name = "X-CSRF-TOKEN", in = ParameterIn.HEADER, required = true,
            description = "Current session CSRF token from GET /api/auth/csrf.", schema = @Schema(type = "string"))
    @Operation(summary = "Change Ticket status", description = "Requires AGENT or ADMIN. "
            + "Allowed transitions: OPEN → IN_PROGRESS; "
            + "IN_PROGRESS → RESOLVED; RESOLVED → IN_PROGRESS; RESOLVED → CLOSED. "
            + "CLOSED is terminal. All other transitions, including the current status, are rejected.")
    @ApiResponses({
            @ApiResponse(responseCode = "403", description = "Insufficient role, or missing or invalid CSRF token.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "200", description = "Ticket status changed.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = TicketResponse.class))),
            @ApiResponse(responseCode = "404", description = "Ticket not found. RFC 9457 Problem Detail.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Invalid ticket status transition. RFC 9457 Problem Detail.",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    public TicketResponse changeStatus(@Parameter(description = "Ticket ID.", example = "42") @PathVariable Long id,
            @Valid @RequestBody UpdateTicketStatusRequest request) {
        return TicketResponse.from(ticketService.changeStatus(id, request.status()));
    }

    @GetMapping
    @Operation(summary = "List Tickets", description = "REQUESTER receives only their own Tickets. "
            + "AGENT and ADMIN may view all Tickets, including historical Tickets without a requester. "
            + "Status, priority and text search criteria combine using AND. "
            + "Filtering precedes pagination. Sorting uses ID as a secondary key in the selected direction.")
    @ApiResponse(responseCode = "200", description = "Matching Tickets with page metadata.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TicketPageResponse.class)))
    public TicketPageResponse listTickets(
            @Parameter(description = "Zero-based page number.")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Maximum number of Tickets per page.")
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size,
            @Parameter(description = "Optional, case-sensitive status filter.")
            @RequestParam(name = "status", required = false) TicketStatus status,
            @Parameter(description = "Optional, case-sensitive priority filter.")
            @RequestParam(name = "priority", required = false) TicketPriority priority,
            @Parameter(description = "Case-insensitive substring search in title OR description. "
                    + "Leading and trailing whitespace is trimmed. A supplied value must be nonblank "
                    + "and at most 100 characters before trimming.")
            @RequestParam(name = "q", required = false) @Size(max = 100)
            @Pattern(regexp = "(?s).*\\P{javaWhitespace}.*", message = "must not be blank") String q,
            @Parameter(description = "Primary sort field.", schema = @Schema(implementation = String.class,
                    allowableValues = {"createdAt", "updatedAt", "title"}, defaultValue = "createdAt"))
            @RequestParam(name = "sortBy", defaultValue = "createdAt") TicketSortField sortBy,
            @Parameter(description = "Direction for both the primary field and the ID tie-breaker.",
                    schema = @Schema(implementation = String.class,
                            allowableValues = {"asc", "desc"}, defaultValue = "desc"))
            @RequestParam(name = "direction", defaultValue = "desc") TicketSortDirection direction,
            Principal principal) {
        String query = q == null ? null : q.strip();
        return TicketPageResponse.from(ticketService.listTickets(page, size, status, priority, query, sortBy, direction,
                principal.getName()));
    }

    @InitBinder({"status", "priority", "sortBy", "direction", "q"})
    void validateSingleListingParameter(WebDataBinder binder, NativeWebRequest request)
            throws ServletRequestBindingException {
        String parameter = binder.getObjectName();
        String[] values = request.getParameterValues(parameter);
        // Scalar enum conversion can otherwise silently use the first repeated value.
        // Search blankness is validated by Bean Validation so it receives a field error.
        if (request.getParameterValues(parameter + "[]") != null
                || (values != null && (values.length != 1 || (!parameter.equals("q") && values[0].isBlank())))) {
            throw new ServletRequestBindingException("Each parameter must have one non-blank value.");
        }
    }

    @InitBinder("sortBy")
    void bindSortField(WebDataBinder binder) {
        binder.registerCustomEditor(TicketSortField.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                setValue(switch (text) {
                    case "createdAt" -> TicketSortField.CREATED_AT;
                    case "updatedAt" -> TicketSortField.UPDATED_AT;
                    case "title" -> TicketSortField.TITLE;
                    default -> throw new IllegalArgumentException("Invalid sort field.");
                });
            }
        });
    }

    @InitBinder("direction")
    void bindSortDirection(WebDataBinder binder) {
        binder.registerCustomEditor(TicketSortDirection.class, new PropertyEditorSupport() {
            @Override
            public void setAsText(String text) {
                setValue(switch (text) {
                    case "asc" -> TicketSortDirection.ASC;
                    case "desc" -> TicketSortDirection.DESC;
                    default -> throw new IllegalArgumentException("Invalid sort direction.");
                });
            }
        });
    }

}
