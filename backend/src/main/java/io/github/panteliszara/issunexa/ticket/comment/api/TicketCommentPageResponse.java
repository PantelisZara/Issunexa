package io.github.panteliszara.issunexa.ticket.comment.api;

import io.github.panteliszara.issunexa.ticket.comment.TicketComment;
import org.springframework.data.domain.Page;

import java.util.List;

public record TicketCommentPageResponse(
        List<TicketCommentResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public TicketCommentPageResponse {
        content = List.copyOf(content);
    }

    public static TicketCommentPageResponse from(Page<TicketComment> comments) {
        return new TicketCommentPageResponse(comments.getContent().stream().map(TicketCommentResponse::from).toList(),
                comments.getNumber(), comments.getSize(), comments.getTotalElements(), comments.getTotalPages(),
                comments.isFirst(), comments.isLast());
    }

}
