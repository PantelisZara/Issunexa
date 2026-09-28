package io.github.panteliszara.issunexa.ticket.comment.api;

import io.github.panteliszara.issunexa.user.UserAccount;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Public comment author summary containing only account ID and display name.")
public record TicketCommentAuthorResponse(Long id, String displayName) {

    public static TicketCommentAuthorResponse from(UserAccount author) {
        return new TicketCommentAuthorResponse(author.getId(), author.getDisplayName());
    }

}
