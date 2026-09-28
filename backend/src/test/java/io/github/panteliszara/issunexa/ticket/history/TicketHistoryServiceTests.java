package io.github.panteliszara.issunexa.ticket.history;

import io.github.panteliszara.issunexa.ticket.TicketNotFoundException;
import io.github.panteliszara.issunexa.ticket.TicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketHistoryServiceTests {

    @Mock
    private TicketHistoryRepository repository;
    @Mock
    private TicketService ticketService;
    @InjectMocks
    private TicketHistoryService service;

    @Test
    void verifiesVisibilityBeforeQueryingRequestedPageWithFixedNewestFirstOrder() {
        Page<TicketHistoryEntry> expected = Page.empty();
        when(repository.findByTicketId(eq(42L), any(Pageable.class))).thenReturn(expected);

        assertThat(service.listHistory(42L, "actor@example.com", 2, 3)).isSameAs(expected);

        var calls = inOrder(ticketService, repository);
        calls.verify(ticketService).getTicket(42L, "actor@example.com");
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        calls.verify(repository).findByTicketId(eq(42L), page.capture());
        assertThat(page.getValue().getPageNumber()).isEqualTo(2);
        assertThat(page.getValue().getPageSize()).isEqualTo(3);
        assertThat(page.getValue().getSort()).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    @Test
    void invisibleOrMissingTicketPreventsHistoryQuery() {
        TicketNotFoundException failure = new TicketNotFoundException(42L);
        when(ticketService.getTicket(42L, "actor@example.com")).thenThrow(failure);

        assertThatThrownBy(() -> service.listHistory(42L, "actor@example.com", 0, 20)).isSameAs(failure);

        verifyNoInteractions(repository);
    }

}
