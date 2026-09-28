package io.github.panteliszara.issunexa.ticket;

import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountService;
import io.github.panteliszara.issunexa.user.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class TicketOptimisticLockingIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private TicketService ticketService;

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    private UserAccount agent;
    private UserAccount admin;
    private Long ticketId;

    @BeforeEach
    void createCommittedFixture() {
        jdbc.update("DELETE FROM tickets");
        jdbc.update("DELETE FROM users");
        agent = userAccountService.createUser("agent@example.com", "Agent", "locking test password", UserRole.AGENT);
        admin = userAccountService.createUser("admin@example.com", "Admin", "locking test password", UserRole.ADMIN);
        ticketId = ticketService.createTicket("Printer offline", "No connection", TicketPriority.HIGH, agent.getEmail())
                .getId();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                agent.getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void committedClaimAndStatusUpdatesAdvanceTheJpaVersion() {
        assertThat(storedState()).containsEntry("version", 0L).containsEntry("assignee_id", null);

        Ticket claimed = ticketService.claimTicket(ticketId, agent.getEmail());

        assertThat(claimed.getVersion()).isEqualTo(1);
        assertThat(storedState()).containsEntry("version", 1L).containsEntry("assignee_id", agent.getId())
                .containsEntry("status", "OPEN").containsEntry("requester_id", agent.getId());

        Ticket progressed = ticketService.changeStatus(ticketId, TicketStatus.IN_PROGRESS);

        assertThat(progressed.getVersion()).isEqualTo(2);
        assertThat(storedState()).containsEntry("version", 2L).containsEntry("assignee_id", agent.getId())
                .containsEntry("status", "IN_PROGRESS").containsEntry("requester_id", agent.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void staleClaimOrStatusCannotOverwriteACommittedClaim(boolean staleClaim) {
        EntityManager staleContext = entityManagerFactory.createEntityManager();
        EntityManager winningContext = entityManagerFactory.createEntityManager();
        try {
            staleContext.getTransaction().begin();
            Ticket stale = staleContext.find(Ticket.class, ticketId);
            assertThat(stale.getVersion()).isZero();

            winningContext.getTransaction().begin();
            Ticket winner = winningContext.find(Ticket.class, ticketId);
            winner.claim(winningContext.find(UserAccount.class, admin.getId()));
            winningContext.getTransaction().commit();
            Map<String, Object> committed = storedState();
            assertThat(committed).containsEntry("version", 1L).containsEntry("assignee_id", admin.getId());

            if (staleClaim) {
                stale.claim(staleContext.find(UserAccount.class, agent.getId()));
            } else {
                stale.changeStatus(TicketStatus.IN_PROGRESS);
            }
            assertThatThrownBy(staleContext::flush).isInstanceOf(OptimisticLockException.class);
            staleContext.getTransaction().rollback();

            assertThat(storedState()).isEqualTo(committed);
        } finally {
            close(staleContext);
            close(winningContext);
        }
    }

    private Map<String, Object> storedState() {
        return jdbc.queryForMap("SELECT * FROM tickets WHERE id = ?", ticketId);
    }

    private void close(EntityManager context) {
        if (context.getTransaction().isActive()) {
            context.getTransaction().rollback();
        }
        context.close();
    }

}
