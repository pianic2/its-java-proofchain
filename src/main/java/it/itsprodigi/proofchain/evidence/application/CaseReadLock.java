package it.itsprodigi.proofchain.evidence.application;

import it.itsprodigi.proofchain.custodycase.domain.CustodyCase;
import java.util.Objects;
import java.util.UUID;

/**
 * Proof that the custody case read lock has already been acquired in the current transaction.
 *
 * <p>The token can only be produced by {@link EvidenceCommandLockService#lockCase(UUID)} and is required to lock
 * evidence, so the frozen lock order cannot be inverted by construction.
 */
public final class CaseReadLock {

    private final CustodyCase custodyCase;

    CaseReadLock(CustodyCase custodyCase) {
        this.custodyCase = Objects.requireNonNull(custodyCase, "custodyCase must not be null");
    }

    public CustodyCase custodyCase() {
        return custodyCase;
    }

    public UUID caseId() {
        return custodyCase.getId();
    }
}
