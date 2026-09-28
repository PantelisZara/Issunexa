package io.github.panteliszara.issunexa.ticket.history;

import io.github.panteliszara.issunexa.ticket.TicketService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketHistoryService {

    private final TicketHistoryRepository ticketHistoryRepository;
    private final TicketService ticketService;

    public TicketHistoryService(TicketHistoryRepository ticketHistoryRepository, TicketService ticketService) {
        this.ticketHistoryRepository = ticketHistoryRepository;
        this.ticketService = ticketService;
    }

    @Transactional(readOnly = true)
    public Page<TicketHistoryEntry> listHistory(Long ticketId, String actorEmail, int page, int size) {
        ticketService.getTicket(ticketId, actorEmail);
        return ticketHistoryRepository.findByTicketId(ticketId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }

}
