CREATE TABLE demo_workspaces (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_demo_workspaces PRIMARY KEY (id),
    CONSTRAINT ck_demo_workspaces_lifetime CHECK (expires_at > created_at
        AND expires_at <= created_at + interval '30 minutes')
);
ALTER TABLE users ADD COLUMN demo_workspace_id BIGINT;
ALTER TABLE users ADD CONSTRAINT fk_users_demo_workspace FOREIGN KEY (demo_workspace_id) REFERENCES demo_workspaces(id);
ALTER TABLE users ADD CONSTRAINT ck_users_demo_role CHECK (demo_workspace_id IS NULL OR role IN ('REQUESTER', 'AGENT'));
CREATE UNIQUE INDEX uk_users_demo_workspace_role ON users (demo_workspace_id, role);

-- Qualify the actual migration schema, including a managed non-public schema.
-- No request value, cutoff, workspace identifier or SQL is accepted by this routine.
DO $migration$
BEGIN
    EXECUTE format($definition$
        CREATE FUNCTION %1$I.expire_demo_workspaces() RETURNS integer
        LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog
        AS $cleanup$
        DECLARE
            expired_ids bigint[];
            removed integer;
            workspace_id bigint;
        BEGIN
            PERFORM pg_catalog.pg_advisory_xact_lock(73629401915001);
            SELECT pg_catalog.array_agg(candidate.id) INTO expired_ids FROM (
                SELECT w.id FROM %1$I.demo_workspaces w
                WHERE w.expires_at <= pg_catalog.clock_timestamp()
                  AND (SELECT count(*) FROM %1$I.users u WHERE u.demo_workspace_id = w.id) = 2
                  -- Refuse inconsistent references rather than deleting ordinary or other visitors' rows.
                  AND NOT EXISTS (
                    SELECT 1 FROM %1$I.tickets t
                    LEFT JOIN %1$I.users r ON r.id = t.requester_id
                    LEFT JOIN %1$I.users a ON a.id = t.assignee_id
                    WHERE (r.demo_workspace_id = w.id AND t.assignee_id IS NOT NULL
                                AND a.demo_workspace_id IS DISTINCT FROM w.id)
                       OR (r.demo_workspace_id IS DISTINCT FROM w.id AND a.demo_workspace_id = w.id))
                  AND NOT EXISTS (
                    SELECT 1 FROM %1$I.ticket_comments c
                    JOIN %1$I.tickets t ON t.id = c.ticket_id
                    LEFT JOIN %1$I.users r ON r.id = t.requester_id
                    JOIN %1$I.users a ON a.id = c.author_id
                    WHERE (r.demo_workspace_id = w.id AND a.demo_workspace_id IS DISTINCT FROM w.id)
                       OR (r.demo_workspace_id IS DISTINCT FROM w.id AND a.demo_workspace_id = w.id))
                  AND NOT EXISTS (
                    SELECT 1 FROM %1$I.ticket_history_entries h
                    JOIN %1$I.tickets t ON t.id = h.ticket_id
                    LEFT JOIN %1$I.users r ON r.id = t.requester_id
                    JOIN %1$I.users a ON a.id = h.actor_id
                    LEFT JOIN %1$I.users s ON s.id = h.assignee_id
                    WHERE (r.demo_workspace_id = w.id AND (a.demo_workspace_id IS DISTINCT FROM w.id
                        OR (h.assignee_id IS NOT NULL AND s.demo_workspace_id IS DISTINCT FROM w.id)))
                       OR (r.demo_workspace_id IS DISTINCT FROM w.id
                            AND (a.demo_workspace_id = w.id OR s.demo_workspace_id = w.id)))
                ORDER BY w.id LIMIT 5 FOR UPDATE OF w SKIP LOCKED
            ) candidate;
            IF expired_ids IS NULL THEN RETURN 0; END IF;
            FOREACH workspace_id IN ARRAY expired_ids LOOP
                PERFORM pg_catalog.pg_advisory_xact_lock(736294019, pg_catalog.hashtext(workspace_id::text));
            END LOOP;
            DELETE FROM %1$I.ticket_history_entries h USING %1$I.tickets t, %1$I.users u
                WHERE h.ticket_id = t.id AND t.requester_id = u.id AND u.demo_workspace_id = ANY(expired_ids);
            DELETE FROM %1$I.ticket_comments c USING %1$I.tickets t, %1$I.users u
                WHERE c.ticket_id = t.id AND t.requester_id = u.id AND u.demo_workspace_id = ANY(expired_ids);
            DELETE FROM %1$I.tickets t USING %1$I.users u
                WHERE t.requester_id = u.id AND u.demo_workspace_id = ANY(expired_ids);
            DELETE FROM %1$I.users WHERE demo_workspace_id = ANY(expired_ids);
            DELETE FROM %1$I.demo_workspaces WHERE id = ANY(expired_ids);
            GET DIAGNOSTICS removed = ROW_COUNT;
            RETURN removed;
        END
        $cleanup$;
    $definition$, current_schema());
    EXECUTE format('REVOKE ALL ON FUNCTION %I.expire_demo_workspaces() FROM PUBLIC', current_schema());
END
$migration$;
