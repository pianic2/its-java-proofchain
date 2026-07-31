CREATE TABLE digital_evidence (
    id UUID NOT NULL,
    case_id UUID NOT NULL,
    reference_tag VARCHAR(64),
    title VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    status VARCHAR(16) NOT NULL,
    current_holder_operator_id UUID,
    uploaded_by_operator_id UUID NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_description VARCHAR(500),
    source_manufacturer VARCHAR(100),
    source_model VARCHAR(100),
    source_serial_number VARCHAR(200),
    source_logical_identifier VARCHAR(300),
    acquisition_method VARCHAR(32) NOT NULL,
    acquisition_location VARCHAR(300),
    acquisition_tool_name VARCHAR(200),
    acquisition_tool_version VARCHAR(100),
    acquisition_notes VARCHAR(2000),
    acquired_at TIMESTAMP(6) WITH TIME ZONE,
    original_filename VARCHAR(255) NOT NULL,
    file_extension VARCHAR(32),
    media_type VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    contextual_sha256 VARCHAR(64) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_digital_evidence PRIMARY KEY (id),
    CONSTRAINT fk_digital_evidence_case FOREIGN KEY (case_id) REFERENCES custody_cases (id),
    CONSTRAINT fk_digital_evidence_current_holder
        FOREIGN KEY (current_holder_operator_id) REFERENCES operators (id),
    CONSTRAINT fk_digital_evidence_uploaded_by
        FOREIGN KEY (uploaded_by_operator_id) REFERENCES operators (id),
    CONSTRAINT ck_digital_evidence_reference_tag CHECK (
        reference_tag IS NULL OR (
            char_length(reference_tag) BETWEEN 1 AND 64
            AND reference_tag = btrim(reference_tag)
            AND reference_tag = upper(reference_tag)
            AND reference_tag ~ '^[A-Z0-9][A-Z0-9._-]{0,63}$'
        )
    ),
    CONSTRAINT ck_digital_evidence_title CHECK (
        char_length(title) BETWEEN 3 AND 200 AND title = btrim(title)
    ),
    CONSTRAINT ck_digital_evidence_description CHECK (
        description IS NULL OR (char_length(description) BETWEEN 1 AND 2000 AND description = btrim(description))
    ),
    CONSTRAINT ck_digital_evidence_status CHECK (
        status IN ('IN_CUSTODY', 'SEALED', 'RELEASED')
    ),
    CONSTRAINT ck_digital_evidence_status_holder CHECK (
        (status IN ('IN_CUSTODY', 'SEALED') AND current_holder_operator_id IS NOT NULL)
        OR (status = 'RELEASED' AND current_holder_operator_id IS NULL)
    ),
    CONSTRAINT ck_digital_evidence_source_type CHECK (
        source_type IN (
            'DEVICE',
            'FILESYSTEM',
            'REMOVABLE_MEDIA',
            'CLOUD_SERVICE',
            'NETWORK_CAPTURE',
            'EMAIL',
            'DATABASE',
            'OTHER',
            'UNKNOWN'
        )
    ),
    CONSTRAINT ck_digital_evidence_source_description CHECK (
        source_description IS NULL OR (
            char_length(source_description) BETWEEN 1 AND 500 AND source_description = btrim(source_description)
        )
    ),
    CONSTRAINT ck_digital_evidence_source_manufacturer CHECK (
        source_manufacturer IS NULL OR (
            char_length(source_manufacturer) BETWEEN 1 AND 100 AND source_manufacturer = btrim(source_manufacturer)
        )
    ),
    CONSTRAINT ck_digital_evidence_source_model CHECK (
        source_model IS NULL OR (
            char_length(source_model) BETWEEN 1 AND 100 AND source_model = btrim(source_model)
        )
    ),
    CONSTRAINT ck_digital_evidence_source_serial_number CHECK (
        source_serial_number IS NULL OR (
            char_length(source_serial_number) BETWEEN 1 AND 200 AND source_serial_number = btrim(source_serial_number)
        )
    ),
    CONSTRAINT ck_digital_evidence_source_logical_identifier CHECK (
        source_logical_identifier IS NULL OR (
            char_length(source_logical_identifier) BETWEEN 1 AND 300
            AND source_logical_identifier = btrim(source_logical_identifier)
        )
    ),
    CONSTRAINT ck_digital_evidence_acquisition_method CHECK (
        acquisition_method IN ('PHYSICAL', 'LOGICAL', 'EXPORT', 'CAPTURE', 'MANUAL_UPLOAD', 'OTHER', 'UNKNOWN')
    ),
    CONSTRAINT ck_digital_evidence_acquisition_location CHECK (
        acquisition_location IS NULL OR (
            char_length(acquisition_location) BETWEEN 1 AND 300 AND acquisition_location = btrim(acquisition_location)
        )
    ),
    CONSTRAINT ck_digital_evidence_acquisition_tool_name CHECK (
        acquisition_tool_name IS NULL OR (
            char_length(acquisition_tool_name) BETWEEN 1 AND 200 AND acquisition_tool_name = btrim(acquisition_tool_name)
        )
    ),
    CONSTRAINT ck_digital_evidence_acquisition_tool_version CHECK (
        acquisition_tool_version IS NULL OR (
            char_length(acquisition_tool_version) BETWEEN 1 AND 100
            AND acquisition_tool_version = btrim(acquisition_tool_version)
        )
    ),
    CONSTRAINT ck_digital_evidence_acquisition_notes CHECK (
        acquisition_notes IS NULL OR (
            char_length(acquisition_notes) BETWEEN 1 AND 2000 AND acquisition_notes = btrim(acquisition_notes)
        )
    ),
    CONSTRAINT ck_digital_evidence_acquired_at CHECK (
        acquired_at IS NULL OR acquired_at <= created_at
    ),
    CONSTRAINT ck_digital_evidence_original_filename CHECK (
        char_length(original_filename) BETWEEN 1 AND 255
        AND original_filename = btrim(original_filename)
        AND original_filename NOT IN ('.', '..')
        AND position('/' IN original_filename) = 0
        AND position(chr(92) IN original_filename) = 0
        AND original_filename !~ '[[:cntrl:]]'
    ),
    CONSTRAINT ck_digital_evidence_file_extension CHECK (
        file_extension IS NULL OR (
            char_length(file_extension) BETWEEN 1 AND 32
            AND file_extension = btrim(file_extension)
            AND file_extension = lower(file_extension)
            AND position('/' IN file_extension) = 0
            AND position(chr(92) IN file_extension) = 0
            AND file_extension !~ '[[:cntrl:]]'
        )
    ),
    CONSTRAINT ck_digital_evidence_media_type CHECK (
        char_length(media_type) BETWEEN 1 AND 255
        AND media_type = btrim(media_type)
        AND media_type !~ '[[:cntrl:]]'
    ),
    CONSTRAINT ck_digital_evidence_file_size CHECK (file_size > 0),
    CONSTRAINT ck_digital_evidence_content_sha256 CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_digital_evidence_contextual_sha256 CHECK (contextual_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_digital_evidence_storage_key CHECK (
        char_length(storage_key) BETWEEN 1 AND 500
        AND storage_key = btrim(storage_key)
        AND storage_key NOT LIKE '/%'
        AND position(chr(92) IN storage_key) = 0
        AND position(':' IN storage_key) = 0
        AND position('//' IN storage_key) = 0
        AND storage_key !~ '(^|/)\.{1,2}(/|$)'
        AND storage_key !~ '[[:cntrl:]]'
    ),
    CONSTRAINT ck_digital_evidence_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_digital_evidence_version_non_negative CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_digital_evidence_case_reference_tag
    ON digital_evidence (case_id, reference_tag)
    WHERE reference_tag IS NOT NULL;

CREATE INDEX ix_digital_evidence_case_created_at_id
    ON digital_evidence (case_id, created_at DESC, id ASC);

CREATE INDEX ix_digital_evidence_current_holder_operator_id
    ON digital_evidence (current_holder_operator_id);

CREATE INDEX ix_digital_evidence_uploaded_by_operator_id
    ON digital_evidence (uploaded_by_operator_id);

CREATE INDEX ix_digital_evidence_content_sha256
    ON digital_evidence (content_sha256);

CREATE INDEX ix_digital_evidence_status
    ON digital_evidence (status);

CREATE INDEX ix_digital_evidence_source_type
    ON digital_evidence (source_type);

CREATE INDEX ix_digital_evidence_acquisition_method
    ON digital_evidence (acquisition_method);

CREATE INDEX ix_digital_evidence_created_at_id
    ON digital_evidence (created_at DESC, id ASC);
