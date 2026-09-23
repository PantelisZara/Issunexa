package io.github.panteliszara.issunexa.ticket.api;

import io.github.panteliszara.issunexa.ticket.Ticket;
import org.springframework.data.domain.Page;

import java.util.List;

public record TicketPageResponse(
        List<TicketResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public TicketPageResponse {
        content = List.copyOf(content);
    }

    public static TicketPageResponse from(Page<Ticket> tickets) {
        return new TicketPageResponse(tickets.getContent().stream().map(TicketResponse::from).toList(),
                tickets.getNumber(), tickets.getSize(), tickets.getTotalElements(), tickets.getTotalPages(),
                tickets.isFirst(), tickets.isLast());
    }

}
