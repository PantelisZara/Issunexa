package io.github.panteliszara.issunexa.ticket.history.api;

import io.github.panteliszara.issunexa.ticket.TicketStatus;
import io.github.panteliszara.issunexa.ticket.api.TicketAssigneeResponse;
import io.github.panteliszara.issunexa.ticket.history.TicketHistoryEntry;
import io.github.panteliszara.issunexa.ticket.history.TicketHistoryType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record TicketHistoryResponse(
        Long id,
        TicketHistoryType type,
        TicketHistoryActorResponse actor,
        @Schema(description = "Previous status for STATUS_CHANGED; otherwise null.", nullable = true)
        TicketStatus previousStatus,
        @Schema(description = "OPEN for TICKET_CREATED, target status for STATUS_CHANGED; otherwise null.", nullable = true)
        TicketStatus newStatus,
        @Schema(description = "Claimed assignee ID and display name for ASSIGNEE_CLAIMED; otherwise null.", nullable = true)
        TicketAssigneeResponse assignee,
        Instant createdAt
) {

    public static TicketHistoryResponse from(TicketHistoryEntry entry) {
        return new TicketHistoryResponse(entry.getId(), entry.getType(), TicketHistoryActorResponse.from(entry.getActor()),
                entry.getPreviousStatus(), entry.getNewStatus(),
                entry.getAssignee() == null ? null : TicketAssigneeResponse.from(entry.getAssignee()), entry.getCreatedAt());
    }

}
