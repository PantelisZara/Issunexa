package io.github.panteliszara.issunexa.ticket.history;

import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketRepository;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import io.github.panteliszara.issunexa.ticket.history.api.TicketHistoryResponse;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountRepository;
import io.github.panteliszara.issunexa.user.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@Testcontainers
@Transactional
class TicketHistoryRepositoryTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private TicketHistoryRepository history;
    @Autowired
    private TicketRepository tickets;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private JdbcTemplate jdbc;

    private Ticket ticket;
    private UserAccount actor;
    private UserAccount assignee;

    @BeforeEach
    void createParents() {
        UserAccount requester = user("requester", UserRole.REQUESTER);
        actor = user("actor", UserRole.AGENT);
        assignee = user("assignee", UserRole.ADMIN);
        ticket = tickets.saveAndFlush(new Ticket("Printer", "Offline", TicketStatus.OPEN, TicketPriority.HIGH, requester));
    }

    @ParameterizedTest
    @EnumSource(TicketHistoryType.class)
    void persistsAndReloadsStructuredEventWithIdentityTimestampAndLazyParents(TicketHistoryType type) {
        TicketHistoryEntry entry = switch (type) {
            case TICKET_CREATED -> TicketHistoryEntry.ticketCreated(ticket, actor);
            case STATUS_CHANGED -> TicketHistoryEntry.statusChanged(ticket, actor, TicketStatus.OPEN, TicketStatus.IN_PROGRESS);
            case ASSIGNEE_CLAIMED -> TicketHistoryEntry.assigneeClaimed(ticket, actor, assignee);
        };
        history.saveAndFlush(entry);
        assertThat(entry.getId()).isPositive();
        assertThat(entry.getCreatedAt()).isNotNull();
        entityManager.clear();

        TicketHistoryEntry loaded = entityManager.find(TicketHistoryEntry.class, entry.getId());

        assertThat(Hibernate.isInitialized(loaded.getTicket())).isFalse();
        assertThat(Hibernate.isInitialized(loaded.getActor())).isFalse();
        assertThat(loaded.getTicket().getId()).isEqualTo(ticket.getId());
        assertThat(loaded.getActor().getId()).isEqualTo(actor.getId());
        assertThat(loaded.getType()).isEqualTo(type);
        assertThat(loaded.getPreviousStatus()).isEqualTo(entry.getPreviousStatus());
        assertThat(loaded.getNewStatus()).isEqualTo(entry.getNewStatus());
        assertThat(loaded.getCreatedAt()).isCloseTo(entry.getCreatedAt(), within(1, ChronoUnit.MICROS));
        if (type == TicketHistoryType.ASSIGNEE_CLAIMED) {
            assertThat(Hibernate.isInitialized(loaded.getAssignee())).isFalse();
            assertThat(loaded.getAssignee().getId()).isEqualTo(assignee.getId());
        } else {
            assertThat(loaded.getAssignee()).isNull();
        }
        assertThat(jdbc.queryForMap("SELECT type, previous_status, new_status FROM ticket_history_entries WHERE id = ?", entry.getId()))
                .containsEntry("type", type.name())
                .containsEntry("previous_status", entry.getPreviousStatus() == null ? null : entry.getPreviousStatus().name())
                .containsEntry("new_status", entry.getNewStatus() == null ? null : entry.getNewStatus().name());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ticket", "actor", "assignee"})
    void rejectsInvalidForeignKeys(String parent) {
        assertThatThrownBy(() -> insert(parent.equals("ticket") ? -1L : ticket.getId(),
                parent.equals("actor") ? -1L : actor.getId(), "ASSIGNEE_CLAIMED", null, null,
                parent.equals("assignee") ? -1L : assignee.getId()))
                .isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23503");
                    assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("fk_ticket_history_" + parent);
                });
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "CUSTOM, NULL, NULL, false", "STATUS_CHANGED, UNKNOWN, CLOSED, false", "STATUS_CHANGED, OPEN, UNKNOWN, false",
            "TICKET_CREATED, NULL, NULL, false", "TICKET_CREATED, OPEN, OPEN, false",
            "TICKET_CREATED, NULL, IN_PROGRESS, false", "TICKET_CREATED, NULL, OPEN, true",
            "STATUS_CHANGED, NULL, CLOSED, false", "STATUS_CHANGED, OPEN, NULL, false",
            "STATUS_CHANGED, OPEN, OPEN, false", "STATUS_CHANGED, OPEN, CLOSED, true",
            "ASSIGNEE_CLAIMED, NULL, NULL, false", "ASSIGNEE_CLAIMED, OPEN, NULL, true", "ASSIGNEE_CLAIMED, NULL, OPEN, true"
    })
    void rejectsInvalidTypeStatusesAndEventShapes(String type, String previous, String next, boolean assigned) {
        assertThatThrownBy(() -> insert(ticket.getId(), actor.getId(), type, previous, next, assigned ? assignee.getId() : null))
                .isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ticket_id", "actor_id", "type", "created_at"})
    void requiredColumnsRejectNull(String column) {
        Long id = insert(ticket.getId(), actor.getId(), "TICKET_CREATED", null, "OPEN", null);
        // The column is a fixed test-fixture value, never client input.
        assertThatThrownBy(() -> jdbc.update("UPDATE ticket_history_entries SET " + column + " = NULL WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23502"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ticket", "actor", "assignee"})
    void parentDeletionCannotCascadeHistory(String parent) {
        insert(ticket.getId(), actor.getId(), "ASSIGNEE_CLAIMED", null, null, assignee.getId());
        String table = parent.equals("ticket") ? "tickets" : "users";
        Long id = switch (parent) {
            case "ticket" -> ticket.getId();
            case "actor" -> actor.getId();
            default -> assignee.getId();
        };
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + table + " WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23503");
                    assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("fk_ticket_history_" + parent);
                });
    }

    @Test
    void removingHistoryThroughJpaDoesNotCascadeToParents() {
        TicketHistoryEntry entry = history.saveAndFlush(TicketHistoryEntry.assigneeClaimed(ticket, actor, assignee));
        history.delete(entry);
        history.flush();
        entityManager.clear();
        assertThat(history.findById(entry.getId())).isEmpty();
        assertThat(tickets.findById(ticket.getId())).isPresent();
        assertThat(users.findById(actor.getId())).isPresent();
        assertThat(users.findById(assignee.getId())).isPresent();
    }

    @Test
    void pagesNewestFirstWithIdTieBreakerAndTicketScoping() {
        Long firstTie = insert(ticket.getId(), actor.getId(), "TICKET_CREATED", null, "OPEN", null);
        Long oldest = insert(ticket.getId(), actor.getId(), "STATUS_CHANGED", "OPEN", "IN_PROGRESS", null);
        Long lastTie = insert(ticket.getId(), actor.getId(), "ASSIGNEE_CLAIMED", null, null, assignee.getId());
        jdbc.update("UPDATE ticket_history_entries SET created_at = '2026-01-02T00:00:00Z'");
        jdbc.update("UPDATE ticket_history_entries SET created_at = '2026-01-01T00:00:00Z' WHERE id = ?", oldest);
        Ticket other = tickets.saveAndFlush(new Ticket("Other", "Excluded", TicketStatus.OPEN, TicketPriority.LOW, actor));
        insert(other.getId(), actor.getId(), "TICKET_CREATED", null, "OPEN", null);
        entityManager.clear();

        Page<TicketHistoryEntry> first = history.findByTicketId(ticket.getId(), page(0, 2));
        Page<TicketHistoryEntry> second = history.findByTicketId(ticket.getId(), page(1, 2));

        assertThat(first.getContent()).extracting(TicketHistoryEntry::getId).containsExactly(lastTie, firstTie);
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(second.getContent()).extracting(TicketHistoryEntry::getId).containsExactly(oldest);
        assertThat(second.isLast()).isTrue();
        assertThat(history.findByTicketId(ticket.getId(), page(2, 2)).getContent()).isEmpty();
    }

    @Test
    void actorAndOptionalAssigneeFetchingDoesNotGrowPerEntryAndWorksAfterDetach() {
        for (int i = 0; i < 8; i++) {
            UserAccount distinctActor = user("actor" + i, UserRole.AGENT);
            UserAccount distinctAssignee = user("assignee" + i, UserRole.ADMIN);
            history.saveAndFlush(i % 2 == 0 ? TicketHistoryEntry.ticketCreated(ticket, distinctActor)
                    : TicketHistoryEntry.assigneeClaimed(ticket, distinctActor, distinctAssignee));
        }
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        try {
            long small = loadPage(stats, 2);
            assertThat(loadPage(stats, 5)).isEqualTo(small).isLessThanOrEqualTo(2);
        } finally {
            stats.setStatisticsEnabled(enabled);
        }
    }

    private long loadPage(Statistics stats, int size) {
        entityManager.clear();
        stats.clear();
        Page<TicketHistoryEntry> page = history.findByTicketId(ticket.getId(), page(0, size));
        assertThat(page.getContent()).hasSize(size).allSatisfy(entry -> {
            assertThat(Hibernate.isInitialized(entry.getActor())).isTrue();
            assertThat(Hibernate.isInitialized(entry.getAssignee())).isTrue();
            assertThat(Hibernate.isInitialized(entry.getTicket())).isFalse();
        });
        entityManager.clear();
        assertThat(page.map(TicketHistoryResponse::from).getContent()).hasSize(size);
        assertThat(page.getTotalElements()).isEqualTo(8);
        assertThat(stats.getEntityFetchCount()).isZero();
        assertThat(stats.getEntityLoadCount()).isLessThanOrEqualTo(3L * size);
        return stats.getPrepareStatementCount();
    }

    private PageRequest page(int number, int size) {
        return PageRequest.of(number, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }

    private UserAccount user(String name, UserRole role) {
        return users.saveAndFlush(new UserAccount(name + "@example.com", name, "test-hash", role));
    }

    private Long insert(Long ticketId, Long actorId, String type, String previous, String next, Long assigneeId) {
        return jdbc.queryForObject("""
                INSERT INTO ticket_history_entries (ticket_id, actor_id, type, previous_status, new_status, assignee_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, ticketId, actorId, type, previous, next, assigneeId);
    }

}
