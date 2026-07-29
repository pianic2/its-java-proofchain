ALTER TABLE digital_evidence
    ADD COLUMN custody_event_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN custody_chain_head_hash CHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000',
    ADD CONSTRAINT ck_digital_evidence_custody_event_count CHECK (custody_event_count >= 0),
    ADD CONSTRAINT ck_digital_evidence_custody_chain_head_hash CHECK (
        custody_chain_head_hash ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_digital_evidence_custody_chain_empty_head CHECK (
        custody_event_count > 0
        OR custody_chain_head_hash = '0000000000000000000000000000000000000000000000000000000000000000'
    );
