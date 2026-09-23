package io.github.panteliszara.issunexa.ticket;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@Transactional
class TicketRepositoryTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsAndReloadsTicketWithGeneratedIdEnumsAndTimestamps() {
        Ticket ticket = new Ticket("Printer offline", "The office printer is unreachable.",
                TicketStatus.IN_PROGRESS, TicketPriority.HIGH);

        ticketRepository.saveAndFlush(ticket);
        assertThat(ticket.getId()).isPositive();
        entityManager.clear();

        Ticket loaded = ticketRepository.findById(ticket.getId()).orElseThrow();

        assertThat(loaded.getTitle()).isEqualTo("Printer offline");
        assertThat(loaded.getDescription()).isEqualTo("The office printer is unreachable.");
        assertThat(loaded.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(loaded.getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
        assertThat(jdbcTemplate.queryForMap("SELECT status, priority FROM tickets WHERE id = ?", ticket.getId()))
                .containsEntry("status", "IN_PROGRESS")
                .containsEntry("priority", "HIGH");
    }

    @Test
    void updatesModificationTimeWhilePreservingCreationTime() {
        Ticket ticket = ticketRepository.saveAndFlush(new Ticket("Printer offline", "Original description",
                TicketStatus.OPEN, TicketPriority.MEDIUM));

        // Model an older row so the update assertion does not depend on clock resolution.
        jdbcTemplate.update("""
                UPDATE tickets
                SET created_at = TIMESTAMPTZ '2000-01-01 00:00:00+00',
                    updated_at = TIMESTAMPTZ '2000-01-01 00:00:00+00'
                WHERE id = ?
                """, ticket.getId());
        entityManager.clear();

        Ticket existing = ticketRepository.findById(ticket.getId()).orElseThrow();
        Instant createdAt = existing.getCreatedAt();
        Instant updatedAt = existing.getUpdatedAt();

        existing.updateDetails("Printer connection issue", "Updated description");
        ticketRepository.flush();
        entityManager.clear();

        Ticket updated = ticketRepository.findById(ticket.getId()).orElseThrow();

        assertThat(updated.getTitle()).isEqualTo("Printer connection issue");
        assertThat(updated.getDescription()).isEqualTo("Updated description");
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfter(updatedAt);
    }

    @Test
    void pagesTicketsByCreationTimeDescendingWithIdAsTieBreaker() {
        Ticket oldest = new Ticket("Oldest", "Oldest ticket", TicketStatus.OPEN, TicketPriority.LOW);
        Ticket firstAtLatestTime = new Ticket("Latest first", "First at latest time",
                TicketStatus.OPEN, TicketPriority.HIGH);
        Ticket secondAtLatestTime = new Ticket("Latest second", "Second at latest time",
                TicketStatus.OPEN, TicketPriority.HIGH);
        Ticket middle = new Ticket("Middle", "Middle ticket", TicketStatus.OPEN, TicketPriority.MEDIUM);
        ticketRepository.saveAllAndFlush(List.of(oldest, firstAtLatestTime, secondAtLatestTime, middle));

        jdbcTemplate.update("""
                UPDATE tickets SET created_at = TIMESTAMPTZ '2000-01-01 00:00:00+00' WHERE id = ?
                """, oldest.getId());
        jdbcTemplate.update("""
                UPDATE tickets SET created_at = TIMESTAMPTZ '2001-01-01 00:00:00+00' WHERE id = ?
                """, middle.getId());
        jdbcTemplate.update("""
                UPDATE tickets SET created_at = TIMESTAMPTZ '2002-01-01 00:00:00+00' WHERE id IN (?, ?)
                """, firstAtLatestTime.getId(), secondAtLatestTime.getId());
        entityManager.clear();

        Page<Ticket> firstPage = ticketService.listTickets(0, 2);
        Page<Ticket> secondPage = ticketService.listTickets(1, 2);

        assertThat(firstPage.getContent()).extracting(Ticket::getId)
                .containsExactly(secondAtLatestTime.getId(), firstAtLatestTime.getId());
        assertThat(secondPage.getContent()).extracting(Ticket::getId)
                .containsExactly(middle.getId(), oldest.getId());
        assertThat(firstPage.getTotalElements()).isEqualTo(4);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.isFirst()).isTrue();
        assertThat(firstPage.isLast()).isFalse();
        assertThat(secondPage.getNumber()).isEqualTo(1);
        assertThat(secondPage.getTotalElements()).isEqualTo(4);
        assertThat(secondPage.isLast()).isTrue();
    }

    @ParameterizedTest(name = "rejects invalid enum value using {2}")
    @CsvSource({
            "INVALID, HIGH, ck_tickets_status",
            "OPEN, INVALID, ck_tickets_priority"
    })
    void rejectsInvalidEnumValuesThroughSql(String status, String priority, String constraintName) {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO tickets (title, description, status, priority, created_at, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "Invalid ticket", "Invalid enum value", status, priority))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .isInstanceOfSatisfying(PSQLException.class, exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23514");
                    assertThat(exception.getServerErrorMessage().getConstraint()).isEqualTo(constraintName);
                });
    }

}
