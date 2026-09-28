package io.github.panteliszara.issunexa.ticket;

import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketTests {

    private final UserAccount requester = new UserAccount("alice@example.com", "Alice",
            "{bcrypt}encoded-test-value", UserRole.REQUESTER);

    @ParameterizedTest
    @EnumSource(TicketCategory.class)
    void requiresExplicitCategoryAndPreservesItThroughLifecycle(TicketCategory category) {
        Ticket ticket = new Ticket("Printer", "No connection", TicketStatus.OPEN, TicketPriority.HIGH, category, requester);
        assertThat(ticket.getCategory()).isEqualTo(category);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(ticket.getRequester()).isSameAs(requester);
        assertThat(ticket.getAssignee()).isNull();
        assertThat(ticket.getVersion()).isZero();

        ticket.changeStatus(TicketStatus.IN_PROGRESS);
        assertThat(ticket.getCategory()).isEqualTo(category);
        UserAccount agent = new UserAccount("agent@example.com", "Agent", "encoded-test-value", UserRole.AGENT);
        ticket.claim(agent);
        assertThat(ticket.getCategory()).isEqualTo(category);
        assertThat(ticket.getAssignee()).isSameAs(agent);
        assertThat(ticket.getRequester()).isSameAs(requester);
    }

    @Test
    void rejectsNullCategory() {
        assertThatThrownBy(() -> new Ticket("Printer", "No connection", TicketStatus.OPEN, TicketPriority.HIGH, null, requester))
                .isInstanceOf(NullPointerException.class).hasMessage("category must not be null");
    }

    @Test
    void retainsRequesterDuringConstruction() {
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester);

        assertThat(ticket.getRequester()).isSameAs(requester);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
    }

    @Test
    void rejectsNullRequesterForNewTickets() {
        assertThatThrownBy(() -> new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("requester must not be null");
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"AGENT", "ADMIN"})
    void claimsUnassignedTicketWithoutChangingRequesterOrStatus(UserRole role) {
        Ticket ticket = new Ticket("Printer offline", "No connection", TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester);
        UserAccount staff = new UserAccount("staff@example.com", "Staff", "encoded-test-value", role);
        assertThat(ticket.getAssignee()).isNull();

        ticket.claim(staff);

        assertThat(ticket.getAssignee()).isSameAs(staff);
        assertThat(ticket.getRequester()).isSameAs(requester);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(ticket.getVersion()).isZero();
    }

    @Test
    void rejectsRequesterAsAssigneeWithoutMutation() {
        Ticket ticket = new Ticket("Printer offline", "No connection", TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester);

        assertThatThrownBy(() -> ticket.claim(requester))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("assignee must be an AGENT or ADMIN");
        assertThat(ticket.getAssignee()).isNull();
        assertThat(ticket.getRequester()).isSameAs(requester);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
    }

    @Test
    void rejectsNullAssigneeWithoutMutation() {
        Ticket ticket = new Ticket("Printer offline", "No connection", TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester);

        assertThatThrownBy(() -> ticket.claim(null))
                .isInstanceOf(NullPointerException.class).hasMessage("assignee must not be null");
        assertThat(ticket.getAssignee()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsRepeatedClaimBySameOrDifferentStaff(boolean sameStaff) {
        Ticket ticket = new Ticket("Printer offline", "No connection", TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester);
        UserAccount agent = new UserAccount("agent@example.com", "Agent", "encoded-test-value", UserRole.AGENT);
        UserAccount next = sameStaff ? agent
                : new UserAccount("admin@example.com", "Admin", "encoded-test-value", UserRole.ADMIN);
        ticket.claim(agent);

        assertThatThrownBy(() -> ticket.claim(next))
                .isInstanceOf(TicketAlreadyAssignedException.class)
                .hasMessage("The ticket already has an assignee.");
        assertThat(ticket.getAssignee()).isSameAs(agent);
        assertThat(ticket.getRequester()).isSameAs(requester);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
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
                currentStatus, TicketPriority.HIGH, TicketCategory.INCIDENT, requester);

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
                currentStatus, TicketPriority.HIGH, TicketCategory.INCIDENT, requester);

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
                TicketStatus.OPEN, TicketPriority.HIGH, TicketCategory.INCIDENT, requester);

        assertThatThrownBy(() -> ticket.changeStatus(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("targetStatus must not be null");
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.OPEN);
    }

    @Test
    void rejectsNullInitialStatus() {
        assertThatThrownBy(() -> new Ticket("Printer offline", "The office printer is unreachable.",
                null, TicketPriority.HIGH, TicketCategory.INCIDENT, requester))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("status must not be null");
    }

}
