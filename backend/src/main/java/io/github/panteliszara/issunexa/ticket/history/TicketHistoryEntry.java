package io.github.panteliszara.issunexa.ticket.history;

import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import io.github.panteliszara.issunexa.user.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "ticket_history_entries")
public class TicketHistoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false, updatable = false)
    private UserAccount actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private TicketHistoryType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 20, updatable = false)
    private TicketStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", length = 20, updatable = false)
    private TicketStatus newStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id", updatable = false)
    private UserAccount assignee;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TicketHistoryEntry() {
    }

    private TicketHistoryEntry(Ticket ticket, UserAccount actor, TicketHistoryType type,
            TicketStatus previousStatus, TicketStatus newStatus, UserAccount assignee) {
        this.ticket = Objects.requireNonNull(ticket, "ticket must not be null");
        this.actor = Objects.requireNonNull(actor, "actor must not be null");
        this.type = type;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.assignee = assignee;
    }

    public static TicketHistoryEntry ticketCreated(Ticket ticket, UserAccount actor) {
        return new TicketHistoryEntry(ticket, actor, TicketHistoryType.TICKET_CREATED, null, TicketStatus.OPEN, null);
    }

    public static TicketHistoryEntry statusChanged(Ticket ticket, UserAccount actor,
            TicketStatus previousStatus, TicketStatus newStatus) {
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        if (previousStatus == newStatus) {
            throw new IllegalArgumentException("previousStatus and newStatus must differ");
        }
        return new TicketHistoryEntry(ticket, actor, TicketHistoryType.STATUS_CHANGED, previousStatus, newStatus, null);
    }

    public static TicketHistoryEntry assigneeClaimed(Ticket ticket, UserAccount actor, UserAccount assignee) {
        Objects.requireNonNull(assignee, "assignee must not be null");
        return new TicketHistoryEntry(ticket, actor, TicketHistoryType.ASSIGNEE_CLAIMED, null, null, assignee);
    }

    public Long getId() {
        return id;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public UserAccount getActor() {
        return actor;
    }

    public TicketHistoryType getType() {
        return type;
    }

    public TicketStatus getPreviousStatus() {
        return previousStatus;
    }

    public TicketStatus getNewStatus() {
        return newStatus;
    }

    public UserAccount getAssignee() {
        return assignee;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @PrePersist
    private void initializeTimestamp() {
        createdAt = Instant.now();
    }

}
