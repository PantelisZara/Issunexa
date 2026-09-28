package io.github.panteliszara.issunexa.ticket.api;

import io.github.panteliszara.issunexa.ticket.TicketCategory;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTicketRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank String description,
        @NotNull TicketPriority priority,
        @NotNull TicketCategory category
) {
}
