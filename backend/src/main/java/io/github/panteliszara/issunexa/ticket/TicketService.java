package io.github.panteliszara.issunexa.ticket;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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

    @Transactional
    public Ticket changeStatus(Long id, TicketStatus targetStatus) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException(id));
        ticket.changeStatus(targetStatus);
        return ticket;
    }

    @Transactional(readOnly = true)
    public Page<Ticket> listTickets(int page, int size, TicketStatus status, TicketPriority priority, String query,
            TicketSortField sortField, TicketSortDirection sortDirection) {
        Sort.Direction direction = switch (sortDirection) {
            case ASC -> Sort.Direction.ASC;
            case DESC -> Sort.Direction.DESC;
        };
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(direction, sortField.getPropertyName(), "id"));
        Specification<Ticket> specification = Specification.unrestricted();
        if (status != null) {
            specification = specification.and(TicketSpecifications.hasStatus(status));
        }
        if (priority != null) {
            specification = specification.and(TicketSpecifications.hasPriority(priority));
        }
        if (query != null) {
            specification = specification.and(TicketSpecifications.containsText(query));
        }
        return ticketRepository.findAll(specification, pageRequest);
    }

}
