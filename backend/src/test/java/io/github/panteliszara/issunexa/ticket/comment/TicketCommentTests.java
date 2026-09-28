package io.github.panteliszara.issunexa.ticket.comment;

import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketCategory;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketCommentTests {

    private final UserAccount author = new UserAccount("alice@example.com", "Alice", "test-hash", UserRole.REQUESTER);
    private final Ticket ticket = new Ticket("Printer offline", "No connection", TicketStatus.OPEN,
            TicketPriority.MEDIUM, TicketCategory.INCIDENT, author);

    @Test
    void retainsTicketAndAuthorWhileStrippingOnlyOuterWhitespace() {
        TicketComment comment = new TicketComment(ticket, author, " \t\n First  line\n\nSecond\tline \n");

        assertThat(comment.getTicket()).isSameAs(ticket);
        assertThat(comment.getAuthor()).isSameAs(author);
        assertThat(comment.getBody()).isEqualTo("First  line\n\nSecond\tline");
        assertThat(comment.getId()).isNull();
        assertThat(comment.getCreatedAt()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t\r ", "\u2003"})
    void rejectsMissingOrBlankBody(String body) {
        assertThatThrownBy(() -> new TicketComment(ticket, author, body))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("body must not be blank");
    }

    @Test
    void acceptsMaximumLengthAndRejectsOversizeBeforeTrimming() {
        assertThat(new TicketComment(ticket, author, "x".repeat(4000)).getBody()).hasSize(4000);
        assertThatThrownBy(() -> new TicketComment(ticket, author, "x".repeat(4001)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("body must not exceed 4000 characters");
        assertThatThrownBy(() -> new TicketComment(ticket, author, " " + "x".repeat(4000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullTicket() {
        assertThatNullPointerException().isThrownBy(() -> new TicketComment(null, author, "Comment"))
                .withMessage("ticket must not be null");
    }

    @Test
    void rejectsNullAuthor() {
        assertThatNullPointerException().isThrownBy(() -> new TicketComment(ticket, null, "Comment"))
                .withMessage("author must not be null");
    }

}
