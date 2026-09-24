package io.github.panteliszara.issunexa.ticket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    void changesStatusAndReturnsLoadedTicketWithoutSavingAgain() {
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.OPEN, TicketPriority.HIGH);
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));

        Ticket result = ticketService.changeStatus(42L, TicketStatus.IN_PROGRESS);

        assertThat(result).isSameAs(ticket);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        verify(ticketRepository).findById(42L);
        verifyNoMoreInteractions(ticketRepository);
    }

    @Test
    void rejectsStatusChangeWhenTicketIsMissing() {
        when(ticketRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.changeStatus(99L, TicketStatus.IN_PROGRESS))
                .isInstanceOf(TicketNotFoundException.class)
                .hasMessageContaining("99");
        verify(ticketRepository).findById(99L);
        verifyNoMoreInteractions(ticketRepository);
    }

    @Test
    void propagatesInvalidStatusTransition() {
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.OPEN, TicketPriority.HIGH);
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.changeStatus(42L, TicketStatus.CLOSED))
                .isInstanceOf(InvalidTicketStatusTransitionException.class);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
        verify(ticketRepository).findById(42L);
        verifyNoMoreInteractions(ticketRepository);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("sortOrders")
    void listsTicketsWithPaginationAndDeterministicOrder(
            TicketSortField sortField, TicketSortDirection direction, Sort.Order primaryOrder, Sort.Order idOrder) {
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.OPEN, TicketPriority.HIGH);
        Page<Ticket> repositoryResult = new PageImpl<>(List.of(ticket), PageRequest.of(2, 10), 21);
        when(ticketRepository.findAll(ArgumentMatchers.<Specification<Ticket>>any(), any(Pageable.class)))
                .thenReturn(repositoryResult);

        Page<Ticket> result = ticketService.listTickets(2, 10, null, null, null, sortField, direction);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(ticketRepository).findAll(ArgumentMatchers.<Specification<Ticket>>notNull(), pageableCaptor.capture());
        assertListingPageable(pageableCaptor.getValue(), primaryOrder, idOrder);
        assertThat(result).isSameAs(repositoryResult);
        verifyNoMoreInteractions(ticketRepository);
    }

    @Test
    void listsTicketsWithAllSearchCriteriaAndReturnsRepositoryPage() {
        Ticket ticket = new Ticket("Login failure", "The account is unreachable.",
                TicketStatus.IN_PROGRESS, TicketPriority.URGENT);
        Page<Ticket> repositoryResult = new PageImpl<>(List.of(ticket), PageRequest.of(2, 10), 21);
        when(ticketRepository.findAll(ArgumentMatchers.<Specification<Ticket>>any(), any(Pageable.class)))
                .thenReturn(repositoryResult);

        Page<Ticket> result = ticketService.listTickets(2, 10, TicketStatus.IN_PROGRESS, TicketPriority.URGENT, "login",
                TicketSortField.UPDATED_AT, TicketSortDirection.ASC);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(ticketRepository).findAll(ArgumentMatchers.<Specification<Ticket>>notNull(), pageableCaptor.capture());
        assertListingPageable(pageableCaptor.getValue(), Sort.Order.asc("updatedAt"), Sort.Order.asc("id"));
        assertThat(result).isSameAs(repositoryResult);
        verifyNoMoreInteractions(ticketRepository);
    }

    static Stream<Arguments> sortOrders() {
        return Stream.of(
                Arguments.of(TicketSortField.CREATED_AT, TicketSortDirection.DESC,
                        Sort.Order.desc("createdAt"), Sort.Order.desc("id")),
                Arguments.of(TicketSortField.CREATED_AT, TicketSortDirection.ASC,
                        Sort.Order.asc("createdAt"), Sort.Order.asc("id")),
                Arguments.of(TicketSortField.UPDATED_AT, TicketSortDirection.DESC,
                        Sort.Order.desc("updatedAt"), Sort.Order.desc("id")),
                Arguments.of(TicketSortField.UPDATED_AT, TicketSortDirection.ASC,
                        Sort.Order.asc("updatedAt"), Sort.Order.asc("id")),
                Arguments.of(TicketSortField.TITLE, TicketSortDirection.ASC,
                        Sort.Order.asc("title"), Sort.Order.asc("id")),
                Arguments.of(TicketSortField.TITLE, TicketSortDirection.DESC,
                        Sort.Order.desc("title"), Sort.Order.desc("id"))
        );
    }

    private static void assertListingPageable(Pageable pageable, Sort.Order primaryOrder, Sort.Order idOrder) {
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort()).containsExactly(primaryOrder, idOrder);
    }

}
