package io.github.panteliszara.issunexa.ticket.comment;

import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketService;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketCommentService {

    private final TicketCommentRepository ticketCommentRepository;
    private final UserAccountRepository userAccountRepository;
    private final TicketService ticketService;

    public TicketCommentService(TicketCommentRepository ticketCommentRepository,
            UserAccountRepository userAccountRepository, TicketService ticketService) {
        this.ticketCommentRepository = ticketCommentRepository;
        this.userAccountRepository = userAccountRepository;
        this.ticketService = ticketService;
    }

    @Transactional
    public TicketComment addComment(Long ticketId, String actorEmail, String body) {
        Ticket ticket = ticketService.getTicket(ticketId, actorEmail);
        UserAccount author = userAccountRepository.findByEmail(UserAccount.normalizeEmail(actorEmail))
                .orElseThrow(() -> new IllegalStateException("Authenticated user account could not be resolved."));
        return ticketCommentRepository.save(new TicketComment(ticket, author, body));
    }

    @Transactional(readOnly = true)
    public Page<TicketComment> listComments(Long ticketId, String actorEmail, int page, int size) {
        ticketService.getTicket(ticketId, actorEmail);
        return ticketCommentRepository.findByTicketId(ticketId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt", "id")));
    }

}
