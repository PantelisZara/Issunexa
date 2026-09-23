package io.github.panteliszara.issunexa.ticket;

public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(Long id) {
        super("Ticket with ID " + id + " was not found");
    }

}
