package io.github.panteliszara.issunexa.ticket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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

}
