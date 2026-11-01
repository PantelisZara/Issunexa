-- Internal SQL for provision-managed.sh; its libpq client enforces verify-full TLS.
-- pg_stat_ssl observes the backend hop, not TLS terminated by a provider proxy.
-- Never adopt existing unmarked objects or roles.
-- Suppress dynamic-statement contexts, which could otherwise expose passwords.
\set VERBOSITY terse
\set SHOW_CONTEXT never
\getenv target_database PGDATABASE
\getenv target_administrator PGUSER
\getenv application_schema ISSUNEXA_DB_SCHEMA
\getenv migration_role ISSUNEXA_FLYWAY_USERNAME
\getenv migration_password ISSUNEXA_FLYWAY_PASSWORD
\getenv runtime_role ISSUNEXA_DB_USERNAME
\getenv runtime_password ISSUNEXA_DB_PASSWORD
BEGIN;
SET LOCAL search_path = pg_catalog;
-- PostgreSQL grants the creator ADMIN automatically; request SET without INHERIT.
SET LOCAL createrole_self_grant = 'set';
SELECT pg_advisory_xact_lock(hashtext('issunexa-managed-provisioning'));
SELECT set_config('issunexa.managed.database', :'target_database', true) AS database_setting,
       set_config('issunexa.managed.administrator', :'target_administrator', true) AS administrator_setting,
       set_config('issunexa.application_schema', :'application_schema', true) AS schema_setting,
       set_config('issunexa.managed.migration_role', :'migration_role', true) AS migration_setting,
       set_config('issunexa.managed.migration_password', :'migration_password', true) AS migration_password_setting,
       set_config('issunexa.runtime_role', :'runtime_role', true) AS runtime_setting,
       set_config('issunexa.managed.runtime_password', :'runtime_password', true) AS runtime_password_setting
\gset

DO $managed$
DECLARE
    application_schema text := current_setting('issunexa.application_schema');
    migration_role text := current_setting('issunexa.managed.migration_role');
    runtime_role text := current_setting('issunexa.runtime_role');
    administrator oid := (SELECT oid FROM pg_roles WHERE rolname = current_user);
    database_id oid := (SELECT oid FROM pg_database WHERE datname = current_database());
    schema_id oid := (SELECT oid FROM pg_namespace WHERE nspname = application_schema);
    role_name text;
    role_count integer;
    marker text;
