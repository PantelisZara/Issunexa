ALTER TABLE tickets ADD COLUMN requester_id BIGINT;

ALTER TABLE tickets ADD CONSTRAINT fk_tickets_requester FOREIGN KEY (requester_id) REFERENCES users(id);
