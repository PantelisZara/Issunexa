package io.github.panteliszara.issunexa.demo;

import io.github.panteliszara.issunexa.user.UserAccount;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DemoWorkspaceAccess {
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public DemoWorkspaceAccess(JdbcTemplate jdbc, Environment environment) {
        this.jdbc = jdbc;
        enabled = environment.matchesProfiles("demo");
    }

    public void validate(UserAccount account) {
        if (account.getDemoWorkspaceId() == null) {
            if (enabled) throw new DemoExpiredException();
            return;
        }
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM demo_workspaces WHERE id = ? AND expires_at > clock_timestamp())",
                Boolean.class, account.getDemoWorkspaceId()))) throw new DemoExpiredException();
    }

    public void beforeTicket(UserAccount account) {
        if (!lock(account)) return;
        requireBelow("SELECT count(*) FROM tickets t JOIN users u ON u.id = t.requester_id WHERE u.demo_workspace_id = ?",
                account.getDemoWorkspaceId(), 8);
        historyQuota(account.getDemoWorkspaceId());
    }

    public void beforeHistory(UserAccount account) {
        if (lock(account)) historyQuota(account.getDemoWorkspaceId());
    }

    public void beforeComment(UserAccount account) {
        if (!lock(account)) return;
        requireBelow("SELECT count(*) FROM ticket_comments c JOIN tickets t ON t.id = c.ticket_id "
                + "JOIN users u ON u.id = t.requester_id WHERE u.demo_workspace_id = ?", account.getDemoWorkspaceId(), 20);
    }

    private void historyQuota(Long workspace) {
        requireBelow("SELECT count(*) FROM ticket_history_entries h JOIN tickets t ON t.id = h.ticket_id "
                + "JOIN users u ON u.id = t.requester_id WHERE u.demo_workspace_id = ?", workspace, 128);
    }

    private boolean lock(UserAccount account) {
        if (account.getDemoWorkspaceId() == null) {
            validate(account);
            return false;
        }
        // Runtime has no UPDATE on workspace lifetime columns. Namespaced transaction
        // advisory locks serialize this count/write boundary without expanding its grants.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(736294019, hashtext(cast(? AS text)))", Object.class,
                account.getDemoWorkspaceId());
        validate(account);
        return true;
    }

    private void requireBelow(String query, Long workspace, int limit) {
        if (jdbc.queryForObject(query, Long.class, workspace) >= limit) throw new DemoUnavailableException();
    }
}
