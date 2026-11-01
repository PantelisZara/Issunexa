-- Explicit grants after every migrate(), including when no version is pending.
-- No broad default grants: newly added application tables need a deliberate update.
DO $grants$
DECLARE
    runtime_role text := nullif(current_setting('issunexa.runtime_role', true), '');
    application_schema text := coalesce(nullif(current_setting('issunexa.application_schema', true), ''), 'public');
    migration_marker text;
    schema_marker text;
    marker_parts text[];
    managed_schema_id oid;
    managed_migration_id oid;
    managed_runtime_id oid;
    table_name text;
    sequence_name text;
BEGIN
    IF runtime_role IS NULL THEN
        -- Persistent custom GUC defaults require parameter privileges unavailable
        -- to ordinary managed owners. Resolve only this connection's marked schema.
        application_schema := current_schema();
        SELECT shobj_description(oid, 'pg_authid') INTO migration_marker FROM pg_roles WHERE rolname = current_user;
        SELECT obj_description(oid, 'pg_namespace') INTO schema_marker FROM pg_namespace WHERE nspname = application_schema;
        -- Plain migration tests have neither local role settings nor managed markers.
        IF coalesce(migration_marker, '') NOT LIKE 'Issunexa managed %'
           AND coalesce(schema_marker, '') NOT LIKE 'Issunexa managed %' THEN
            RETURN;
        END IF;
        marker_parts := regexp_match(schema_marker,
            '^Issunexa managed schema for database ([a-z][a-z0-9_]{0,62}) migrator ([a-z][a-z0-9_]{0,62}) runtime ([a-z][a-z0-9_]{0,62}) administrator ([a-z][a-z0-9_]{0,62})$');
        IF marker_parts IS NULL OR marker_parts[1] <> current_database() OR marker_parts[2] <> current_user
           OR marker_parts[4] <> (SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database())
           OR (SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = application_schema) <> current_user
           OR migration_marker IS DISTINCT FROM format('Issunexa managed migration for database %s schema %s administrator %s',
                current_database(), application_schema, marker_parts[4]) THEN
            RAISE EXCEPTION 'Invalid or missing managed migration/schema markers';
        END IF;
        runtime_role := marker_parts[3];
        IF (SELECT shobj_description(oid, 'pg_authid') FROM pg_roles WHERE rolname = runtime_role) IS DISTINCT FROM
            format('Issunexa managed runtime for database %s schema %s administrator %s',
                   current_database(), application_schema, marker_parts[4]) THEN
            RAISE EXCEPTION 'Invalid or missing managed runtime marker';
        END IF;
        -- Table-level REVOKE does not remove column grants. Managed startup must
        -- reject them even when no provisioning rerun or versioned migration occurs.
        IF EXISTS (SELECT 1 FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
                   WHERE c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = application_schema)
                     AND a.attacl IS NOT NULL) THEN
            RAISE EXCEPTION 'Unexpected managed application column grants';
        END IF;
        SELECT oid INTO managed_schema_id FROM pg_namespace WHERE nspname = application_schema;
        SELECT oid INTO managed_migration_id FROM pg_roles WHERE rolname = current_user;
        SELECT oid INTO managed_runtime_id FROM pg_roles WHERE rolname = runtime_role;
        -- Preserve the owned namespace: never normalize or retain outsider grants.
        IF EXISTS (SELECT 1 FROM pg_namespace n, LATERAL aclexplode(n.nspacl) a
                   WHERE n.oid = managed_schema_id AND a.grantee <> managed_migration_id
                     AND (a.grantee <> managed_runtime_id OR a.privilege_type <> 'USAGE' OR a.is_grantable))
           OR EXISTS (SELECT 1 FROM pg_class c, LATERAL aclexplode(c.relacl) a
                      WHERE c.relnamespace = managed_schema_id AND a.grantee <> managed_migration_id
                        AND (a.grantee <> managed_runtime_id OR a.is_grantable OR NOT (
                            (c.relkind = 'S' AND a.privilege_type IN ('USAGE', 'SELECT'))
                            OR (c.relkind = 'r' AND c.relname IN
                                ('users', 'tickets', 'ticket_comments', 'ticket_history_entries')
                                AND (a.privilege_type IN ('SELECT', 'INSERT')
                                     OR (c.relname = 'tickets' AND a.privilege_type = 'UPDATE'))))))
           OR EXISTS (SELECT 1 FROM pg_type t, LATERAL aclexplode(t.typacl) a
                      WHERE t.typnamespace = managed_schema_id AND a.grantee <> managed_migration_id) THEN
            RAISE EXCEPTION 'Unexpected managed namespace grants; review grantees and privileges manually';
        END IF;
    END IF;
    IF runtime_role = current_user OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_role) THEN
        RAISE EXCEPTION 'Invalid provisioned runtime role';
    END IF;
    FOREACH table_name IN ARRAY ARRAY['users', 'tickets', 'ticket_comments', 'ticket_history_entries'] LOOP
        IF to_regclass(format('%I.%I', application_schema, table_name)) IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON TABLE %I.%I FROM PUBLIC, %I', application_schema, table_name, runtime_role);
            EXECUTE format('GRANT SELECT, INSERT ON TABLE %I.%I TO %I', application_schema, table_name, runtime_role);
            IF table_name = 'tickets' THEN
                EXECUTE format('GRANT UPDATE ON TABLE %I.%I TO %I', application_schema, table_name, runtime_role);
            END IF;
            sequence_name := pg_get_serial_sequence(format('%I.%I', application_schema, table_name), 'id');
            IF sequence_name IS NOT NULL THEN
                EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM PUBLIC, %I', sequence_name, runtime_role);
                EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE %s TO %I', sequence_name, runtime_role);
            END IF;
        END IF;
    END LOOP;
    IF to_regclass(format('%I.flyway_schema_history', application_schema)) IS NOT NULL THEN
        EXECUTE format('REVOKE ALL ON TABLE %I.flyway_schema_history FROM PUBLIC, %I', application_schema, runtime_role);
    END IF;
END
$grants$;
