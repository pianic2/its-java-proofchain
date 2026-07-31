-- Enforces the exact digital evidence lifecycle graph in the database itself:
--
--     IN_CUSTODY -> SEALED
--     IN_CUSTODY -> RELEASED
--     SEALED     -> RELEASED
--
-- RELEASED is terminal. Together with the existing ck_digital_evidence_status_holder check, which already ties a
-- non-null holder to IN_CUSTODY/SEALED and a null holder to RELEASED, this makes it impossible for any statement --
-- application code, a repair script or a manual session -- to unseal, reopen or otherwise restore custody of released
-- evidence, or to give a released row a holder again.
--
-- The application never reaches this guard: seal and release validate the source state under the evidence write lock
-- before mutating. It is a last-resort invariant, not a control-flow mechanism, so it raises a check_violation that
-- surfaces as a sanitized persistence failure and rolls the whole command back.

-- The statements are written so that replaying this migration on a database that already carries the guard is a no-op,
-- exactly like the Sprint 4 backfill migration it follows.

CREATE OR REPLACE FUNCTION enforce_digital_evidence_lifecycle() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status
        AND NOT (
            (OLD.status = 'IN_CUSTODY' AND NEW.status IN ('SEALED', 'RELEASED'))
            OR (OLD.status = 'SEALED' AND NEW.status = 'RELEASED')
        )
    THEN
        RAISE EXCEPTION
            'illegal digital evidence lifecycle transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'ck_digital_evidence_lifecycle_transition';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS tr_digital_evidence_lifecycle_transition ON digital_evidence;

CREATE TRIGGER tr_digital_evidence_lifecycle_transition
    BEFORE UPDATE OF status ON digital_evidence
    FOR EACH ROW
    EXECUTE FUNCTION enforce_digital_evidence_lifecycle();
