\getenv requested_schema ISSUNEXA_DB_SCHEMA
\getenv requested_database PGDATABASE
\getenv requested_user PGUSER
\getenv requested_action ISSUNEXA_RECOVERY_ACTION
SELECT set_config('issunexa.recovery_schema', :'requested_schema', true) AS recovery_schema,
       set_config('issunexa.recovery_database', :'requested_database', true) AS recovery_database,
       set_config('issunexa.recovery_user', :'requested_user', true) AS recovery_user,
       set_config('issunexa.recovery_action', :'requested_action', true) AS recovery_action
\gset
DO $guard$
DECLARE
    schema_name text := current_setting('issunexa.recovery_schema');
    schema_id oid := (SELECT oid FROM pg_namespace WHERE nspname = schema_name);
    schema_marker text := obj_description(schema_id, 'pg_namespace');
    parts text[];
    administrator text := (SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database());
BEGIN
    IF current_database() <> current_setting('issunexa.recovery_database')
       OR current_user <> current_setting('issunexa.recovery_user')
       OR current_setting('server_version_num')::integer NOT BETWEEN 180000 AND 189999
       OR NOT EXISTS (SELECT 1 FROM pg_stat_ssl WHERE pid = pg_backend_pid() AND ssl)
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND rolcanlogin
                      AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit))
       OR (SELECT nspowner FROM pg_namespace WHERE oid = schema_id) IS DISTINCT FROM
          (SELECT oid FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION 'Recovery requires PostgreSQL18, TLS and the marked schema-owning migrator';
    END IF;
    parts := regexp_match(schema_marker,
        '^Issunexa managed schema for database ([a-z][a-z0-9_]{0,62}) migrator ([a-z][a-z0-9_]{0,62}) runtime ([a-z][a-z0-9_]{0,62}) administrator ([a-z][a-z0-9_]{0,62})$');
    IF parts IS NULL OR parts[1] <> current_database() OR parts[2] <> current_user OR parts[4] <> administrator
       OR (SELECT shobj_description(oid, 'pg_authid') FROM pg_roles WHERE rolname = current_user) IS DISTINCT FROM
          format('Issunexa managed migration for database %s schema %s administrator %s', current_database(), schema_name, administrator)
       OR (SELECT shobj_description(oid, 'pg_authid') FROM pg_roles WHERE rolname = parts[3]) IS DISTINCT FROM
          format('Issunexa managed runtime for database %s schema %s administrator %s', current_database(), schema_name, administrator) THEN
        RAISE EXCEPTION 'Missing or inconsistent managed recovery markers';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN (parts[2], parts[3])
               AND (NOT rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit))
       OR EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member
                  WHERE r.rolname IN (parts[2], parts[3]))
       OR EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid
                  WHERE r.rolname IN (parts[2], parts[3]) AND
                        (pg_get_userbyid(m.member) <> administrator OR m.inherit_option))
       OR EXISTS (SELECT 1 FROM pg_default_acl WHERE defaclnamespace = schema_id
                  OR defaclrole IN (SELECT oid FROM pg_roles WHERE rolname IN (parts[2], parts[3]))) THEN
        RAISE EXCEPTION 'Unexpected managed recovery role privileges or default grants';
    END IF;
    IF current_setting('issunexa.recovery_action') = 'restore' THEN
        PERFORM pg_advisory_xact_lock(hashtext('issunexa-managed-provisioning'));
        -- Includes collations/operators/text-search objects, not only relations/routines/types.
        IF EXISTS (SELECT 1 FROM pg_depend WHERE refclassid = 'pg_namespace'::regclass AND refobjid = schema_id) THEN
            RAISE EXCEPTION 'Restore requires a completely empty provisioned application schema';
        END IF;
    ELSIF to_regclass(format('%I.flyway_schema_history', schema_name)) IS NULL THEN
        RAISE EXCEPTION 'Backup requires a migrated application schema';
    END IF;
END
$guard$;
