-- Explicit grants after every migrate(), including when no version is pending.
-- No broad default grants: newly added application tables need a deliberate update.
DO $grants$
DECLARE
    runtime_role text := nullif(current_setting('issunexa.runtime_role', true), '');
    table_name text;
    sequence_name text;
BEGIN
    -- Ordinary Testcontainers migration tests have no provisioned runtime role.
    IF runtime_role IS NULL THEN
        RETURN;
    END IF;
    IF runtime_role = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_role) THEN
        RAISE EXCEPTION 'Invalid provisioned runtime role';
    END IF;
    FOREACH table_name IN ARRAY ARRAY['users', 'tickets', 'ticket_comments', 'ticket_history_entries'] LOOP
        IF to_regclass(format('public.%I', table_name)) IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON TABLE public.%I FROM PUBLIC, %I', table_name, runtime_role);
            EXECUTE format('GRANT SELECT, INSERT ON TABLE public.%I TO %I', table_name, runtime_role);
            IF table_name = 'tickets' THEN
                EXECUTE format('GRANT UPDATE ON TABLE public.%I TO %I', table_name, runtime_role);
            END IF;
            sequence_name := pg_get_serial_sequence(format('public.%I', table_name), 'id');
            IF sequence_name IS NOT NULL THEN
                EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM PUBLIC, %I', sequence_name, runtime_role);
                EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO %I', sequence_name, runtime_role);
            END IF;
        END IF;
    END LOOP;
    IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
        REVOKE ALL ON TABLE public.flyway_schema_history FROM PUBLIC;
        EXECUTE format('REVOKE ALL ON TABLE public.flyway_schema_history FROM %I', runtime_role);
    END IF;
END
$grants$;
