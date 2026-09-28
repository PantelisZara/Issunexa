package io.github.panteliszara.issunexa.ticket.history.api;

import io.github.panteliszara.issunexa.ticket.history.TicketHistoryEntry;
import org.springframework.data.domain.Page;

import java.util.List;

public record TicketHistoryPageResponse(
        List<TicketHistoryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public TicketHistoryPageResponse {
        content = List.copyOf(content);
    }

    public static TicketHistoryPageResponse from(Page<TicketHistoryEntry> history) {
        return new TicketHistoryPageResponse(history.getContent().stream().map(TicketHistoryResponse::from).toList(),
                history.getNumber(), history.getSize(), history.getTotalElements(), history.getTotalPages(),
                history.isFirst(), history.isLast());
    }

}
