package io.github.panteliszara.issunexa.ticket.api;

import io.github.panteliszara.issunexa.user.UserAccount;

public record TicketAssigneeResponse(Long id, String displayName) {

    public static TicketAssigneeResponse from(UserAccount assignee) {
        return new TicketAssigneeResponse(assignee.getId(), assignee.getDisplayName());
    }

}
