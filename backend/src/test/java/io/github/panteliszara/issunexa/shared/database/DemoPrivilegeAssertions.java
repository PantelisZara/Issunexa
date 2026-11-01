package io.github.panteliszara.issunexa.shared.database;

import io.github.panteliszara.issunexa.demo.DemoWorkspaceService;
import io.github.panteliszara.issunexa.demo.DemoUnavailableException;
import io.github.panteliszara.issunexa.demo.DemoExpiredException;
import io.github.panteliszara.issunexa.ticket.TicketService;
import io.github.panteliszara.issunexa.ticket.TicketPriority;
import io.github.panteliszara.issunexa.ticket.TicketCategory;
import io.github.panteliszara.issunexa.ticket.comment.TicketCommentService;
import io.github.panteliszara.issunexa.user.UserAccountRepository;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.flywaydb.core.Flyway;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DemoPrivilegeAssertions {
    private DemoPrivilegeAssertions() { }

    static void verify(JdbcTemplate migration, JdbcTemplate runtime) {
        assertThat(runtime.queryForObject("SELECT expire_demo_workspaces()", Integer.class)).isZero();
        assertThat(runtime.queryForObject("""
                SELECT has_function_privilege('public', p.oid, 'EXECUTE') FROM pg_proc p
                WHERE p.pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = current_schema())
                  AND p.proname = 'expire_demo_workspaces'
                """, Boolean.class)).isFalse();
        for (String sql : List.of("DELETE FROM demo_workspaces", "DELETE FROM users", "DELETE FROM tickets",
                "UPDATE demo_workspaces SET expires_at = now()", "ALTER FUNCTION expire_demo_workspaces() SECURITY INVOKER",
                "CREATE OR REPLACE FUNCTION expire_demo_workspaces() RETURNS integer LANGUAGE sql AS 'SELECT 999'")) {
            assertThatThrownBy(() -> runtime.execute(sql)).rootCause().isInstanceOfSatisfying(PSQLException.class,
                    e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
        assertThatThrownBy(() -> runtime.execute("SELECT expire_demo_workspaces(1)"))
                .rootCause().isInstanceOfSatisfying(PSQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42883"));
        // A no-pending callback also upgrades the recognized legacy V9 table UPDATE grant.
        migration.execute("GRANT UPDATE ON TABLE tickets TO " + runtime.queryForObject("SELECT current_user", String.class));
        Flyway.configure().dataSource(migration.getDataSource()).load().migrate();
        long ordinaryUsers = runtime.queryForObject("SELECT count(*) FROM users WHERE demo_workspace_id IS NULL", Long.class);
        Long active = workspace(migration, false, "active");
        Long expired = workspace(migration, true, "expired");
        Long requester = migration.queryForObject("SELECT id FROM users WHERE demo_workspace_id = ? AND role = 'REQUESTER'", Long.class, expired);
        Long ticket = migration.queryForObject("""
                INSERT INTO tickets(title,description,status,priority,category,requester_id,version,created_at,updated_at)
                VALUES ('Expired synthetic','Synthetic fixture','OPEN','LOW','OTHER',?,0,now(),now()) RETURNING id
                """, Long.class, requester);
        migration.update("INSERT INTO ticket_comments(ticket_id,author_id,body,created_at) VALUES (?,?,'Synthetic',now())", ticket, requester);
        migration.update("INSERT INTO ticket_history_entries(ticket_id,actor_id,type,new_status,created_at) VALUES (?,?,'TICKET_CREATED','OPEN',now())", ticket, requester);
        Long outsider = migration.queryForObject("INSERT INTO users(email,display_name,password_hash,role,created_at,updated_at) VALUES ('cleanup-outsider@example.invalid','Ordinary','disabled','REQUESTER',now(),now()) RETURNING id", Long.class);
        Long ordinaryTicket = migration.queryForObject("""
                INSERT INTO tickets(title,description,status,priority,category,requester_id,version,created_at,updated_at)
                VALUES ('Ordinary legacy ticket','No synthetic provenance or history','OPEN','LOW','OTHER',?,0,now(),now()) RETURNING id
                """, Long.class, outsider);
        assertThat(runtime.queryForObject("SELECT has_table_privilege(current_user, 'tickets', 'UPDATE')", Boolean.class)).isFalse();
        for (String column : List.of("title", "description", "status", "priority", "assignee_id", "version", "updated_at"))
            assertThat(runtime.queryForObject("SELECT has_column_privilege(current_user, 'tickets', ?, 'UPDATE')", Boolean.class, column)).isTrue();
        for (String column : List.of("id", "requester_id", "category", "created_at"))
            assertThat(runtime.queryForObject("SELECT has_column_privilege(current_user, 'tickets', ?, 'UPDATE')", Boolean.class, column)).isFalse();
        assertThatThrownBy(() -> runtime.update("UPDATE tickets SET requester_id = ? WHERE id = ?", requester, ordinaryTicket))
                .rootCause().isInstanceOfSatisfying(PSQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        runtime.update("UPDATE tickets SET title = 'Mutable', description = 'Mutable', status = 'IN_PROGRESS', priority = 'HIGH', assignee_id = NULL, version = version + 1, updated_at = now() WHERE id = ?", ordinaryTicket);
        Long foreignComment = migration.queryForObject("INSERT INTO ticket_comments(ticket_id,author_id,body,created_at) VALUES (?,?,'Preserved ordinary reference',now()) RETURNING id", Long.class, ticket, outsider);
        assertThat(runtime.queryForObject("SELECT expire_demo_workspaces()", Integer.class)).isZero();
        assertThat(runtime.queryForObject("SELECT count(*) FROM ticket_comments WHERE id = ?", Long.class, foreignComment)).isEqualTo(1);
        migration.update("DELETE FROM ticket_comments WHERE id = ?", foreignComment);
        assertThat(runtime.queryForObject("SELECT expire_demo_workspaces()", Integer.class)).isEqualTo(1);
        assertThat(runtime.queryForObject("SELECT requester_id FROM tickets WHERE id = ?", Long.class, ordinaryTicket)).isEqualTo(outsider);
        migration.update("DELETE FROM tickets WHERE id = ?", ordinaryTicket);
        migration.update("DELETE FROM users WHERE id = ?", outsider);
        assertThat(runtime.queryForObject("SELECT count(*) FROM demo_workspaces WHERE id = ?", Long.class, expired)).isZero();
        assertThat(runtime.queryForObject("SELECT count(*) FROM demo_workspaces WHERE id = ?", Long.class, active)).isEqualTo(1);
        assertThat(runtime.queryForObject("SELECT count(*) FROM users WHERE demo_workspace_id IS NULL", Long.class)).isEqualTo(ordinaryUsers);
        assertThat(runtime.queryForObject("SELECT count(*) FROM tickets WHERE id = ?", Long.class, ticket)).isZero();
        // Leave the ordinary fixture snapshot unchanged for the provisioners' existing repeatability assertions.
        migration.update("DELETE FROM users WHERE demo_workspace_id = ?", active);
        migration.update("DELETE FROM demo_workspaces WHERE id = ?", active);
    }

    static void verifyRuntimeWorkflows(ConfigurableApplicationContext context) {
        var runtime = context.getBean(JdbcTemplate.class);
        var migration = new JdbcTemplate(context.getBean(Flyway.class).getConfiguration().getDataSource());
        var tickets = context.getBean(TicketService.class);
        var comments = context.getBean(TicketCommentService.class);
        var demo = new DemoWorkspaceService(runtime,
                context.getBean(UserAccountRepository.class),
                context.getBean(PasswordEncoder.class), tickets);
        var transaction = new TransactionTemplate(
                context.getBean(PlatformTransactionManager.class));
        var requester = transaction.execute(status -> demo.start(null));
        for (int i = 0; i < 7; i++) tickets.createTicket("Bounded", "Synthetic runtime", TicketPriority.LOW,
                TicketCategory.OTHER, requester.getEmail());
        assertThatThrownBy(() -> tickets.createTicket("Exhausted", "Synthetic runtime", TicketPriority.LOW,
                TicketCategory.OTHER, requester.getEmail()))
                .isInstanceOf(DemoUnavailableException.class);
        Long ticket = runtime.queryForObject("SELECT min(t.id) FROM tickets t JOIN users u ON u.id = t.requester_id WHERE u.demo_workspace_id = ?", Long.class, requester.getDemoWorkspaceId());
        for (int i = 0; i < 20; i++) comments.addComment(ticket, requester.getEmail(), "Synthetic restricted runtime");
        assertThatThrownBy(() -> comments.addComment(ticket, requester.getEmail(), "Exhausted"))
                .isInstanceOf(DemoUnavailableException.class);
        migration.update("UPDATE demo_workspaces SET created_at = statement_timestamp() - interval '29 minutes', expires_at = statement_timestamp() - interval '1 minute' WHERE id = ?", requester.getDemoWorkspaceId());
        assertThatThrownBy(() -> tickets.getTicket(ticket, requester.getEmail()))
                .isInstanceOf(DemoExpiredException.class);
        Integer removed = transaction.execute(status -> demo.cleanup());
        assertThat(removed).isEqualTo(1);
    }

    private static Long workspace(JdbcTemplate migration, boolean expired, String name) {
        Long id = migration.queryForObject("INSERT INTO demo_workspaces(created_at,expires_at) VALUES (statement_timestamp() - interval '29 minutes', "
                + "statement_timestamp() " + (expired ? "- interval '1 minute'" : "+ interval '1 minute'") + ") RETURNING id", Long.class);
        for (String role : List.of("REQUESTER", "AGENT")) {
            migration.update("INSERT INTO users(email,display_name,password_hash,role,demo_workspace_id,created_at,updated_at) "
                    + "VALUES (?,'Synthetic','disabled',?,?,now(),now())", name + "-" + role.toLowerCase(java.util.Locale.ROOT) + "@example.invalid", role, id);
        }
        return id;
    }
}
