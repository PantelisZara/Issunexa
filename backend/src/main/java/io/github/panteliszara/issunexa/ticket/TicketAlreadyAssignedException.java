package io.github.panteliszara.issunexa.ticket;

public class TicketAlreadyAssignedException extends RuntimeException {

    public TicketAlreadyAssignedException() {
        super("The ticket already has an assignee.");
    }

}
