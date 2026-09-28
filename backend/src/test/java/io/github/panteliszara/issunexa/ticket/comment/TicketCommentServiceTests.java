package io.github.panteliszara.issunexa.ticket.comment;

import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketNotFoundException;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketService;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountRepository;
import io.github.panteliszara.issunexa.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketCommentServiceTests {

    private static final String EMAIL = "alice@example.com";

    @Mock
    private TicketCommentRepository ticketCommentRepository;
    @Mock
    private UserAccountRepository userAccountRepository;
    @Mock
    private TicketService ticketService;
    @InjectMocks
    private TicketCommentService service;

    private final UserAccount author = new UserAccount(EMAIL, "Alice", "test-hash", UserRole.REQUESTER);
    private final Ticket ticket = new Ticket("Printer offline", "No connection", TicketStatus.OPEN,
            TicketPriority.MEDIUM, author);

    @Test
    void checksVisibilityBeforeResolvingCanonicalActorAndSavingNormalizedComment() {
        String principal = "  ALICE@EXAMPLE.COM ";
        when(ticketService.getTicket(42L, principal)).thenReturn(ticket);
        when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.of(author));
        TicketComment saved = new TicketComment(ticket, author, "First  line\nSecond line");
        when(ticketCommentRepository.save(any(TicketComment.class))).thenReturn(saved);

        TicketComment result = service.addComment(42L, principal, " \nFirst  line\nSecond line \t");

        assertThat(result).isSameAs(saved);
        var calls = inOrder(ticketService, userAccountRepository, ticketCommentRepository);
        calls.verify(ticketService).getTicket(42L, principal);
        calls.verify(userAccountRepository).findByEmail(EMAIL);
        ArgumentCaptor<TicketComment> captor = ArgumentCaptor.forClass(TicketComment.class);
        calls.verify(ticketCommentRepository).save(captor.capture());
        assertThat(captor.getValue().getTicket()).isSameAs(ticket);
        assertThat(captor.getValue().getAuthor()).isSameAs(author);
        assertThat(captor.getValue().getBody()).isEqualTo("First  line\nSecond line");
        calls.verifyNoMoreInteractions();
    }

    @Test
    void hiddenOrMissingTicketPreventsAccountLookupAndCommentPersistence() {
        TicketNotFoundException failure = new TicketNotFoundException(42L);
        when(ticketService.getTicket(42L, EMAIL)).thenThrow(failure);

        assertThatThrownBy(() -> service.addComment(42L, EMAIL, "Comment")).isSameAs(failure);

        verifyNoInteractions(userAccountRepository, ticketCommentRepository);
    }

    @Test
    void unresolvedActorFailsSafelyWithoutSaving() {
        when(ticketService.getTicket(42L, EMAIL)).thenReturn(ticket);
        when(userAccountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addComment(42L, EMAIL, "Comment"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Authenticated user account could not be resolved.");

        verifyNoInteractions(ticketCommentRepository);
    }

    @Test
    void verifiesVisibilityBeforeQueryingRequestedPageInFixedChronologicalOrder() {
        when(ticketService.getTicket(42L, EMAIL)).thenReturn(ticket);
        Page<TicketComment> page = new PageImpl<>(List.of(new TicketComment(ticket, author, "Comment")),
                PageRequest.of(2, 3), 7);
        when(ticketCommentRepository.findByTicketId(eq(42L), any(Pageable.class))).thenReturn(page);

        assertThat(service.listComments(42L, EMAIL, 2, 3)).isSameAs(page);

        var calls = inOrder(ticketService, ticketCommentRepository);
        calls.verify(ticketService).getTicket(42L, EMAIL);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        calls.verify(ticketCommentRepository).findByTicketId(eq(42L), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(3);
        assertThat(captor.getValue().getSort()).containsExactly(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
        verifyNoInteractions(userAccountRepository);
    }

    @Test
    void hiddenOrMissingTicketPreventsCommentListing() {
        TicketNotFoundException failure = new TicketNotFoundException(42L);
        when(ticketService.getTicket(42L, EMAIL)).thenThrow(failure);

        assertThatThrownBy(() -> service.listComments(42L, EMAIL, 0, 20)).isSameAs(failure);

        verifyNoInteractions(userAccountRepository, ticketCommentRepository);
    }

}
