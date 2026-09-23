package io.github.panteliszara.issunexa.ticket.api;

import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketService;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

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

    @GetMapping
    public TicketPageResponse listTickets(
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(name = "status", required = false) TicketStatus status,
            @RequestParam(name = "priority", required = false) TicketPriority priority) {
        return TicketPageResponse.from(ticketService.listTickets(page, size, status, priority));
    }

    @InitBinder({"status", "priority"})
    void validateSingleFilterValue(WebDataBinder binder, NativeWebRequest request)
            throws ServletRequestBindingException {
        String[] values = request.getParameterValues(binder.getObjectName());
        // Scalar enum conversion can otherwise silently use the first repeated value.
        if (request.getParameterValues(binder.getObjectName() + "[]") != null
                || (values != null && (values.length != 1 || values[0].isBlank()))) {
            throw new ServletRequestBindingException("Each filter must have one non-blank value.");
        }
    }

}
