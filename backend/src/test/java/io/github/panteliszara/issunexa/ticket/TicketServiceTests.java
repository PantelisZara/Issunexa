package io.github.panteliszara.issunexa.ticket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketServiceTests {

    @Mock
    private TicketRepository ticketRepository;

    private TicketService ticketService;

    @BeforeEach
    void setUp() {
        ticketService = new TicketService(ticketRepository);
    }

    @Test
    void createsOpenTicketAndReturnsRepositoryResult() {
        String title = "Printer offline";
        String description = "The office printer is unreachable.\nIt shows a network error.";
        TicketPriority priority = TicketPriority.HIGH;
        Ticket persistedTicket = new Ticket(title, description, TicketStatus.OPEN, priority);
        when(ticketRepository.save(any(Ticket.class))).thenReturn(persistedTicket);

        Ticket result = ticketService.createTicket(title, description, priority);

        ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository).save(ticketCaptor.capture());
        Ticket submittedTicket = ticketCaptor.getValue();
        assertThat(submittedTicket.getTitle()).isEqualTo(title);
        assertThat(submittedTicket.getDescription()).isEqualTo(description);
        assertThat(submittedTicket.getPriority()).isEqualTo(priority);
        assertThat(submittedTicket.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(result).isSameAs(persistedTicket);
    }

    @Test
    void returnsTicketWhenFound() {
        Long id = 42L;
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.IN_PROGRESS, TicketPriority.HIGH);
        when(ticketRepository.findById(id)).thenReturn(Optional.of(ticket));

        Ticket result = ticketService.getTicket(id);

        assertThat(result).isSameAs(ticket);
        verify(ticketRepository).findById(id);
    }

    @Test
    void throwsNotFoundExceptionWithMissingId() {
        Long missingId = 99L;
        when(ticketRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.getTicket(missingId))
                .isInstanceOf(TicketNotFoundException.class)
                .hasMessageContaining(missingId.toString());
        verify(ticketRepository).findById(missingId);
    }

    @Test
    void listsTicketsWithPaginationAndDeterministicOrder() {
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.OPEN, TicketPriority.HIGH);
        Page<Ticket> repositoryResult = new PageImpl<>(List.of(ticket), PageRequest.of(2, 10), 21);
        when(ticketRepository.findAll(any(Pageable.class))).thenReturn(repositoryResult);

        Page<Ticket> result = ticketService.listTickets(2, 10, null, null);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(ticketRepository).findAll(pageableCaptor.capture());
        assertListingPageable(pageableCaptor.getValue());
        assertThat(result).isSameAs(repositoryResult);
        verifyNoMoreInteractions(ticketRepository);
    }

    @Test
    void listsTicketsByStatusWithPaginationAndDeterministicOrder() {
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.IN_PROGRESS, TicketPriority.HIGH);
        Page<Ticket> repositoryResult = new PageImpl<>(List.of(ticket), PageRequest.of(2, 10), 21);
        when(ticketRepository.findAllByStatus(eq(TicketStatus.IN_PROGRESS), any(Pageable.class)))
                .thenReturn(repositoryResult);

        Page<Ticket> result = ticketService.listTickets(2, 10, TicketStatus.IN_PROGRESS, null);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(ticketRepository).findAllByStatus(eq(TicketStatus.IN_PROGRESS), pageableCaptor.capture());
        assertListingPageable(pageableCaptor.getValue());
        assertThat(result).isSameAs(repositoryResult);
        verifyNoMoreInteractions(ticketRepository);
    }

    @Test
    void listsTicketsByPriorityWithPaginationAndDeterministicOrder() {
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.OPEN, TicketPriority.HIGH);
        Page<Ticket> repositoryResult = new PageImpl<>(List.of(ticket), PageRequest.of(2, 10), 21);
        when(ticketRepository.findAllByPriority(eq(TicketPriority.HIGH), any(Pageable.class)))
                .thenReturn(repositoryResult);

        Page<Ticket> result = ticketService.listTickets(2, 10, null, TicketPriority.HIGH);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(ticketRepository).findAllByPriority(eq(TicketPriority.HIGH), pageableCaptor.capture());
        assertListingPageable(pageableCaptor.getValue());
        assertThat(result).isSameAs(repositoryResult);
        verifyNoMoreInteractions(ticketRepository);
    }

    @Test
    void listsTicketsByStatusAndPriorityWithPaginationAndDeterministicOrder() {
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.IN_PROGRESS, TicketPriority.URGENT);
        Page<Ticket> repositoryResult = new PageImpl<>(List.of(ticket), PageRequest.of(2, 10), 21);
        when(ticketRepository.findAllByStatusAndPriority(
                eq(TicketStatus.IN_PROGRESS), eq(TicketPriority.URGENT), any(Pageable.class)))
                .thenReturn(repositoryResult);

        Page<Ticket> result = ticketService.listTickets(2, 10, TicketStatus.IN_PROGRESS, TicketPriority.URGENT);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(ticketRepository).findAllByStatusAndPriority(
                eq(TicketStatus.IN_PROGRESS), eq(TicketPriority.URGENT), pageableCaptor.capture());
        assertListingPageable(pageableCaptor.getValue());
        assertThat(result).isSameAs(repositoryResult);
        verifyNoMoreInteractions(ticketRepository);
    }

    private static void assertListingPageable(Pageable pageable) {
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort()).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

}
