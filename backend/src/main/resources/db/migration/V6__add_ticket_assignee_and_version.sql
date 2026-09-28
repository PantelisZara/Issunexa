ALTER TABLE tickets ADD COLUMN assignee_id BIGINT;

ALTER TABLE tickets ADD CONSTRAINT fk_tickets_assignee FOREIGN KEY (assignee_id) REFERENCES users(id);

ALTER TABLE tickets ADD COLUMN version BIGINT;

UPDATE tickets SET version = 0;

ALTER TABLE tickets ALTER COLUMN version SET NOT NULL;
