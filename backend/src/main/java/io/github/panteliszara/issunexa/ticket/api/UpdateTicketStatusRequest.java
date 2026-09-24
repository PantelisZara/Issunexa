package io.github.panteliszara.issunexa.ticket.api;

import io.github.panteliszara.issunexa.ticket.TicketStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateTicketStatusRequest(@NotNull TicketStatus status) {
}
