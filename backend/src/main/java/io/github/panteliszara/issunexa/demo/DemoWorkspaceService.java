package io.github.panteliszara.issunexa.demo;

import io.github.panteliszara.issunexa.ticket.TicketCategory;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketService;
import io.github.panteliszara.issunexa.user.UserAccount;
import io.github.panteliszara.issunexa.user.UserAccountRepository;
import io.github.panteliszara.issunexa.user.UserRole;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Profile("demo")
public class DemoWorkspaceService {
    public static final String SESSION_WORKSPACE = DemoWorkspaceService.class.getName() + ".workspace";
    private final JdbcTemplate jdbc;
    private final UserAccountRepository accounts;
    private final PasswordEncoder encoder;
    private final TicketService tickets;

    public DemoWorkspaceService(JdbcTemplate jdbc, UserAccountRepository accounts, PasswordEncoder encoder,
            TicketService tickets) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.encoder = encoder;
        this.tickets = tickets;
    }

    @Transactional
    public UserAccount start(Long existingWorkspace) {
        if (existingWorkspace != null) return account(existingWorkspace, UserRole.REQUESTER);
        jdbc.execute("SELECT pg_advisory_xact_lock(73629401915001)");
        cleanup();
        if (jdbc.queryForObject("SELECT count(*) FROM demo_workspaces", Long.class) >= 50)
            throw new DemoUnavailableException();
        Long workspace = jdbc.queryForObject("INSERT INTO demo_workspaces (created_at, expires_at) "
                + "VALUES (statement_timestamp(), statement_timestamp() + interval '30 minutes') RETURNING id", Long.class);
        UserAccount requester = create(workspace, UserRole.REQUESTER, "Demo requester");
        create(workspace, UserRole.AGENT, "Demo agent");
        tickets.createTicket("Explore your isolated workspace", "Synthetic example: a meeting room display needs attention.",
                TicketPriority.MEDIUM, TicketCategory.OTHER, requester.getEmail());
        return requester;
    }

    @Transactional(readOnly = true)
    public UserAccount account(Long workspace, UserRole role) {
        if (workspace == null || role == UserRole.ADMIN || !active(workspace)) throw new DemoExpiredException();
        return accounts.findByDemoWorkspaceIdAndRole(workspace, role).orElseThrow(DemoExpiredException::new);
    }

    @Transactional(readOnly = true)
    public boolean active(Long workspace) {
        return workspace != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM demo_workspaces WHERE id = ? AND expires_at > clock_timestamp())",
                Boolean.class, workspace));
    }

    @Transactional(readOnly = true)
    public Instant expiry(Long workspace) {
        if (workspace == null) return null;
        return jdbc.query("SELECT expires_at FROM demo_workspaces WHERE id = ?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), workspace).stream().findFirst().orElse(null);
    }

    @Transactional
    public int cleanup() {
        return jdbc.queryForObject("SELECT expire_demo_workspaces()", Integer.class);
    }

    private UserAccount create(Long workspace, UserRole role, String name) {
        UserAccount account = new UserAccount("demo-" + UUID.randomUUID() + "@example.invalid", name,
                encoder.encode(UUID.randomUUID().toString()), role, workspace);
        return accounts.saveAndFlush(account);
    }
}
