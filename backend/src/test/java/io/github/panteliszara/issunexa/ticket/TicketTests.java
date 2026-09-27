package io.github.panteliszara.issunexa.ticket;

import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketTests {

    private final UserAccount requester = new UserAccount("alice@example.com", "Alice",
            "{bcrypt}encoded-test-value", UserRole.REQUESTER);

    @Test
    void retainsRequesterDuringConstruction() {
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.OPEN, TicketPriority.HIGH, requester);

        assertThat(ticket.getRequester()).isSameAs(requester);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
    }

    @Test
    void rejectsNullRequesterForNewTickets() {
        assertThatThrownBy(() -> new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.OPEN, TicketPriority.HIGH, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("requester must not be null");
    }

    @ParameterizedTest(name = "allows {0} -> {1}")
    @CsvSource({
            "OPEN, IN_PROGRESS",
            "IN_PROGRESS, RESOLVED",
            "RESOLVED, IN_PROGRESS",
            "RESOLVED, CLOSED"
    })
    void allowsWorkflowTransitions(TicketStatus currentStatus, TicketStatus targetStatus) {
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                currentStatus, TicketPriority.HIGH, requester);

        ticket.changeStatus(targetStatus);

        assertThat(ticket.getStatus()).isEqualTo(targetStatus);
        assertThat(ticket.getTitle()).isEqualTo("Printer offline");
        assertThat(ticket.getDescription()).isEqualTo("The office printer is unreachable.");
        assertThat(ticket.getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(ticket.getRequester()).isSameAs(requester);
    }

    @ParameterizedTest(name = "rejects {0} -> {1}")
    @CsvSource({
            "OPEN, OPEN",
            "OPEN, RESOLVED",
            "OPEN, CLOSED",
            "IN_PROGRESS, OPEN",
            "IN_PROGRESS, IN_PROGRESS",
            "IN_PROGRESS, CLOSED",
            "RESOLVED, OPEN",
            "RESOLVED, RESOLVED",
            "CLOSED, OPEN",
            "CLOSED, IN_PROGRESS",
            "CLOSED, RESOLVED",
            "CLOSED, CLOSED"
    })
    void rejectsProhibitedTransitionsWithoutChangingStatus(TicketStatus currentStatus, TicketStatus targetStatus) {
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                currentStatus, TicketPriority.HIGH, requester);

        assertThatThrownBy(() -> ticket.changeStatus(targetStatus))
                .isInstanceOfSatisfying(InvalidTicketStatusTransitionException.class, exception -> {
                    assertThat(exception.getTicketId()).isNull();
                    assertThat(exception.getCurrentStatus()).isEqualTo(currentStatus);
                    assertThat(exception.getRequestedStatus()).isEqualTo(targetStatus);
                })
                .hasMessage("Ticket cannot transition from " + currentStatus + " to " + targetStatus + ".");
        assertThat(ticket.getStatus()).isEqualTo(currentStatus);
        assertThat(ticket.getRequester()).isSameAs(requester);
    }

    @Test
    void rejectsNullTargetWithoutChangingStatus() {
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.OPEN, TicketPriority.HIGH, requester);

        assertThatThrownBy(() -> ticket.changeStatus(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("targetStatus must not be null");
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
    }

    @Test
    void rejectsNullInitialStatus() {
        assertThatThrownBy(() -> new Ticket("Printer offline", "The office printer is unreachable.",
                null, TicketPriority.HIGH, requester))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("status must not be null");
    }

}
