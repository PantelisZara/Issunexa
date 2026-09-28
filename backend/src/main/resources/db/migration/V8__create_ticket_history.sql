CREATE TABLE ticket_history_entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    ticket_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    previous_status VARCHAR(20),
    new_status VARCHAR(20),
    assignee_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_ticket_history_entries PRIMARY KEY (id),
    CONSTRAINT fk_ticket_history_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
    CONSTRAINT fk_ticket_history_actor FOREIGN KEY (actor_id) REFERENCES users(id),
    CONSTRAINT fk_ticket_history_assignee FOREIGN KEY (assignee_id) REFERENCES users(id),
    CONSTRAINT ck_ticket_history_type CHECK (type IN ('TICKET_CREATED', 'STATUS_CHANGED', 'ASSIGNEE_CLAIMED')),
    CONSTRAINT ck_ticket_history_previous_status CHECK (previous_status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_ticket_history_new_status CHECK (new_status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_ticket_history_event_shape CHECK (
        (type = 'TICKET_CREATED' AND previous_status IS NULL
            AND new_status IS NOT NULL AND new_status = 'OPEN' AND assignee_id IS NULL)
        OR (type = 'STATUS_CHANGED' AND previous_status IS NOT NULL AND new_status IS NOT NULL
            AND previous_status <> new_status AND assignee_id IS NULL)
        OR (type = 'ASSIGNEE_CLAIMED' AND previous_status IS NULL AND new_status IS NULL AND assignee_id IS NOT NULL)
    )
);

CREATE INDEX idx_ticket_history_ticket_created_id ON ticket_history_entries (ticket_id, created_at DESC, id DESC);