BEGIN
    IF current_database() <> current_setting('issunexa.managed.database')
       OR current_user <> current_setting('issunexa.managed.administrator')
       OR (SELECT datdba FROM pg_database WHERE oid = database_id) <> administrator
       OR NOT (SELECT NOT rolsuper AND rolcreaterole FROM pg_roles WHERE oid = administrator)
       OR current_setting('server_version_num')::integer < 160000 THEN
        RAISE EXCEPTION 'Requires PostgreSQL 16+ and the non-superuser database owner with CREATEROLE';
    END IF;
    FOREACH role_name IN ARRAY ARRAY[current_database(), current_user, application_schema, migration_role, runtime_role] LOOP
        IF role_name !~ '^[a-z][a-z0-9_]{0,62}$' OR role_name LIKE 'pg\_%' OR role_name LIKE 'neon\_%' THEN
            RAISE EXCEPTION 'Use dedicated lowercase identifiers outside reserved namespaces';
        END IF;
    END LOOP;
    IF migration_role = runtime_role OR current_user IN (migration_role, runtime_role)
       OR application_schema IN ('public', 'information_schema') THEN
        RAISE EXCEPTION 'Use distinct application identities and a dedicated non-public schema';
    END IF;
    -- A fresh dedicated database may have extension namespaces, never other applications.
    IF EXISTS (SELECT 1 FROM pg_namespace n WHERE n.nspname NOT IN ('public', 'information_schema', application_schema)
               AND left(n.nspname, 3) <> 'pg_'
               AND NOT EXISTS (SELECT 1 FROM pg_extension e WHERE e.extnamespace = n.oid))
       OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE n.nspname NOT IN ('information_schema', application_schema) AND left(n.nspname, 3) <> 'pg_' AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
                  AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass
                                  AND d.objid = c.oid AND d.deptype = 'e'))
       OR EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                  WHERE n.nspname NOT IN ('information_schema', application_schema) AND left(n.nspname, 3) <> 'pg_' AND NOT EXISTS (
                      SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e'))
       OR EXISTS (SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                  WHERE n.nspname NOT IN ('information_schema', application_schema) AND left(n.nspname, 3) <> 'pg_'
                    AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.deptype = 'e' AND
                        ((d.classid = 'pg_type'::regclass AND d.objid IN (t.oid, t.typelem))
                         OR (d.classid = 'pg_class'::regclass AND d.objid IN
                             (t.typrelid, (SELECT typrelid FROM pg_type WHERE oid = t.typelem)))))) THEN
        RAISE EXCEPTION 'Unexpected namespace or external objects; use a fresh dedicated database';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_namespace n,
                    LATERAL aclexplode(coalesce(n.nspacl, acldefault('n', n.nspowner))) a
               WHERE left(n.nspname, 3) <> 'pg_' AND n.nspname <> 'information_schema'
                 AND a.grantee = 0 AND a.privilege_type = 'CREATE')
       OR EXISTS (SELECT 1 FROM pg_database d,
                    LATERAL aclexplode(coalesce(d.datacl, acldefault('d', d.datdba))) a
                  WHERE d.oid = database_id AND a.grantee = 0 AND a.privilege_type = 'CREATE') THEN
        RAISE EXCEPTION 'Insecure PUBLIC namespace creation privileges; review the database manually';
    END IF;

    SELECT count(*) INTO role_count FROM pg_roles WHERE rolname IN (migration_role, runtime_role);
    IF NOT ((role_count = 0 AND schema_id IS NULL) OR (role_count = 2 AND schema_id IS NOT NULL)) THEN
        RAISE EXCEPTION 'Incomplete or colliding managed provisioning; choose unused role and schema names';
    END IF;
    FOREACH role_name IN ARRAY ARRAY[migration_role, runtime_role] LOOP
        marker := format('Issunexa managed %s for database %s schema %s administrator %s',
                         CASE WHEN role_name = migration_role THEN 'migration' ELSE 'runtime' END,
                         current_database(), application_schema, current_user);
        IF EXISTS (SELECT 1 FROM pg_roles r WHERE r.rolname = role_name
                   AND (shobj_description(r.oid, 'pg_authid') IS DISTINCT FROM marker
                        OR r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls
                        OR r.rolinherit OR NOT r.rolcanlogin)) THEN
            RAISE EXCEPTION 'Unmarked or elevated target role; no automatic adoption or repair';
        END IF;
        IF EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member WHERE r.rolname = role_name)
           OR EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid
                      WHERE r.rolname = role_name AND
                          (m.member <> administrator OR m.inherit_option))
           OR (role_count = 2 AND (NOT EXISTS (
               SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid
               WHERE r.rolname = role_name AND m.member = administrator AND m.admin_option)
               OR NOT pg_has_role(administrator, (SELECT oid FROM pg_roles WHERE rolname = role_name), 'SET'))) THEN
            RAISE EXCEPTION 'Unexpected application role membership or missing administrator options';
        END IF;
        IF EXISTS (SELECT 1 FROM pg_db_role_setting s JOIN pg_roles r ON r.oid = s.setrole,
                        LATERAL unnest(s.setconfig) config
                   WHERE r.rolname = role_name AND
                       (s.setdatabase <> database_id OR config <> 'search_path=' || application_schema)) THEN
            RAISE EXCEPTION 'Unexpected application role configuration';
        END IF;
    END LOOP;
    IF schema_id IS NOT NULL AND
       ((SELECT nspowner FROM pg_namespace WHERE oid = schema_id) <> (SELECT oid FROM pg_roles WHERE rolname = migration_role)
        OR obj_description(schema_id, 'pg_namespace') IS DISTINCT FROM
            format('Issunexa managed schema for database %s migrator %s runtime %s administrator %s',
                   current_database(), migration_role, runtime_role, current_user)) THEN
        RAISE EXCEPTION 'Unmarked schema or unexpected application schema owner';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_database d JOIN pg_roles r ON r.oid = d.datdba WHERE r.rolname IN (migration_role, runtime_role))
       OR EXISTS (SELECT 1 FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner
                  WHERE r.rolname = runtime_role OR (r.rolname = migration_role AND n.oid <> schema_id))
       OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE r.rolname = runtime_role OR (r.rolname = migration_role AND c.relnamespace <> schema_id
                        AND n.nspname <> 'pg_toast'))
       OR EXISTS (SELECT 1 FROM pg_proc p JOIN pg_roles r ON r.oid = p.proowner
                  WHERE r.rolname = runtime_role OR (r.rolname = migration_role AND NOT (
                    p.pronamespace = schema_id AND p.proname = 'expire_demo_workspaces'
                    AND p.pronargs = 0 AND p.prorettype = 'integer'::regtype AND p.prokind = 'f'
                    AND p.prosecdef AND p.provolatile = 'v'
                    AND p.prolang = (SELECT oid FROM pg_language WHERE lanname = 'plpgsql')
                    AND p.proconfig IS NOT DISTINCT FROM ARRAY['search_path=pg_catalog']::text[]))) THEN
        RAISE EXCEPTION 'Application roles own unexpected databases, schemas or objects';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_shdepend d JOIN pg_roles r ON r.oid = d.refobjid
               WHERE d.refclassid = 'pg_authid'::regclass AND d.deptype = 'o'
                 AND r.rolname IN (migration_role, runtime_role)
                 AND (r.rolname = runtime_role OR d.dbid <> database_id))
       OR EXISTS (SELECT 1 FROM pg_type t JOIN pg_roles r ON r.oid = t.typowner
                  WHERE r.rolname = runtime_role OR (r.rolname = migration_role AND t.typnamespace <> schema_id))
       OR EXISTS (SELECT 1 FROM pg_default_acl a JOIN pg_roles r ON r.oid = a.defaclrole
                  WHERE r.rolname IN (migration_role, runtime_role) OR a.defaclnamespace = schema_id) THEN
        RAISE EXCEPTION 'Unexpected ownership or default privileges; review manually';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_namespace n CROSS JOIN pg_roles r
               WHERE r.rolname IN (migration_role, runtime_role) AND left(n.nspname, 3) <> 'pg_'
                 AND n.nspname <> 'information_schema' AND (n.oid <> schema_id OR r.rolname = runtime_role)
                 AND has_schema_privilege(r.oid, n.oid, 'CREATE'))
       OR EXISTS (SELECT 1 FROM pg_roles r WHERE r.rolname IN (migration_role, runtime_role)
                  AND has_database_privilege(r.oid, database_id, 'CREATE')) THEN
        RAISE EXCEPTION 'Application roles have unexpected namespace creation privileges';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class c WHERE c.relnamespace = schema_id AND
               (c.relowner <> (SELECT oid FROM pg_roles WHERE rolname = migration_role)
                OR NOT ((c.relkind = 'r' AND c.relname IN
                    ('users', 'tickets', 'ticket_comments', 'ticket_history_entries', 'demo_workspaces', 'flyway_schema_history'))
                   OR (c.relkind = 'S' AND EXISTS (
                       SELECT 1 FROM pg_depend d JOIN pg_class t ON t.oid = d.refobjid
                       WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid
                         AND d.refclassid = 'pg_class'::regclass AND d.deptype IN ('a', 'i')
                         AND t.relnamespace = schema_id AND t.relname IN
                             ('users', 'tickets', 'ticket_comments', 'ticket_history_entries', 'demo_workspaces')))
                   OR (c.relkind = 'i' AND c.relname IN ('pk_users', 'uk_users_email', 'pk_tickets',
                       'idx_tickets_requester_id', 'pk_ticket_comments', 'idx_ticket_comments_ticket_created_id',
                       'pk_ticket_history_entries', 'idx_ticket_history_ticket_created_id',
                       'pk_demo_workspaces', 'uk_users_demo_workspace_role', 'flyway_schema_history_pk', 'flyway_schema_history_s_idx')))))
       OR EXISTS (SELECT 1 FROM pg_proc p WHERE p.pronamespace = schema_id AND NOT (p.proname = 'expire_demo_workspaces' AND p.pronargs = 0 AND p.prorettype = 'integer'::regtype
                    AND p.prokind = 'f' AND p.prosecdef AND p.provolatile = 'v'
                    AND p.prolang = (SELECT oid FROM pg_language WHERE lanname = 'plpgsql')
                    AND p.proconfig IS NOT DISTINCT FROM ARRAY['search_path=pg_catalog']::text[]
                    AND p.proowner = (SELECT oid FROM pg_roles WHERE rolname = migration_role)))
       OR EXISTS (SELECT 1 FROM pg_type t WHERE t.typnamespace = schema_id AND t.typrelid = 0 AND t.typelem = 0) THEN
        RAISE EXCEPTION 'Unexpected objects or owners in the application schema';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_attribute attribute JOIN pg_class column_table ON column_table.oid = attribute.attrelid,
                         LATERAL aclexplode(attribute.attacl) grant_entry
               WHERE column_table.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = application_schema)
                 AND NOT (column_table.relkind = 'r' AND column_table.relname = 'tickets'
                     AND attribute.attname IN ('title', 'description', 'status', 'priority', 'assignee_id', 'version', 'updated_at')
                     AND grant_entry.grantee IS NOT DISTINCT FROM (SELECT oid FROM pg_roles WHERE rolname = runtime_role)
                     AND grant_entry.privilege_type = 'UPDATE' AND NOT grant_entry.is_grantable))
       OR EXISTS (SELECT 1 FROM pg_class c, LATERAL aclexplode(c.relacl) a JOIN pg_roles r ON r.oid = a.grantee
               WHERE r.rolname IN (migration_role, runtime_role) AND c.relnamespace IS DISTINCT FROM schema_id)
       OR EXISTS (SELECT 1 FROM pg_attribute c JOIN pg_class t ON t.oid = c.attrelid,
                         LATERAL aclexplode(c.attacl) a JOIN pg_roles r ON r.oid = a.grantee
                  WHERE r.rolname IN (migration_role, runtime_role) AND t.relnamespace IS DISTINCT FROM schema_id)
       OR EXISTS (SELECT 1 FROM pg_proc p, LATERAL aclexplode(p.proacl) a JOIN pg_roles r ON r.oid = a.grantee
                  WHERE r.rolname IN (migration_role, runtime_role) AND p.pronamespace IS DISTINCT FROM schema_id)
       OR EXISTS (SELECT 1 FROM pg_type t, LATERAL aclexplode(t.typacl) a JOIN pg_roles r ON r.oid = a.grantee
                  WHERE r.rolname IN (migration_role, runtime_role) AND t.typnamespace IS DISTINCT FROM schema_id)
       OR EXISTS (SELECT 1 FROM pg_namespace n, LATERAL aclexplode(n.nspacl) a JOIN pg_roles r ON r.oid = a.grantee
                  WHERE r.rolname IN (migration_role, runtime_role) AND n.oid IS DISTINCT FROM schema_id)
       OR EXISTS (SELECT 1 FROM pg_database d, LATERAL aclexplode(d.datacl) a JOIN pg_roles r ON r.oid = a.grantee
                  WHERE r.rolname IN (migration_role, runtime_role) AND (d.oid <> database_id OR a.privilege_type <> 'CONNECT'))
       OR EXISTS (SELECT 1 FROM pg_default_acl d, LATERAL aclexplode(d.defaclacl) a JOIN pg_roles r ON r.oid = a.grantee
                  WHERE r.rolname IN (migration_role, runtime_role)) THEN
        RAISE EXCEPTION 'Unexpected explicit application grants outside the managed policy';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_namespace n, LATERAL aclexplode(n.nspacl) a
               WHERE n.oid = schema_id AND a.grantee <> (SELECT oid FROM pg_roles WHERE rolname = migration_role)
                 AND (a.grantee IS DISTINCT FROM (SELECT oid FROM pg_roles WHERE rolname = runtime_role) OR a.privilege_type <> 'USAGE' OR a.is_grantable))
       OR EXISTS (SELECT 1 FROM pg_class c, LATERAL aclexplode(c.relacl) a
                  WHERE c.relnamespace = schema_id AND a.grantee <> (SELECT oid FROM pg_roles WHERE rolname = migration_role)
                    AND (a.grantee IS DISTINCT FROM (SELECT oid FROM pg_roles WHERE rolname = runtime_role) OR a.is_grantable OR NOT (
                        (c.relkind = 'S' AND a.privilege_type IN ('USAGE', 'SELECT'))
                        OR (c.relkind = 'r' AND c.relname IN
                            ('users', 'tickets', 'ticket_comments', 'ticket_history_entries', 'demo_workspaces')
                            AND (a.privilege_type IN ('SELECT', 'INSERT')
                                 OR (c.relname = 'tickets' AND a.privilege_type = 'UPDATE'))))))
       OR EXISTS (SELECT 1 FROM pg_type t, LATERAL aclexplode(t.typacl) a
                  WHERE t.typnamespace = schema_id AND a.grantee <> (SELECT oid FROM pg_roles WHERE rolname = migration_role)) THEN
        RAISE EXCEPTION 'Unexpected managed namespace grants; review grantees and privileges manually';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname = application_schema AND (NOT (p.proname = 'expire_demo_workspaces' AND p.pronargs = 0 AND p.prorettype = 'integer'::regtype
                    AND p.prokind = 'f' AND p.prosecdef AND p.provolatile = 'v'
                    AND p.prolang = (SELECT oid FROM pg_language WHERE lanname = 'plpgsql')
                    AND p.proconfig IS NOT DISTINCT FROM ARRAY['search_path=pg_catalog']::text[])
                   OR p.proowner <> (SELECT oid FROM pg_roles WHERE rolname = migration_role)))
       OR EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace,
                         LATERAL aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
                  WHERE n.nspname = application_schema AND a.grantee <> p.proowner
                    AND (a.grantee IS DISTINCT FROM (SELECT oid FROM pg_roles WHERE rolname = runtime_role)
                         OR a.privilege_type <> 'EXECUTE' OR a.is_grantable)) THEN
        RAISE EXCEPTION 'Unexpected demo routine ownership, shape or grants';
    END IF;

    -- All preflight checks precede persistent changes; any later error rolls back too.
    FOREACH role_name IN ARRAY ARRAY[migration_role, runtime_role] LOOP
        IF role_count = 0 THEN
            EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS', role_name);
            EXECUTE format('COMMENT ON ROLE %I IS %L', role_name,
                format('Issunexa managed %s for database %s schema %s administrator %s',
                       CASE WHEN role_name = migration_role THEN 'migration' ELSE 'runtime' END,
                       current_database(), application_schema, current_user));
        END IF;
        EXECUTE format('ALTER ROLE %I PASSWORD %L', role_name,
                       current_setting(CASE WHEN role_name = migration_role THEN
                           'issunexa.managed.migration_password' ELSE 'issunexa.managed.runtime_password' END));
        EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path = %I', role_name, current_database(), application_schema);
        EXECUTE format('REVOKE ALL ON DATABASE %I FROM %I', current_database(), role_name);
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), role_name);
    END LOOP;
    EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC', current_database());
    IF schema_id IS NULL THEN
        EXECUTE format('CREATE SCHEMA %I AUTHORIZATION %I', application_schema, migration_role);
        -- Object ownership operations use SET ROLE, which does not apply login defaults.
        EXECUTE format('SET LOCAL ROLE %I', migration_role);
        EXECUTE format('COMMENT ON SCHEMA %I IS %L', application_schema,
            format('Issunexa managed schema for database %s migrator %s runtime %s administrator %s',
                   current_database(), migration_role, runtime_role, current_setting('issunexa.managed.administrator')));
        RESET ROLE;
    END IF;
    EXECUTE format('SET LOCAL ROLE %I', migration_role);
    EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC, %I', application_schema, runtime_role);
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', application_schema, runtime_role);
END
$managed$;
-- Run as schema/table owner, never using a provider administrator's broad grants.
\ir ../src/main/resources/db/migration/afterMigrate__runtime_privileges.sql
RESET ROLE;
DO $verify$
DECLARE
    schema_name text := current_setting('issunexa.application_schema');
    migration_name text := current_setting('issunexa.managed.migration_role');
    runtime_name text := current_setting('issunexa.runtime_role');
BEGIN
    IF has_database_privilege(runtime_name, current_database(), 'CREATE')
       OR has_database_privilege(runtime_name, current_database(), 'TEMPORARY')
       OR has_database_privilege(migration_name, current_database(), 'CREATE')
       OR has_database_privilege(migration_name, current_database(), 'TEMPORARY')
       OR NOT has_database_privilege(runtime_name, current_database(), 'CONNECT')
       OR NOT has_database_privilege(migration_name, current_database(), 'CONNECT')
       OR NOT has_schema_privilege(runtime_name, schema_name, 'USAGE')
       OR has_schema_privilege(runtime_name, schema_name, 'CREATE')
       OR NOT has_schema_privilege(migration_name, schema_name, 'CREATE') THEN
        RAISE EXCEPTION 'Managed privilege verification failed; all provisioning changes rolled back';
    END IF;
END
$verify$;
COMMIT;
