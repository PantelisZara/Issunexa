package io.github.panteliszara.issunexa.ticket.api;

import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketService;
import io.github.panteliszara.issunexa.ticket.TicketSortDirection;
import io.github.panteliszara.issunexa.ticket.TicketSortField;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping
    public ResponseEntity<TicketResponse> createTicket(@Valid @RequestBody CreateTicketRequest request) {
        Ticket ticket = ticketService.createTicket(request.title(), request.description(), request.priority());
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}")
                .buildAndExpand(ticket.getId())
                .toUri();
        return ResponseEntity.created(location).body(TicketResponse.from(ticket));
    }

    @GetMapping("/{id}")
    public TicketResponse getTicket(@PathVariable Long id) {
        return TicketResponse.from(ticketService.getTicket(id));
    }

    @PatchMapping("/{id}/status")
    public TicketResponse changeStatus(@PathVariable Long id, @Valid @RequestBody UpdateTicketStatusRequest request) {
        return TicketResponse.from(ticketService.changeStatus(id, request.status()));
    }

    @GetMapping
    public TicketPageResponse listTickets(
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(name = "status", required = false) TicketStatus status,
            @RequestParam(name = "priority", required = false) TicketPriority priority,
            @RequestParam(name = "q", required = false) @Size(max = 100)
            @Pattern(regexp = "(?s).*\\P{javaWhitespace}.*", message = "must not be blank") String q,
            @RequestParam(name = "sortBy", defaultValue = "createdAt") TicketSortField sortBy,
            @RequestParam(name = "direction", defaultValue = "desc") TicketSortDirection direction) {
        String query = q == null ? null : q.strip();
        return TicketPageResponse.from(ticketService.listTickets(page, size, status, priority, query, sortBy, direction));
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
