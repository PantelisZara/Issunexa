package io.github.panteliszara.issunexa.ticket.history.api;

import io.github.panteliszara.issunexa.user.UserAccount;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Public lifecycle actor summary containing only account ID and display name.")
public record TicketHistoryActorResponse(Long id, String displayName) {

    public static TicketHistoryActorResponse from(UserAccount actor) {
        return new TicketHistoryActorResponse(actor.getId(), actor.getDisplayName());
    }

}
