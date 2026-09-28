package io.github.panteliszara.issunexa.ticket;

import io.github.panteliszara.issunexa.ticket.history.TicketHistoryEntry;
import io.github.panteliszara.issunexa.ticket.history.TicketHistoryRepository;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final UserAccountRepository userAccountRepository;
    private final TicketHistoryRepository ticketHistoryRepository;

    public TicketService(TicketRepository ticketRepository, UserAccountRepository userAccountRepository,
            TicketHistoryRepository ticketHistoryRepository) {
        this.ticketRepository = ticketRepository;
        this.userAccountRepository = userAccountRepository;
        this.ticketHistoryRepository = ticketHistoryRepository;
    }

    @Transactional
    public Ticket createTicket(String title, String description, TicketPriority priority, TicketCategory category,
            String requesterEmail) {
        UserAccount requester = resolveCurrentAccount(requesterEmail);
        Ticket ticket = ticketRepository.save(new Ticket(title, description, TicketStatus.OPEN, priority, category, requester));
        ticketHistoryRepository.save(TicketHistoryEntry.ticketCreated(ticket, requester));
        return ticket;
    }

    @Transactional(readOnly = true)
    public Ticket getTicket(Long id, String actorEmail) {
        UserAccount actor = resolveCurrentAccount(actorEmail);
        Optional<Ticket> ticket = switch (actor.getRole()) {
            case REQUESTER -> ticketRepository.findOne(TicketSpecifications.hasId(id)
                    .and(TicketSpecifications.requestedBy(actor.getId())));
            case AGENT, ADMIN -> ticketRepository.findById(id);
        };
        return ticket.orElseThrow(() -> new TicketNotFoundException(id));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('AGENT', 'ADMIN')")
    public Ticket claimTicket(Long ticketId, String actorEmail) {
        UserAccount actor = resolveCurrentAccount(actorEmail);
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new TicketNotFoundException(ticketId));
        ticket.claim(actor);
        ticketHistoryRepository.save(TicketHistoryEntry.assigneeClaimed(ticket, actor, ticket.getAssignee()));
        return ticket;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('AGENT', 'ADMIN')")
    public Ticket changeStatus(Long id, TicketStatus targetStatus, String actorEmail) {
        UserAccount actor = resolveCurrentAccount(actorEmail);
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException(id));
        TicketStatus previousStatus = ticket.getStatus();
        ticket.changeStatus(targetStatus);
        ticketHistoryRepository.save(TicketHistoryEntry.statusChanged(ticket, actor, previousStatus, ticket.getStatus()));
        return ticket;
    }

    @Transactional(readOnly = true)
    public Page<Ticket> listTickets(int page, int size, TicketStatus status, TicketPriority priority, TicketCategory category, String query,
            TicketSortField sortField, TicketSortDirection sortDirection, String actorEmail) {
        UserAccount actor = resolveCurrentAccount(actorEmail);
        Sort.Direction direction = switch (sortDirection) {
            case ASC -> Sort.Direction.ASC;
            case DESC -> Sort.Direction.DESC;
        };
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(direction, sortField.getPropertyName(), "id"));
        Specification<Ticket> specification = switch (actor.getRole()) {
            case REQUESTER -> TicketSpecifications.requestedBy(actor.getId());
            case AGENT, ADMIN -> Specification.unrestricted();
        };
        if (status != null) {
            specification = specification.and(TicketSpecifications.hasStatus(status));
        }
        if (priority != null) {
            specification = specification.and(TicketSpecifications.hasPriority(priority));
        }
        if (category != null) {
            specification = specification.and(TicketSpecifications.hasCategory(category));
        }
        if (query != null) {
            specification = specification.and(TicketSpecifications.containsText(query));
        }
        return ticketRepository.findAll(specification, pageRequest);
    }

    private UserAccount resolveCurrentAccount(String email) {
        return userAccountRepository.findByEmail(UserAccount.normalizeEmail(email))
                .orElseThrow(() -> new IllegalStateException("Authenticated user account could not be resolved."));
    }

}
