CREATE TABLE custody_cases (
    id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    authority_name VARCHAR(200),
    external_reference VARCHAR(200),
    location VARCHAR(300),
    priority VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_by_operator_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    closed_at TIMESTAMP(6) WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_custody_cases PRIMARY KEY (id),
    CONSTRAINT fk_custody_cases_created_by_operator
        FOREIGN KEY (created_by_operator_id) REFERENCES operators (id),
    CONSTRAINT ck_custody_cases_title CHECK (
        char_length(title) BETWEEN 3 AND 200 AND title = btrim(title)
    ),
    CONSTRAINT ck_custody_cases_description CHECK (
        description IS NULL OR (char_length(description) BETWEEN 1 AND 2000 AND description = btrim(description))
    ),
    CONSTRAINT ck_custody_cases_authority_name CHECK (
        authority_name IS NULL OR (char_length(authority_name) BETWEEN 1 AND 200 AND authority_name = btrim(authority_name))
    ),
    CONSTRAINT ck_custody_cases_external_reference CHECK (
        external_reference IS NULL OR (
            char_length(external_reference) BETWEEN 1 AND 200 AND external_reference = btrim(external_reference)
        )
    ),
    CONSTRAINT ck_custody_cases_location CHECK (
        location IS NULL OR (char_length(location) BETWEEN 1 AND 300 AND location = btrim(location))
    ),
    CONSTRAINT ck_custody_cases_priority CHECK (
        priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    ),
    CONSTRAINT ck_custody_cases_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_custody_cases_closed_at_by_status CHECK (
        (status = 'OPEN' AND closed_at IS NULL) OR (status = 'CLOSED' AND closed_at IS NOT NULL)
    ),
    CONSTRAINT ck_custody_cases_version_non_negative CHECK (version >= 0)
);

CREATE INDEX ix_custody_cases_created_at_id ON custody_cases (created_at DESC, id ASC);

CREATE TABLE case_memberships (
    id UUID NOT NULL,
    case_id UUID NOT NULL,
    operator_id UUID NOT NULL,
    assigned_by_operator_id UUID NOT NULL,
    assigned_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_case_memberships PRIMARY KEY (id),
    CONSTRAINT fk_case_memberships_case FOREIGN KEY (case_id) REFERENCES custody_cases (id),
    CONSTRAINT fk_case_memberships_operator FOREIGN KEY (operator_id) REFERENCES operators (id),
    CONSTRAINT fk_case_memberships_assigned_by_operator
        FOREIGN KEY (assigned_by_operator_id) REFERENCES operators (id),
    CONSTRAINT uk_case_memberships_case_operator UNIQUE (case_id, operator_id)
);

CREATE INDEX ix_case_memberships_case_assigned_at_id
    ON case_memberships (case_id, assigned_at ASC, id ASC);

CREATE INDEX ix_case_memberships_operator_case ON case_memberships (operator_id, case_id);
