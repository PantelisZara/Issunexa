package io.github.panteliszara.issunexa.ticket;

public class InvalidTicketStatusTransitionException extends RuntimeException {

    private final Long ticketId;
    private final TicketStatus currentStatus;
    private final TicketStatus requestedStatus;

    public InvalidTicketStatusTransitionException(Long ticketId, TicketStatus currentStatus, TicketStatus requestedStatus) {
        super((ticketId == null ? "Ticket" : "Ticket " + ticketId)
                + " cannot transition from " + currentStatus + " to " + requestedStatus + ".");
        this.ticketId = ticketId;
        this.currentStatus = currentStatus;
        this.requestedStatus = requestedStatus;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public TicketStatus getCurrentStatus() {
        return currentStatus;
    }

    public TicketStatus getRequestedStatus() {
        return requestedStatus;
    }

}
