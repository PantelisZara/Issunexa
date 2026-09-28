package io.github.panteliszara.issunexa.ticket.history;

import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketHistoryEntryTests {

    private final UserAccount actor = new UserAccount("actor@example.com", "Actor", "test-hash", UserRole.AGENT);
    private final UserAccount assignee = new UserAccount("assignee@example.com", "Assignee", "test-hash", UserRole.ADMIN);
    private final Ticket ticket = new Ticket("Printer", "Offline", TicketStatus.OPEN, TicketPriority.HIGH, actor);

    @Test
    void createsStructuredCreationEntry() {
        TicketHistoryEntry entry = TicketHistoryEntry.ticketCreated(ticket, actor);

        assertThat(entry.getType()).isEqualTo(TicketHistoryType.TICKET_CREATED);
        assertThat(entry.getTicket()).isSameAs(ticket);
        assertThat(entry.getActor()).isSameAs(actor);
        assertThat(entry.getPreviousStatus()).isNull();
        assertThat(entry.getNewStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(entry.getAssignee()).isNull();
        assertThat(entry.getId()).isNull();
        assertThat(entry.getCreatedAt()).isNull();
    }

    @Test
    void retainsBeforeAndAfterStatuses() {
        TicketHistoryEntry entry = TicketHistoryEntry.statusChanged(ticket, actor, TicketStatus.RESOLVED, TicketStatus.CLOSED);

        assertThat(entry.getType()).isEqualTo(TicketHistoryType.STATUS_CHANGED);
        assertThat(entry.getTicket()).isSameAs(ticket);
        assertThat(entry.getActor()).isSameAs(actor);
        assertThat(entry.getPreviousStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(entry.getNewStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(entry.getAssignee()).isNull();
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void rejectsEqualStatuses(TicketStatus status) {
        assertThatThrownBy(() -> TicketHistoryEntry.statusChanged(ticket, actor, status, status))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("previousStatus and newStatus must differ");
    }

    @Test
    void rejectsMissingStatuses() {
        assertThatNullPointerException().isThrownBy(() -> TicketHistoryEntry.statusChanged(ticket, actor, null, TicketStatus.OPEN));
        assertThatNullPointerException().isThrownBy(() -> TicketHistoryEntry.statusChanged(ticket, actor, TicketStatus.OPEN, null));
    }

    @Test
    void recordsAssigneeExplicitlyRatherThanAssumingItIsActor() {
        TicketHistoryEntry entry = TicketHistoryEntry.assigneeClaimed(ticket, actor, assignee);

        assertThat(entry.getType()).isEqualTo(TicketHistoryType.ASSIGNEE_CLAIMED);
        assertThat(entry.getTicket()).isSameAs(ticket);
        assertThat(entry.getActor()).isSameAs(actor);
        assertThat(entry.getAssignee()).isSameAs(assignee);
        assertThat(entry.getPreviousStatus()).isNull();
        assertThat(entry.getNewStatus()).isNull();
        assertThatNullPointerException().isThrownBy(() -> TicketHistoryEntry.assigneeClaimed(ticket, actor, null));
    }

    @ParameterizedTest
    @EnumSource(TicketHistoryType.class)
    void allFactoriesRequireTicketAndActor(TicketHistoryType type) {
        assertThatNullPointerException().isThrownBy(() -> entry(type, null, actor)).withMessage("ticket must not be null");
        assertThatNullPointerException().isThrownBy(() -> entry(type, ticket, null)).withMessage("actor must not be null");
    }

    private TicketHistoryEntry entry(TicketHistoryType type, Ticket ticket, UserAccount actor) {
        return switch (type) {
            case TICKET_CREATED -> TicketHistoryEntry.ticketCreated(ticket, actor);
            case STATUS_CHANGED -> TicketHistoryEntry.statusChanged(ticket, actor, TicketStatus.OPEN, TicketStatus.IN_PROGRESS);
            case ASSIGNEE_CLAIMED -> TicketHistoryEntry.assigneeClaimed(ticket, actor, assignee);
        };
    }

}
