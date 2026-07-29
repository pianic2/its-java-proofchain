ALTER TABLE digital_evidence
    ADD CONSTRAINT uk_digital_evidence_id_case UNIQUE (id, case_id);

CREATE TABLE custody_events (
    id UUID NOT NULL,
    case_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    operator_id UUID NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    sequence_number BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    payload_version INTEGER NOT NULL,
    payload_json JSONB NOT NULL,
    previous_hash VARCHAR(64) NOT NULL,
    event_hash VARCHAR(64) NOT NULL,
    hash_version INTEGER NOT NULL,
    CONSTRAINT pk_custody_events PRIMARY KEY (id),
    CONSTRAINT fk_custody_events_case
        FOREIGN KEY (case_id) REFERENCES custody_cases (id),
    CONSTRAINT fk_custody_events_evidence_case
        FOREIGN KEY (evidence_id, case_id) REFERENCES digital_evidence (id, case_id),
    CONSTRAINT fk_custody_events_operator
        FOREIGN KEY (operator_id) REFERENCES operators (id),
    CONSTRAINT uk_custody_events_evidence_sequence UNIQUE (evidence_id, sequence_number),
    CONSTRAINT uk_custody_events_evidence_hash UNIQUE (evidence_id, event_hash),
    CONSTRAINT ck_custody_events_actor_role CHECK (
        actor_role IN ('ADMIN', 'CASE_MANAGER', 'EVIDENCE_OFFICER', 'AUDITOR')
    ),
    CONSTRAINT ck_custody_events_sequence_positive CHECK (sequence_number > 0),
    CONSTRAINT ck_custody_events_event_type CHECK (
        event_type IN (
            'EVIDENCE_REGISTERED',
            'CUSTODY_TRANSFERRED',
            'METADATA_UPDATED',
            'INTEGRITY_VERIFIED',
            'EVIDENCE_SEALED',
            'EVIDENCE_RELEASED'
        )
    ),
    CONSTRAINT ck_custody_events_payload_version CHECK (payload_version = 1),
    CONSTRAINT ck_custody_events_payload_object CHECK (jsonb_typeof(payload_json) = 'object'),
    CONSTRAINT ck_custody_events_previous_hash CHECK (previous_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_custody_events_event_hash CHECK (event_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_custody_events_hash_version CHECK (hash_version = 1)
);

CREATE INDEX ix_custody_events_case_id ON custody_events (case_id);
CREATE INDEX ix_custody_events_operator_id ON custody_events (operator_id);
CREATE INDEX ix_custody_events_event_type ON custody_events (event_type);
CREATE INDEX ix_custody_events_occurred_at ON custody_events (occurred_at);

CREATE FUNCTION reject_custody_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '55000',
        MESSAGE = 'custody_events are append-only';
END;
$$;

CREATE TRIGGER custody_events_append_only
    BEFORE UPDATE OR DELETE ON custody_events
    FOR EACH ROW
    EXECUTE FUNCTION reject_custody_event_mutation();
