package io.github.panteliszara.issunexa.ticket;

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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TicketStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private TicketPriority priority;

    // Historical rows may be unowned; application creation requires a requester.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", nullable = true, updatable = false)
    private UserAccount requester;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Ticket() {
    }

    public Ticket(String title, String description, TicketStatus status, TicketPriority priority,
            UserAccount requester) {
        this.title = title;
        this.description = description;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.priority = priority;
        this.requester = Objects.requireNonNull(requester, "requester must not be null");
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public TicketPriority getPriority() {
        return priority;
    }

    public UserAccount getRequester() {
        return requester;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void changeStatus(TicketStatus targetStatus) {
        Objects.requireNonNull(targetStatus, "targetStatus must not be null");
        boolean allowed = switch (status) {
            case OPEN -> targetStatus == TicketStatus.IN_PROGRESS;
            case IN_PROGRESS -> targetStatus == TicketStatus.RESOLVED;
            case RESOLVED -> targetStatus == TicketStatus.IN_PROGRESS || targetStatus == TicketStatus.CLOSED;
            case CLOSED -> false;
        };
        if (!allowed) {
            throw new InvalidTicketStatusTransitionException(id, status, targetStatus);
        }
        status = targetStatus;
    }

    public void updateDetails(String title, String description) {
        this.title = title;
        this.description = description;
    }

    @PrePersist
    private void initializeTimestamps() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    private void updateTimestamp() {
        updatedAt = Instant.now();
    }

}
