-- Restore-only initialization: archive ACLs are intentionally not imported.
-- The guard proved this schema empty; this routine was created by this transaction.
-- Remove only PostgreSQL's PUBLIC default on the known cleanup signature.
-- The following shared callback still validates all shapes, owners and grants.
DO $restore_grants$
DECLARE
    application_schema text := current_setting('issunexa.recovery_schema');
BEGIN
    IF to_regprocedure(format('%I.expire_demo_workspaces()', application_schema)) IS NOT NULL THEN
        EXECUTE format('REVOKE ALL ON FUNCTION %I.expire_demo_workspaces() FROM PUBLIC', application_schema);
    END IF;
END
$restore_grants$;
