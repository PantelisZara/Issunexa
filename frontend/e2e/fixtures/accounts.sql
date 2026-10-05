-- Synthetic E2E-only accounts. Password: E2E-only-Issunexa-032!
-- Spring Security's delegating encoder requires the {bcrypt} prefix.
-- Applied only by run-e2e.sh to its new disposable database, never by Flyway.
BEGIN;
INSERT INTO users (email, display_name, password_hash, role, created_at, updated_at)
SELECT email, display_name,
       '{bcrypt}$2a$10$FUJC3PV/SpauB66UFXO0nuHzdnkUo9CY6sqHkTjS2qUcAKUA6L2G.',
       role, now(), now()
FROM (VALUES
    ('requester-a@e2e.invalid', 'E2E Requester A', 'REQUESTER'),
    ('requester-b@e2e.invalid', 'E2E Requester B', 'REQUESTER'),
    ('agent@e2e.invalid', 'E2E Agent', 'AGENT'),
    ('admin@e2e.invalid', 'E2E Admin', 'ADMIN'),
    ('throttle@e2e.invalid', 'E2E Throttle', 'REQUESTER')
) AS accounts(email, display_name, role);
COMMIT;
