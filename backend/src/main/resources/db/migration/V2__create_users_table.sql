CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    email VARCHAR(254) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_nonblank CHECK (email ~ '[^[:space:]]'),
    CONSTRAINT ck_users_email_normalized CHECK (
        email = lower(regexp_replace(email, '^[[:space:]]+|[[:space:]]+$', '', 'g'))
    ),
    CONSTRAINT ck_users_display_name_nonblank CHECK (display_name ~ '[^[:space:]]')
);
