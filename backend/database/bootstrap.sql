-- Administrative provisioning, deliberately outside Flyway versioned migrations.
-- No tables/data are created, deleted or reset here. All changes are transactional.
\getenv migration_role ISSUNEXA_FLYWAY_USERNAME
\getenv migration_password ISSUNEXA_FLYWAY_PASSWORD
\getenv runtime_role ISSUNEXA_DB_USERNAME
\getenv runtime_password ISSUNEXA_DB_PASSWORD
BEGIN;
SELECT pg_advisory_xact_lock(hashtext('issunexa-database-bootstrap'));
-- \gset suppresses secret-valued result output. psql tracing must remain disabled.
SELECT set_config('issunexa.bootstrap.migration_role', :'migration_role', true) AS migration_name_setting,
       set_config('issunexa.bootstrap.migration_password', :'migration_password', true) AS migration_password_setting,
       set_config('issunexa.bootstrap.runtime_role', :'runtime_role', true) AS runtime_name_setting,
       set_config('issunexa.bootstrap.runtime_password', :'runtime_password', true) AS runtime_password_setting,
       set_config('issunexa.runtime_role', :'runtime_role', true) AS runtime_grants_setting
\gset

DO $bootstrap$
DECLARE
    migration_role text := current_setting('issunexa.bootstrap.migration_role');
    runtime_role text := current_setting('issunexa.bootstrap.runtime_role');
    role_name text;
    relation record;
    database_owner oid := (SELECT datdba FROM pg_database WHERE datname = current_database());
BEGIN
    IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION 'Bootstrap requires an administrator with superuser privileges';
    END IF;
    -- pg_ names are reserved for PostgreSQL system/internal/temporary schemas.
    -- Reject shared databases before changing roles or any database-wide PUBLIC ACL.
    IF EXISTS (SELECT 1 FROM pg_namespace
               WHERE nspname NOT IN ('public', 'information_schema') AND left(nspname, 3) <> 'pg_') THEN
        RAISE EXCEPTION 'Unexpected user-defined schema outside public; review shared/customized database manually';
    END IF;
    IF migration_role !~ '^[a-z][a-z0-9_]{0,62}$' OR runtime_role !~ '^[a-z][a-z0-9_]{0,62}$'
       OR migration_role = runtime_role OR current_user IN (migration_role, runtime_role)
       OR migration_role LIKE 'pg\_%' OR runtime_role LIKE 'pg\_%' THEN
        RAISE EXCEPTION 'Use distinct dedicated lowercase migration/runtime role names (not admin or pg_*)';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN (migration_role, runtime_role)
               AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
       OR EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r
                  ON r.oid = m.member OR r.oid = m.roleid
                  WHERE r.rolname IN (migration_role, runtime_role)) THEN
        RAISE EXCEPTION 'Existing target roles have elevated privileges or memberships; choose dedicated roles';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN (migration_role, runtime_role)
               AND shobj_description(oid, 'pg_authid') IS DISTINCT FROM
                   format('Issunexa %s role for database %s',
                          CASE WHEN rolname = migration_role THEN 'migration' ELSE 'runtime' END,
                          current_database())) THEN
        RAISE EXCEPTION 'Target role name already exists outside this bootstrap; choose unused role names';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_database d JOIN pg_roles r ON r.oid = d.datdba
               WHERE r.rolname IN (migration_role, runtime_role))
       OR EXISTS (SELECT 1 FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner
                  WHERE r.rolname = runtime_role OR (r.rolname = migration_role AND n.nspname <> 'public'))
       OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner
                  WHERE r.rolname = runtime_role) THEN
        RAISE EXCEPTION 'Target roles must not own databases or unrelated schemas; runtime must own no relations';
    END IF;
    -- This procedure supports the dedicated Issunexa public schema, not shared databases.
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
               AND NOT (c.relkind = 'r' AND c.relname IN
                   ('users', 'tickets', 'ticket_comments', 'ticket_history_entries', 'flyway_schema_history'))
               AND NOT (c.relkind = 'S' AND EXISTS (
                   SELECT 1 FROM pg_depend d JOIN pg_class t ON t.oid = d.refobjid
                   JOIN pg_namespace tn ON tn.oid = t.relnamespace
                   WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid
                     AND d.refclassid = 'pg_class'::regclass AND d.deptype IN ('a', 'i')
                     AND tn.nspname = 'public'
                     AND t.relname IN ('users', 'tickets', 'ticket_comments', 'ticket_history_entries'))))
       OR EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                  WHERE n.nspname = 'public') THEN
        RAISE EXCEPTION 'Unexpected objects in public schema; review a custom privilege upgrade manually';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner
               WHERE n.nspname = 'public' AND n.nspowner NOT IN
                   (database_owner, (SELECT oid FROM pg_roles WHERE rolname = current_user))
                 AND r.rolname NOT IN ('pg_database_owner', migration_role))
       OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_roles r ON r.oid = c.relowner
                  WHERE n.nspname = 'public' AND c.relkind = 'r'
                    AND c.relowner NOT IN (database_owner, (SELECT oid FROM pg_roles WHERE rolname = current_user))
                    AND r.rolname <> migration_role) THEN
        RAISE EXCEPTION 'Unexpected existing schema/table owner; review ownership before upgrading';
    END IF;

    FOREACH role_name IN ARRAY ARRAY[migration_role, runtime_role] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
            EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS', role_name);
            EXECUTE format('COMMENT ON ROLE %I IS %L', role_name,
                           format('Issunexa %s role for database %s',
                                  CASE WHEN role_name = migration_role THEN 'migration' ELSE 'runtime' END,
                                  current_database()));
        END IF;
    END LOOP;
    EXECUTE format('ALTER ROLE %I LOGIN NOINHERIT PASSWORD %L', migration_role,
                   current_setting('issunexa.bootstrap.migration_password'));
    EXECUTE format('ALTER ROLE %I LOGIN NOINHERIT PASSWORD %L', runtime_role,
                   current_setting('issunexa.bootstrap.runtime_password'));
    -- The migration session passes the role name to afterMigrate without SQL placeholders.
    EXECUTE format('ALTER ROLE %I IN DATABASE %I SET issunexa.runtime_role = %L',
                   migration_role, current_database(), runtime_role);
    EXECUTE format('REVOKE ALL ON DATABASE %I FROM %I, %I', current_database(), migration_role, runtime_role);
    EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I, %I', current_database(), migration_role, runtime_role);
    EXECUTE format('ALTER SCHEMA public OWNER TO %I', migration_role);
    REVOKE ALL ON SCHEMA public FROM PUBLIC;
    EXECUTE format('REVOKE ALL ON SCHEMA public FROM %I', runtime_role);
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', runtime_role);
    FOR relation IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relkind = 'r' LOOP
        -- PostgreSQL transfers attached identity sequence ownership along with the table.
        EXECUTE format('ALTER TABLE public.%I OWNER TO %I', relation.relname, migration_role);
        EXECUTE format('REVOKE ALL ON TABLE public.%I FROM PUBLIC, %I', relation.relname, runtime_role);
    END LOOP;
END
$bootstrap$;
-- Reuse Flyway's grant policy in the same transaction, including on repeat runs.
\ir ../src/main/resources/db/migration/afterMigrate__runtime_privileges.sql
COMMIT;
