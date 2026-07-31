-- Test-only migration used by the failure certification. It is NEVER on the production migration path:
-- the runtime reads classpath:db/migration only, and this file lives under src/test/resources/db/invalid.
CREATE TABLE deliberately_invalid (
    id UUID NOT NULL,
    CONSTRAINT pk_deliberately_invalid PRIMARY KEY (id),
    CONSTRAINT fk_deliberately_invalid_missing_table
        FOREIGN KEY (id) REFERENCES table_that_does_not_exist (id)
);
