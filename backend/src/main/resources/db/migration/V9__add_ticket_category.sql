ALTER TABLE tickets ADD COLUMN category VARCHAR(20);

UPDATE tickets SET category = 'OTHER';

ALTER TABLE tickets ADD CONSTRAINT ck_tickets_category
    CHECK (category IN ('INCIDENT', 'SERVICE_REQUEST', 'ACCESS_REQUEST', 'OTHER'));

ALTER TABLE tickets ALTER COLUMN category SET NOT NULL;
