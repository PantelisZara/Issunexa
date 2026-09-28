package io.github.panteliszara.issunexa.ticket.api;

import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record TicketResponse(
        Long id,
        String title,
        String description,
        TicketStatus status,
        TicketPriority priority,
        Instant createdAt,
        Instant updatedAt,
        @Schema(description = "Assigned staff ID and display name, or null when unassigned.", nullable = true)
        TicketAssigneeResponse assignee
) {

    public static TicketResponse from(Ticket ticket) {
        return new TicketResponse(ticket.getId(), ticket.getTitle(), ticket.getDescription(),
                ticket.getStatus(), ticket.getPriority(), ticket.getCreatedAt(), ticket.getUpdatedAt(),
                ticket.getAssignee() == null ? null : TicketAssigneeResponse.from(ticket.getAssignee()));
    }

}
