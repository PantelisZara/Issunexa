package io.github.panteliszara.issunexa.ticket.comment;

import io.github.panteliszara.issunexa.ticket.Ticket;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketRepository;
import io.github.panteliszara.issunexa.ticket.TicketStatus;
import io.github.panteliszara.issunexa.ticket.comment.api.TicketCommentResponse;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@Testcontainers
@Transactional
class TicketCommentRepositoryTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private TicketCommentRepository comments;
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
    private UserAccount author;

    @BeforeEach
    void createParents() {
        UserAccount requester = users.saveAndFlush(
                new UserAccount("requester@example.com", "Requester", "test-hash", UserRole.REQUESTER));
        author = users.saveAndFlush(new UserAccount("author@example.com", "Author", "test-hash", UserRole.AGENT));
        ticket = tickets.saveAndFlush(new Ticket("Printer offline", "No connection", TicketStatus.OPEN,
                TicketPriority.MEDIUM, requester));
    }

    @Test
    void persistsIdentityBodyAndTimestampAndReloadsWithLazyRelationships() {
        TicketComment comment = comments.saveAndFlush(new TicketComment(ticket, author, "  First  line\nSecond line  "));
        assertThat(comment.getId()).isPositive();
        assertThat(comment.getCreatedAt()).isNotNull();
        entityManager.clear();

        TicketComment reloaded = entityManager.find(TicketComment.class, comment.getId());

        assertThat(Hibernate.isInitialized(reloaded.getTicket())).isFalse();
        assertThat(Hibernate.isInitialized(reloaded.getAuthor())).isFalse();
        assertThat(reloaded.getTicket().getId()).isEqualTo(ticket.getId());
        assertThat(reloaded.getAuthor().getId()).isEqualTo(author.getId());
        assertThat(reloaded.getBody()).isEqualTo("First  line\nSecond line");
        assertThat(reloaded.getCreatedAt()).isCloseTo(comment.getCreatedAt(), within(1, ChronoUnit.MICROS));
        assertThat(jdbc.queryForMap("SELECT ticket_id, author_id, body FROM ticket_comments WHERE id = ?", comment.getId()))
                .containsEntry("ticket_id", ticket.getId()).containsEntry("author_id", author.getId())
                .containsEntry("body", "First  line\nSecond line");
    }

    @ParameterizedTest
    @CsvSource({"ticket, fk_ticket_comments_ticket", "author, fk_ticket_comments_author"})
    void rejectsMissingForeignKey(String parent, String constraint) {
        assertThatThrownBy(() -> insert(parent.equals("ticket") ? -1L : ticket.getId(),
                parent.equals("author") ? -1L : author.getId(), "Comment", Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23503");
                    assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsBlankBodyFromDirectSql(String body) {
        assertThatThrownBy(() -> insert(ticket.getId(), author.getId(), body, Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23514");
                    assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("ck_ticket_comments_body_nonblank");
                });
    }

    @Test
    void enforcesDatabaseBodyLengthBoundary() {
        Long id = insert(ticket.getId(), author.getId(), "x".repeat(4000), Instant.now());
        assertThat(jdbc.queryForObject("SELECT length(body) FROM ticket_comments WHERE id = ?", Integer.class, id))
                .isEqualTo(4000);
        assertThatThrownBy(() -> insert(ticket.getId(), author.getId(), "x".repeat(4001), Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("22001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ticket_id", "author_id", "body", "created_at"})
    void requiredColumnsRejectNull(String column) {
        Long id = insert(ticket.getId(), author.getId(), "Comment", Instant.now());
        // Column names come only from this fixed test fixture, never client input.
        assertThatThrownBy(() -> jdbc.update("UPDATE ticket_comments SET " + column + " = NULL WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23502"));
    }

    @ParameterizedTest
    @CsvSource({"tickets, fk_ticket_comments_ticket", "users, fk_ticket_comments_author"})
    void parentDeletionIsRestrictiveRatherThanCascading(String table, String constraint) {
        insert(ticket.getId(), author.getId(), "Comment", Instant.now());
        Long id = table.equals("tickets") ? ticket.getId() : author.getId();

        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + table + " WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).rootCause()
                .isInstanceOfSatisfying(PSQLException.class, error -> {
                    assertThat(error.getSQLState()).isEqualTo("23503");
                    assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
                });
    }

    @Test
    void removingCommentThroughJpaDoesNotCascadeToEitherParent() {
        TicketComment comment = comments.saveAndFlush(new TicketComment(ticket, author, "Comment"));

        comments.delete(comment);
        comments.flush();
        entityManager.clear();

        assertThat(comments.findById(comment.getId())).isEmpty();
        assertThat(tickets.findById(ticket.getId())).isPresent();
        assertThat(users.findById(author.getId())).isPresent();
    }

    @Test
    void paginatesOnlyRequestedTicketChronologicallyWithIdTieBreaker() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Long latest = insert(ticket.getId(), author.getId(), "Latest", start.plusSeconds(2));
        Long tiedFirst = insert(ticket.getId(), author.getId(), "Tied first", start.plusSeconds(1));
        Long earliest = insert(ticket.getId(), author.getId(), "Earliest", start);
        Long tiedSecond = insert(ticket.getId(), author.getId(), "Tied second", start.plusSeconds(1));
        Ticket other = tickets.saveAndFlush(new Ticket("Other", "Exclude", TicketStatus.OPEN, TicketPriority.LOW, author));
        insert(other.getId(), author.getId(), "Other ticket", start.minusSeconds(1));
        entityManager.clear();

        Page<TicketComment> first = comments.findByTicketId(ticket.getId(), page(0, 2));
        Page<TicketComment> second = comments.findByTicketId(ticket.getId(), page(1, 2));
        Page<TicketComment> beyond = comments.findByTicketId(ticket.getId(), page(2, 2));

        assertThat(first.getContent()).extracting(TicketComment::getId).containsExactly(earliest, tiedFirst);
        assertThat(first.getTotalElements()).isEqualTo(4);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.isFirst()).isTrue();
        assertThat(first.isLast()).isFalse();
        assertThat(second.getContent()).extracting(TicketComment::getId).containsExactly(tiedSecond, latest);
        assertThat(second.isLast()).isTrue();
        assertThat(beyond.getContent()).isEmpty();
        assertThat(beyond.getTotalElements()).isEqualTo(4);
    }

    @Test
    void authorFetchingIsBoundedAcrossPageSizesAndMappingWorksAfterDetach() {
        for (int i = 0; i < 8; i++) {
            UserAccount distinctAuthor = users.saveAndFlush(new UserAccount("author" + i + "@example.com",
                    "Author " + i, "test-hash", UserRole.REQUESTER));
            comments.saveAndFlush(new TicketComment(ticket, distinctAuthor, "Comment " + i));
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long smallPageQueries = loadAndMapPage(statistics, 2);
            long largerPageQueries = loadAndMapPage(statistics, 5);
            assertThat(largerPageQueries).isEqualTo(smallPageQueries).isLessThanOrEqualTo(2);
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    private long loadAndMapPage(Statistics statistics, int size) {
        entityManager.clear();
        statistics.clear();
        Page<TicketComment> page = comments.findByTicketId(ticket.getId(), page(0, size));
        assertThat(page.getContent()).hasSize(size).allSatisfy(comment -> {
            assertThat(Hibernate.isInitialized(comment.getAuthor())).isTrue();
            assertThat(Hibernate.isInitialized(comment.getTicket())).isFalse();
        });
        entityManager.clear();
        List<TicketCommentResponse> responses = page.map(TicketCommentResponse::from).getContent();
        assertThat(responses).hasSize(size).allSatisfy(response -> assertThat(response.author().displayName()).startsWith("Author "));
        assertThat(page.getTotalElements()).isEqualTo(8);
        assertThat(statistics.getEntityFetchCount()).isZero();
        // Only the requested comments and their distinct authors are materialized, not all eight comments.
        assertThat(statistics.getEntityLoadCount()).isLessThanOrEqualTo(2L * size);
        return statistics.getPrepareStatementCount();
    }

    private PageRequest page(int number, int size) {
        return PageRequest.of(number, size, Sort.by(Sort.Direction.ASC, "createdAt", "id"));
    }

    private Long insert(Long ticketId, Long authorId, String body, Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO ticket_comments (ticket_id, author_id, body, created_at) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, ticketId, authorId, body, Timestamp.from(createdAt));
    }

}
