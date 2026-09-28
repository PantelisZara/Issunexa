package io.github.panteliszara.issunexa.ticket.comment;

import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.user.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "ticket_comments")
public class TicketComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false, updatable = false)
    private UserAccount author;

    @Column(name = "body", nullable = false, length = 4000, updatable = false)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TicketComment() {
    }

    public TicketComment(Ticket ticket, UserAccount author, String body) {
        this.ticket = Objects.requireNonNull(ticket, "ticket must not be null");
        this.author = Objects.requireNonNull(author, "author must not be null");
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body must not be blank");
        }
        if (body.length() > 4000) {
            throw new IllegalArgumentException("body must not exceed 4000 characters");
        }
        this.body = body.strip();
    }

    public Long getId() {
        return id;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public UserAccount getAuthor() {
        return author;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @PrePersist
    private void initializeTimestamp() {
        createdAt = Instant.now();
    }

}
