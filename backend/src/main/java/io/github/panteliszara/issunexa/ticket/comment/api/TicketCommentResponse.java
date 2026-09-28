package io.github.panteliszara.issunexa.ticket.comment.api;

import io.github.panteliszara.issunexa.ticket.comment.TicketComment;

import java.time.Instant;

public record TicketCommentResponse(Long id, String body, TicketCommentAuthorResponse author, Instant createdAt) {

    public static TicketCommentResponse from(TicketComment comment) {
        return new TicketCommentResponse(comment.getId(), comment.getBody(),
                TicketCommentAuthorResponse.from(comment.getAuthor()), comment.getCreatedAt());
    }

}
