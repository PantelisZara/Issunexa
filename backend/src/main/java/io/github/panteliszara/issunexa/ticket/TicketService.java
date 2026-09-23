package io.github.panteliszara.issunexa.ticket;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;

    public TicketService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Transactional
    public Ticket createTicket(String title, String description, TicketPriority priority) {
        Ticket ticket = new Ticket(title, description, TicketStatus.OPEN, priority);
        return ticketRepository.save(ticket);
    }

    @Transactional(readOnly = true)
    public Ticket getTicket(Long id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException(id));
    }

}
