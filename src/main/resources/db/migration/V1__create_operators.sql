CREATE TABLE operators (
    id UUID NOT NULL,
    username VARCHAR(64) NOT NULL,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(60) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_operators PRIMARY KEY (id),
    CONSTRAINT uk_operators_username UNIQUE (username),
    CONSTRAINT uk_operators_email UNIQUE (email),
    CONSTRAINT ck_operators_username_length CHECK (char_length(username) BETWEEN 3 AND 64),
    CONSTRAINT ck_operators_username_pattern CHECK (username ~ '^[a-z0-9._-]+$'),
    CONSTRAINT ck_operators_username_normalized CHECK (username = lower(btrim(username))),
    CONSTRAINT ck_operators_email_length CHECK (char_length(email) BETWEEN 1 AND 320),
    CONSTRAINT ck_operators_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT ck_operators_password_hash_length CHECK (char_length(password_hash) = 60),
    CONSTRAINT ck_operators_first_name_length CHECK (
        char_length(first_name) BETWEEN 1 AND 100 AND first_name = btrim(first_name)
    ),
    CONSTRAINT ck_operators_last_name_length CHECK (
        char_length(last_name) BETWEEN 1 AND 100 AND last_name = btrim(last_name)
    ),
    CONSTRAINT ck_operators_role CHECK (
        role IN ('ADMIN', 'CASE_MANAGER', 'EVIDENCE_OFFICER', 'AUDITOR')
    ),
    CONSTRAINT ck_operators_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED')),
    CONSTRAINT ck_operators_version_non_negative CHECK (version >= 0)
);

CREATE INDEX ix_operators_active_admin_id
    ON operators (id)
    WHERE role = 'ADMIN' AND status = 'ACTIVE';
