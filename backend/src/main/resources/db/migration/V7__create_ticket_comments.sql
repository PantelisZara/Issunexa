CREATE TABLE ticket_comments (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    ticket_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    body VARCHAR(4000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_ticket_comments PRIMARY KEY (id),
    CONSTRAINT fk_ticket_comments_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
    CONSTRAINT fk_ticket_comments_author FOREIGN KEY (author_id) REFERENCES users(id),
    CONSTRAINT ck_ticket_comments_body_nonblank CHECK (length(btrim(body)) > 0)
);

CREATE INDEX idx_ticket_comments_ticket_created_id ON ticket_comments (ticket_id, created_at, id);
