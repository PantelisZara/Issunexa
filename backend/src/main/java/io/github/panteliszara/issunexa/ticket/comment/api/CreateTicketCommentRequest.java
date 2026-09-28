package io.github.panteliszara.issunexa.ticket.comment.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTicketCommentRequest(
        @Schema(description = "Nonblank plain text; outer whitespace is stripped and internal whitespace is preserved.",
                minLength = 1, maxLength = 4000)
        @NotBlank @Size(max = 4000) String body
) {
}
