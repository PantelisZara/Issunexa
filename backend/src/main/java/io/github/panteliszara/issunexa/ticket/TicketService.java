package io.github.panteliszara.issunexa.ticket;

import io.github.panteliszara.issunexa.demo.DemoWorkspaceAccess;
import io.github.panteliszara.issunexa.ticket.history.TicketHistoryEntry;
import io.github.panteliszara.issunexa.ticket.history.TicketHistoryRepository;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class TicketService {

    private final DemoWorkspaceAccess demo;
    private final TicketRepository ticketRepository;
    private final UserAccountRepository userAccountRepository;
    private final TicketHistoryRepository ticketHistoryRepository;

    public TicketService(TicketRepository ticketRepository, UserAccountRepository userAccountRepository,
            TicketHistoryRepository ticketHistoryRepository, DemoWorkspaceAccess demo) {
        this.demo = demo;
        this.ticketRepository = ticketRepository;
        this.userAccountRepository = userAccountRepository;
        this.ticketHistoryRepository = ticketHistoryRepository;
    }

    @Transactional
    public Ticket createTicket(String title, String description, TicketPriority priority, TicketCategory category,
            String requesterEmail) {
        UserAccount requester = resolveCurrentAccount(requesterEmail);
        demo.beforeTicket(requester);
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
            case AGENT, ADMIN -> visibleStaffTicket(id, actor);
        };
        return ticket.orElseThrow(() -> new TicketNotFoundException(id));
    }

    @Transactional
    @PreAuthorize("hasAnyRole('AGENT', 'ADMIN')")
    public Ticket claimTicket(Long ticketId, String actorEmail) {
        UserAccount actor = resolveStaffAccount(actorEmail);
        Ticket ticket = visibleStaffTicket(ticketId, actor)
                .orElseThrow(() -> new TicketNotFoundException(ticketId));
        demo.beforeHistory(actor);
        ticket.claim(actor);
        ticketHistoryRepository.save(TicketHistoryEntry.assigneeClaimed(ticket, actor, ticket.getAssignee()));
        return ticket;
    }

    @Transactional
    @PreAuthorize("hasAnyRole('AGENT', 'ADMIN')")
    public Ticket changeStatus(Long id, TicketStatus targetStatus, String actorEmail) {
        UserAccount actor = resolveStaffAccount(actorEmail);
        Ticket ticket = visibleStaffTicket(id, actor)
                .orElseThrow(() -> new TicketNotFoundException(id));
        demo.beforeHistory(actor);
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
        if (actor.getDemoWorkspaceId() != null) {
            specification = specification.and(TicketSpecifications.inWorkspace(actor.getDemoWorkspaceId()));
        }
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

    private Optional<Ticket> visibleStaffTicket(Long id, UserAccount actor) {
        return actor.getDemoWorkspaceId() == null ? ticketRepository.findById(id)
                : ticketRepository.findOne(TicketSpecifications.hasId(id)
                        .and(TicketSpecifications.inWorkspace(actor.getDemoWorkspaceId())));
    }

    private UserAccount resolveStaffAccount(String email) {
        UserAccount actor = resolveCurrentAccount(email);
        // Session authorities reflect login time; the persisted role may have been revoked since then.
        return switch (actor.getRole()) {
            case AGENT, ADMIN -> actor;
            case REQUESTER -> throw new AccessDeniedException("Staff role required.");
        };
    }

    private UserAccount resolveCurrentAccount(String email) {
        UserAccount actor = userAccountRepository.findByEmail(UserAccount.normalizeEmail(email))
                .orElseThrow(() -> new IllegalStateException("Authenticated user account could not be resolved."));
        demo.validate(actor);
        return actor;
    }

}
